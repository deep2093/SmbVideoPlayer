/* Standalone Android feasibility probe for open-svpflow's C API.
 * Uses generated frames or an explicitly supplied local raw segment.
 * Serial diagnostic only: process-global source state is not a live-player bridge.
 * Build against the pinned external project's include/open_svpflow.h.
 */
#include "open_svpflow.h"
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <string.h>
#ifdef SKYWING_JNI
#include <android/log.h>
#endif

static int width = 640, height = 360;
static int raw_source = -1;
static int probe_frames = 12, probe_fps = 24, probe_half = 0, probe_threads = 4;
static int probe_profile = 0;
static int probe_guard_frames = 0;
static char result_summary[512];
static int luma(int x, int y, int n) {
    // Move textured content across the whole frame. A small moving rectangle on
    // a flat background let the engine return source frames for this fixture.
    int sx = (x + n * 6) % width, sy = (y + n * 2) % height;
    int background = 48 + (sx / 32 * 13 + sy / 32 * 29) % 96
        + ((sx / 16 + sy / 16) % 2) * 48;
    int object_x = (x + width - (n * 3) % width) % width;
    return object_x >= width / 3 && object_x < width / 3 + width / 5
        && y >= height / 4 && y < height * 3 / 4 ? ((y / 3) % 2 ? 210 : 45) : background;
}
static unsigned long long sample_hash(const uint8_t *data, ptrdiff_t stride, int source) {
    unsigned long long hash = 14695981039346656037ULL;
    for (int y = 0; y < height; y += 8)
        for (int x = 0; x < width; x += 4) {
            hash ^= data ? data[y * stride + x] : luma(x, y, source);
            hash *= 1099511628211ULL;
        }
    return hash;
}
static int compare_time(const void *a, const void *b) {
    double left = *(const double *)a, right = *(const double *)b;
    return (left > right) - (left < right);
}
static int32_t read_source(void *user, int32_t n, uint8_t *const planes[3],
                           const ptrdiff_t strides[3]) {
    (void)user;
    if (raw_source >= 0) {
        off_t offset = (off_t)n * width * height * 3 / 2;
        for (int p = 0; p < 3; ++p) {
            int pw = p ? width / 2 : width;
            int ph = p ? height / 2 : height;
            for (int y = 0; y < ph; ++y) {
                if (pread(raw_source, planes[p] + y * strides[p], pw, offset) != pw) return 1;
                offset += pw;
            }
        }
        return 0;
    }
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            planes[0][y * strides[0] + x] = luma(x, y, n);
        }
    }
    for (int p = 1; p < 3; ++p)
        for (int y = 0; y < height / 2; ++y)
            for (int x = 0; x < width / 2; ++x)
                planes[p][y * strides[p] + x] = 128;
    return 0;
}

static double seconds(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec + t.tv_nsec / 1e9;
}

