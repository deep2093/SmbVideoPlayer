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

static int width = 640, height = 360;
static int raw_source = -1;
static char result_summary[256];
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
            int sx = x - n * 6;
            int object = sx >= 100 && sx < 400 && y >= 60 && y < 300;
            planes[0][y * strides[0] + x] = object ? ((y / 3) % 2 ? 200 : 50) : 110;
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
    if (argc < 2) { fprintf(stderr, "usage: probe plugin-directory [width height [yuv420p-file [frames [fps-num [fps-den [output-yuv]]]]]]\n"); return 2; }
    raw_source = -1;
    FILE *output = NULL;
    osvp_context *context = NULL;
    osvp_clip *source = NULL, *smooth = NULL;
    const osvp_frame *frame = NULL;
    int result = 1;
    if (argc >= 4) { width = atoi(argv[2]); height = atoi(argv[3]); }
    if (argc >= 5) {
        raw_source = open(argv[4], O_RDONLY);
        if (raw_source < 0) { perror("source"); return 1; }
    }
    if (width <= 0 || height <= 0 || width > 4096 || height > 4096 || width % 2 || height % 2) goto cleanup;
    int frames = argc >= 6 ? atoi(argv[5]) : 12;
    int fps_num = argc >= 7 ? atoi(argv[6]) : 24;
    int fps_den = argc >= 8 ? atoi(argv[7]) : 1;
    if (frames < 2 || frames > 120 || fps_num <= 0 || fps_den <= 0) goto cleanup;
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
    osvp_set_threads(4);
    context = osvp_create(argv[1]);
    if (!context) { fprintf(stderr, "create: %s\n", osvp_error()); goto cleanup; }
    osvp_video_info info = {OSVP_YUV420P8, width, height, fps_num, fps_den, frames};
    source = osvp_source(context, &info, read_source, NULL, NULL);
    if (!source) { fprintf(stderr, "source: %s\n", osvp_error()); goto cleanup; }
    const char *super_options = getenv("OSVP_GPU") ? "{gpu:1,pel:2}" : "{gpu:0,pel:2}";
    smooth = osvp_smooth_fps(context, source,
        super_options, "{block:{w:16,overlap:2}}",
        "{rate:{abs:true,num:60,den:1},algo:13,mask:{cover:80,area:30}}", 0);
    if (!smooth) { fprintf(stderr, "smooth: %s\n", osvp_error()); goto cleanup; }
    osvp_clip_info(smooth, &info);
    printf("output=%dx%d fps=%lld/%lld frames=%d\n", info.width, info.height,
        (long long)info.fps_num, (long long)info.fps_den, info.num_frames);
    double start = seconds();
    unsigned long checksum = 0;
    for (int n = 0; n < info.num_frames; ++n) {
        frame = osvp_get_frame(smooth, n);
        if (!frame) { fprintf(stderr, "frame %d: %s\n", n, osvp_error()); goto cleanup; }
        const uint8_t *data = osvp_frame_data(frame, 0);
        ptrdiff_t stride = osvp_frame_stride(frame, 0);
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
    printf("elapsed_seconds=%.3f output_fps=%.2f checksum=%lu\n", elapsed,
        info.num_frames / elapsed, checksum);
    snprintf(result_summary, sizeof(result_summary),
        "output=%dx%d fps=%lld/%lld frames=%d elapsed=%.4f throughput=%.2f checksum=%lu",
        info.width, info.height, (long long)info.fps_num, (long long)info.fps_den,
        info.num_frames, elapsed, info.num_frames / elapsed, checksum);
    result = 0;
cleanup:
    osvp_frame_free(frame);
    osvp_clip_free(smooth);
    osvp_clip_free(source);
    if (context) osvp_destroy(context);
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
    jstring directory, jint w, jint h, jboolean gpu) {
    (void)self;
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
