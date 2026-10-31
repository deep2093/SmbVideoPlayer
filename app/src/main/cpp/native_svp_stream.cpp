// SkyWing bridge to the separately built Apache-2.0 open-svpflow C API.
#include "open_svpflow.h"
#include <jni.h>
#include <algorithm>
#include <condition_variable>
#include <cstring>
#include <memory>
#include <mutex>
#include <vector>

namespace {
constexpr int slots = 32;
struct Source {
    int width, height, count = 0;
    bool cancelled = false, ended = false;
    std::mutex mutex;
    std::condition_variable changed;
    std::vector<unsigned char> frames[slots];
    int indices[slots];
    Source(int w, int h): width(w), height(h) { std::fill_n(indices, slots, -1); }
};
struct Stream {
    std::shared_ptr<Source> source;
    osvp_context *context = nullptr;
    osvp_clip *input = nullptr, *output = nullptr;
    ~Stream() {
        osvp_clip_free(output);
        osvp_clip_free(input);
        osvp_destroy(context);
    }
};
int read_source(void *opaque, int n, uint8_t *const planes[3], const ptrdiff_t strides[3]) {
    auto source = *static_cast<std::shared_ptr<Source> *>(opaque);
    std::unique_lock<std::mutex> lock(source->mutex);
    source->changed.wait(lock, [&] { return source->cancelled || source->ended || n < source->count; });
    if (source->cancelled || source->count == 0) return 1;
    if (source->ended) n = std::min(n, source->count - 1);
    int slot = n % slots;
    if (source->indices[slot] != n) return 1; // Never substitute a recycled frame.
    const auto *data = source->frames[slot].data();
    for (int p = 0; p < 3; p++) {
        int w = source->width >> (p != 0), h = source->height >> (p != 0);
        for (int y = 0; y < h; y++) { memcpy(planes[p] + y * strides[p], data, w); data += w; }
    }
    return 0;
}
void release_source(void *opaque) { delete static_cast<std::shared_ptr<Source> *>(opaque); }

void error(JNIEnv *env, const char *message) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message); }
Stream *stream(jlong value) { return reinterpret_cast<Stream *>(value); }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_deepak_videolibrary_player_NativeSvpStream_create(JNIEnv *env, jobject, jstring directory,
    jint width, jint height, jlong fps_num, jlong fps_den) {
    if (width <= 0 || height <= 0 || width > 4096 || height > 2160 || (width | height) & 1 || fps_num <= 0 || fps_den <= 0) {
        error(env, "Unsupported native frame geometry/rate"); return 0;
    }
    auto result = std::make_unique<Stream>();
    result->source = std::make_shared<Source>(width, height);
    const char *dir = env->GetStringUTFChars(directory, nullptr);
    if (!dir) return 0;
    osvp_set_threads(4);
    result->context = osvp_create(dir);
    env->ReleaseStringUTFChars(directory, dir);
    if (!result->context) { error(env, osvp_error()); return 0; }
    osvp_video_info info{OSVP_YUV420P8, width, height, fps_num, fps_den, 10000000};
    auto *owner = new std::shared_ptr<Source>(result->source);
    result->input = osvp_source(result->context, &info, read_source, release_source, owner);
    if (!result->input) { delete owner; error(env, osvp_error()); return 0; }
    result->output = osvp_smooth_fps(result->context, result->input, "{gpu:1,pel:2}",
        "{block:{w:16,overlap:2}}", "{rate:{abs:true,num:60,den:1},algo:13,mask:{cover:80,area:30}}", 1);
    if (!result->output) { error(env, osvp_error()); return 0; }
    return reinterpret_cast<jlong>(result.release());
}

extern "C" JNIEXPORT void JNICALL
Java_com_deepak_videolibrary_player_NativeSvpStream_push(JNIEnv *env, jobject, jlong handle, jobject yuvBuffer) {
    auto s = stream(handle)->source;
    const auto *pixels = static_cast<const unsigned char *>(env->GetDirectBufferAddress(yuvBuffer));
    int w = s->width, h = s->height;
    size_t bytes = static_cast<size_t>(w) * h * 3 / 2;
    if (!pixels || env->GetDirectBufferCapacity(yuvBuffer) < static_cast<jlong>(bytes)) {
        error(env, "Native input buffer is too small"); return;
    }
    std::vector<unsigned char> yuv(pixels, pixels + bytes);
    std::lock_guard<std::mutex> lock(s->mutex);
    if (s->cancelled || s->ended) return;
    int i = s->count % slots;
    s->frames[i] = std::move(yuv);
    s->indices[i] = s->count++;
    s->changed.notify_all();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_deepak_videolibrary_player_NativeSvpStream_render(JNIEnv *env, jobject, jlong handle, jint index, jobject yuvBuffer) {
    auto *s = stream(handle);
    int w = s->source->width, h = s->source->height;
    auto *pixels = static_cast<unsigned char *>(env->GetDirectBufferAddress(yuvBuffer));
    if (!pixels || env->GetDirectBufferCapacity(yuvBuffer) < static_cast<jlong>(w) * h * 3 / 2) {
        error(env, "Native output buffer is too small"); return false;
    }
    // Permit two outputs of work ahead, as in the verified native pipeline.
    // The provider has eight frames of backpressure and cancellation wakes pending reads.
    osvp_clip_set_end(s->output, index + 2);
    const auto *frame = osvp_get_frame(s->output, index);
    if (!frame) {
        std::lock_guard<std::mutex> lock(s->source->mutex);
        if (!s->source->cancelled) error(env, osvp_error());
        return false;
    }
    for (int p = 0; p < 3; p++) {
        const auto *plane = osvp_frame_data(frame, p);
        auto stride = osvp_frame_stride(frame, p);
        int pw = w >> (p != 0), ph = h >> (p != 0);
        for (int y = 0; y < ph; y++) { memcpy(pixels, plane+y*stride, pw); pixels += pw; }
    }
    osvp_frame_free(frame);
    return true;
}
extern "C" JNIEXPORT void JNICALL
Java_com_deepak_videolibrary_player_NativeSvpStream_end(JNIEnv *, jobject, jlong handle) {
    auto s = stream(handle)->source;
    std::lock_guard<std::mutex> lock(s->mutex); s->ended = true; s->changed.notify_all();
}
extern "C" JNIEXPORT void JNICALL
Java_com_deepak_videolibrary_player_NativeSvpStream_cancel(JNIEnv *, jobject, jlong handle) {
    auto s = stream(handle)->source;
    std::lock_guard<std::mutex> lock(s->mutex); s->cancelled = true; s->changed.notify_all();
}
extern "C" JNIEXPORT void JNICALL
Java_com_deepak_videolibrary_player_NativeSvpStream_close(JNIEnv *, jobject, jlong handle) { delete stream(handle); }