int main(int argc, char **argv) {
    result_summary[0] = '\0';
    if (argc < 2) { fprintf(stderr, "usage: probe plugin-directory [width height [yuv420p-file [frames [fps-num [fps-den [output-yuv]]]]]]\n"); return 2; }
    raw_source = -1;
    FILE *output = NULL;
    osvp_context *context = NULL;
    osvp_clip *source = NULL, *smooth = NULL;
    const osvp_frame *frame = NULL;
    double *times = NULL;
    int result = 1;
    if (argc >= 4) { width = atoi(argv[2]); height = atoi(argv[3]); }
    if (argc >= 5) {
        raw_source = open(argv[4], O_RDONLY);
        if (raw_source < 0) { perror("source"); return 1; }
    }
    if (width <= 0 || height <= 0 || width > 4096 || height > 4096 || width % 2 || height % 2) goto cleanup;
    int frames = argc >= 6 ? atoi(argv[5]) : probe_frames;
    int fps_num = argc >= 7 ? atoi(argv[6]) : probe_fps;
    int fps_den = argc >= 8 ? atoi(argv[7]) : 1;
    if (frames < 2 || frames > (raw_source >= 0 ? 120 : 7200) || fps_num <= 0 || fps_den <= 0) goto cleanup;
    if (raw_source >= 0) {
        struct stat st;
        if (fstat(raw_source, &st) || st.st_size != (off_t)width * height * 3 / 2 * frames) {
            fprintf(stderr, "Raw input size does not match dimensions and frame count\n"); goto cleanup;
        }
    }
    if (argc >= 9) {
        output = fopen(argv[8], "wb");
        if (!output) { perror("output"); goto cleanup; }
    }
    osvp_set_threads(probe_threads);
    context = osvp_create(argv[1]);
    if (!context) { fprintf(stderr, "create: %s\n", osvp_error()); goto cleanup; }
    // A guarded sample is an interior window of a longer generated source.
    // Keep unguarded runs separate: they exercise the real finite-clip boundary.
    osvp_video_info info = {OSVP_YUV420P8, width, height, fps_num, fps_den, frames + probe_guard_frames};
    source = osvp_source(context, &info, read_source, NULL, NULL);
    if (!source) { fprintf(stderr, "source: %s\n", osvp_error()); goto cleanup; }
    const char *super_options = getenv("OSVP_GPU") ? "{gpu:1,pel:2}" : "{gpu:0,pel:2}";
    const char *analyse_options = "{block:{w:16,overlap:2}}";
    const char *smooth_options = "{rate:{abs:true,num:60,den:1},algo:13,mask:{cover:80,area:30}}";
    if (probe_profile == 1) {
        // Change only Super precision/storage, retaining the baseline search and synthesis.
        super_options = getenv("OSVP_GPU") ? "{gpu:1,pel:1,full:false,scale:{up:0}}" : "{gpu:0,pel:1,full:false,scale:{up:0}}";
    } else if (probe_profile == 2) {
        super_options = getenv("OSVP_GPU") ? "{gpu:1,pel:1,full:false,scale:{up:0}}" : "{gpu:0,pel:1,full:false,scale:{up:0}}";
        analyse_options = "{main:{search:{coarse:{bad:{range:24,sad:2000},distance:-8},type:2}},refine:[{thsad:250}]}";
        // Normalize rate to 60 fps and device ID to this host's zero-based enumeration.
        // This tests compatible options, not proprietary engine or scheduler equivalence.
        smooth_options = "{rate:{abs:true,num:60,den:1},algo:21,api:1,gpu_qn:1,gpuid:0,hdr:{dovi:true},mask:{area:100,cover:80},scene:{mode:3}}";
    } else if (probe_profile != 0) {
        fprintf(stderr, "Unknown probe profile\n"); goto cleanup;
    }
    smooth = osvp_smooth_fps(context, source,
        super_options, analyse_options, smooth_options, probe_half);
    if (!smooth) { fprintf(stderr, "smooth: %s\n", osvp_error()); goto cleanup; }
    osvp_clip_info(smooth, &info);
    if (probe_guard_frames > 0) {
        info.num_frames = (int)((long long)(frames - 1) * 60 * fps_den / fps_num) + 1;
    }
    printf("output=%dx%d fps=%lld/%lld frames=%d\n", info.width, info.height,
        (long long)info.fps_num, (long long)info.fps_den, info.num_frames);
    double start = seconds();
    times = calloc((size_t)info.num_frames, sizeof(*times));
    if (!times) goto cleanup;
    unsigned long checksum = 0;
    int intermediate = 0, distinct = 0;
    for (int n = 0; n < info.num_frames; ++n) {
#ifdef SKYWING_JNI
        if (info.num_frames <= 120 || n < 16 || n % 60 == 0 || n >= info.num_frames - 8)
            __android_log_print(4, "SvpProbe", "request frame=%d/%d half=%d workers=%d profile=%d distinct=%d/%d", n, info.num_frames, probe_half, probe_threads, probe_profile, distinct, intermediate);
#endif
        double frame_start = seconds();
        frame = osvp_get_frame(smooth, n);
        times[n] = (seconds() - frame_start) * 1000.0;
        if (!frame) { fprintf(stderr, "frame %d: %s\n", n, osvp_error()); goto cleanup; }
        const uint8_t *data = osvp_frame_data(frame, 0);
        ptrdiff_t stride = osvp_frame_stride(frame, 0);
        long long source_time = (long long)n * fps_num;
        int divisor = 60 * fps_den;
        if (raw_source < 0 && source_time % divisor && source_time / divisor + 1 < frames) {
            int left = (int)(source_time / divisor);
            unsigned long long hash = sample_hash(data, stride, -1);
            intermediate++;
            if (hash != sample_hash(NULL, 0, left) && hash != sample_hash(NULL, 0, left + 1)) distinct++;
        }
        for (int y = 0; y < height; y += 8)
            for (int x = 0; x < width; x += 8) checksum += data[y * stride + x];
        if (output) {
            for (int p = 0; p < 3; ++p) {
                int pw = p ? width / 2 : width;
                int ph = p ? height / 2 : height;
                const uint8_t *plane = osvp_frame_data(frame, p);
                ptrdiff_t pitch = osvp_frame_stride(frame, p);
                for (int y = 0; y < ph; ++y)
                    if (fwrite(plane + y * pitch, 1, pw, output) != (size_t)pw) {
                        fprintf(stderr, "Output write failed\n"); goto cleanup;
                    }
            }
        }
        osvp_frame_free(frame);
        frame = NULL;
    }
    if (output && fflush(output)) { perror("output flush"); goto cleanup; }
    double elapsed = seconds() - start;
    qsort(times, (size_t)info.num_frames, sizeof(*times), compare_time);
    printf("elapsed_seconds=%.3f output_fps=%.2f checksum=%lu\n", elapsed,
        info.num_frames / elapsed, checksum);
    snprintf(result_summary, sizeof(result_summary),
        "output=%dx%d fps=%lld/%lld frames=%d elapsed=%.4f throughput=%.2f checksum=%lu profile=%d half=%d threads=%d guard=%d intermediate=%d distinct=%d p50_ms=%.3f p95_ms=%.3f max_ms=%.3f",
        info.width, info.height, (long long)info.fps_num, (long long)info.fps_den,
        info.num_frames, elapsed, info.num_frames / elapsed, checksum, probe_profile, probe_half, probe_threads, probe_guard_frames,
        intermediate, distinct, times[info.num_frames / 2], times[(info.num_frames - 1) * 95 / 100], times[info.num_frames - 1]);
    result = 0;
cleanup:
#ifdef SKYWING_JNI
    __android_log_print(4, "SvpProbe", "cleanup status=%d result=%s", result, result_summary);
#endif
    free(times);
    osvp_frame_free(frame);
    osvp_clip_free(smooth);
#ifdef SKYWING_JNI
    __android_log_print(4, "SvpProbe", "output clip released");
#endif
    osvp_clip_free(source);
#ifdef SKYWING_JNI
    __android_log_print(4, "SvpProbe", "source clip released; destroying context");
#endif
    if (context) osvp_destroy(context);
#ifdef SKYWING_JNI
    __android_log_print(4, "SvpProbe", "cleanup finished status=%d", result);
#endif
    if (output && fclose(output)) result = 1;
    if (result && argc >= 9) unlink(argv[8]);
    if (raw_source >= 0) close(raw_source);
    raw_source = -1;
    return result;
}

#ifdef SKYWING_JNI
#include <jni.h>
JNIEXPORT jstring JNICALL
Java_com_deepak_videolibrary_player_NativeSvpProbe_run(JNIEnv *env, jobject self,
    jstring directory, jint w, jint h, jboolean gpu, jint frames, jint fps, jboolean half, jint threads, jint profile, jint guard_frames) {
    (void)self;
    probe_frames = frames; probe_fps = fps; probe_half = half;
    probe_threads = threads;
    probe_profile = profile;
    probe_guard_frames = guard_frames;
    const char *dir = (*env)->GetStringUTFChars(env, directory, NULL);
    if (!dir) return NULL;
    char ws[16], hs[16];
    snprintf(ws, sizeof(ws), "%d", w);
    snprintf(hs, sizeof(hs), "%d", h);
    char *args[] = {"probe", (char *)dir, ws, hs};
    if (gpu) setenv("OSVP_GPU", "1", 1); else unsetenv("OSVP_GPU");
    int status = main(4, args);
    (*env)->ReleaseStringUTFChars(env, directory, dir);
    if (status != 0) return (*env)->NewStringUTF(env, osvp_error());
    return (*env)->NewStringUTF(env, result_summary);
}
#endif
