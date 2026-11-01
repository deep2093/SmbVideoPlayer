// SkyWing OpenCL tracing wrapper, used only by the in-app GPU lab.
//
// The open-svpflow engine loads OpenCL by name. When OSVP_OPENCL_LIB names this library, every OpenCL call the engine
// makes comes here first and is forwarded to the vendor libOpenCL.so. The wrapper records which queue each command was
// put on, which commands were flushed, which event each command produced, and what every thread is currently blocked
// in. A watchdog thread writes a report when a call or an event wait has been stuck for more than three seconds, so a
// hang inside the Adreno driver can be localised without root access or a native debugger.
//
// OSVP_CL_VARIANT selects an experimental behaviour (one per lab run, in a fresh process):
//   trace      forward unchanged (default)
//   flushwait  flush every known queue before any wait, poll or finish
//   flushall   flush the queue after every enqueued command
//   blockwrite make every host-to-device write blocking
//   all        all three changes together
// OSVP_TRACE_FILE names a file the report is appended to (the lab shows and shares it).
#define _GNU_SOURCE
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/syscall.h>

#define EXPORT __attribute__((visibility("default")))
typedef int32_t cl_int;
typedef uint32_t cl_uint;
typedef uint64_t cl_ulong;
typedef void *H;

#define CL_EVENT_COMMAND_QUEUE 0x11D0
#define CL_EVENT_COMMAND_TYPE 0x11D1
#define CL_EVENT_COMMAND_EXECUTION_STATUS 0x11D3
#define CL_DEVICE_NAME 0x102B
#define CL_DRIVER_VERSION 0x102D
#define CL_DEVICE_VERSION 0x102F
#define CL_TRUE 1
#define CL_PLATFORM_NOT_FOUND_KHR (-1001)

// ---- real entry points -------------------------------------------------------------------------------------------
static void *vendor;
static pthread_once_t load_once = PTHREAD_ONCE_INIT;
#define REAL(name) static __typeof__(&name) real_##name;
#define LOAD(name) real_##name = (__typeof__(real_##name))dlsym(vendor, #name);

EXPORT cl_int clGetPlatformIDs(cl_uint n, H *platforms, cl_uint *count);
EXPORT cl_int clGetDeviceIDs(H platform, cl_ulong type, cl_uint n, H *devices, cl_uint *count);
EXPORT cl_int clGetDeviceInfo(H device, cl_uint param, size_t size, void *value, size_t *ret);
EXPORT H clCreateContext(const intptr_t *props, cl_uint n, const H *devices, void (*notify)(const char *, const void *, size_t, void *), void *user, cl_int *err);
EXPORT H clCreateCommandQueue(H context, H device, cl_ulong props, cl_int *err);
EXPORT H clCreateProgramWithSource(H context, cl_uint count, const char **strings, const size_t *lengths, cl_int *err);
EXPORT cl_int clBuildProgram(H program, cl_uint n, const H *devices, const char *options, void (*notify)(H, void *), void *user);
EXPORT cl_int clGetProgramBuildInfo(H program, H device, cl_uint param, size_t size, void *value, size_t *ret);
EXPORT H clCreateKernel(H program, const char *name, cl_int *err);
EXPORT cl_int clGetKernelWorkGroupInfo(H kernel, H device, cl_uint param, size_t size, void *value, size_t *ret);
EXPORT H clCreateBuffer(H context, cl_ulong flags, size_t size, void *host, cl_int *err);
EXPORT H clCreateImage(H context, cl_ulong flags, const void *format, const void *desc, void *host, cl_int *err);
EXPORT H clCreateImage2D(H context, cl_ulong flags, const void *format, size_t w, size_t h, size_t pitch, void *host, cl_int *err);
EXPORT cl_int clEnqueueReadBuffer(H q, H mem, cl_uint blocking, size_t offset, size_t size, void *ptr, cl_uint nw, const H *waits, H *event);
EXPORT cl_int clEnqueueWriteBuffer(H q, H mem, cl_uint blocking, size_t offset, size_t size, const void *ptr, cl_uint nw, const H *waits, H *event);
EXPORT cl_int clEnqueueReadBufferRect(H q, H mem, cl_uint blocking, const size_t *buffer_origin, const size_t *host_origin, const size_t *region, size_t buffer_row, size_t buffer_slice, size_t host_row, size_t host_slice, void *ptr, cl_uint nw, const H *waits, H *event);
EXPORT cl_int clEnqueueWriteImage(H q, H image, cl_uint blocking, const size_t *origin, const size_t *region, size_t row, size_t slice, const void *ptr, cl_uint nw, const H *waits, H *event);
EXPORT cl_int clEnqueueFillBuffer(H q, H mem, const void *pattern, size_t pattern_size, size_t offset, size_t size, cl_uint nw, const H *waits, H *event);
EXPORT void *clEnqueueMapBuffer(H q, H mem, cl_uint blocking, cl_ulong flags, size_t offset, size_t size, cl_uint nw, const H *waits, H *event, cl_int *err);
EXPORT cl_int clEnqueueNDRangeKernel(H q, H kernel, cl_uint dims, const size_t *offset, const size_t *global, const size_t *local, cl_uint nw, const H *waits, H *event);
EXPORT cl_int clSetKernelArg(H kernel, cl_uint index, size_t size, const void *value);
EXPORT cl_int clFinish(H q);
EXPORT cl_int clFlush(H q);
EXPORT cl_int clWaitForEvents(cl_uint n, const H *events);
EXPORT cl_int clGetEventInfo(H event, cl_uint param, size_t size, void *value, size_t *ret);
EXPORT cl_int clReleaseEvent(H event);
EXPORT cl_int clReleaseMemObject(H mem);
EXPORT cl_int clReleaseCommandQueue(H q);
EXPORT cl_int clReleaseKernel(H kernel);
EXPORT cl_int clReleaseProgram(H program);

REAL(clGetPlatformIDs) REAL(clGetDeviceIDs) REAL(clGetDeviceInfo) REAL(clCreateContext) REAL(clCreateCommandQueue)
REAL(clCreateProgramWithSource) REAL(clBuildProgram) REAL(clGetProgramBuildInfo) REAL(clCreateKernel)
REAL(clGetKernelWorkGroupInfo) REAL(clCreateBuffer) REAL(clCreateImage) REAL(clCreateImage2D) REAL(clEnqueueReadBuffer)
REAL(clEnqueueWriteBuffer) REAL(clEnqueueReadBufferRect) REAL(clEnqueueWriteImage) REAL(clEnqueueFillBuffer)
REAL(clEnqueueMapBuffer) REAL(clEnqueueNDRangeKernel) REAL(clSetKernelArg) REAL(clFinish) REAL(clFlush)
REAL(clWaitForEvents) REAL(clGetEventInfo) REAL(clReleaseEvent) REAL(clReleaseMemObject) REAL(clReleaseCommandQueue)
REAL(clReleaseKernel) REAL(clReleaseProgram)

// ---- variants -----------------------------------------------------------------------------------------------------
static int flush_on_wait, flush_after_enqueue, blocking_writes;
static char trace_path[512];

// ---- bookkeeping ----------------------------------------------------------------------------------------------------
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static int64_t now_ns(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (int64_t)t.tv_sec * 1000000000LL + t.tv_nsec;
}
static int64_t start_ns;
static double since_start(int64_t ns) { return (ns - start_ns) / 1e9; }

#define MAX_QUEUES 64
typedef struct { H q; int id; long enqueued, flushes, unflushed; int64_t last_enqueue, last_flush; } Queue;
static Queue queues[MAX_QUEUES];
static int queue_count;

#define MAX_KERNELS 128
typedef struct { H kernel; char name[48]; } Kernel;
static Kernel kernels[MAX_KERNELS];
static int kernel_count;

#define EVENT_SLOTS 4096
typedef struct { H event; int queue; char what[64]; int64_t at; } EventOrigin;
static EventOrigin events[EVENT_SLOTS];

#define MAX_THREADS 64
typedef struct { int tid; const char *call; H a, b; int64_t since; H polled; int64_t poll_since; int reported; } ThreadState;
static ThreadState threads[MAX_THREADS];

#define RING 96
typedef struct { int64_t at; int tid; char text[96]; } Entry;
static Entry ring[RING];
static long ring_next;

// Queue slots. Only clCreateCommandQueue adds a queue; clReleaseCommandQueue frees its slot, so a released (freed) queue
// is never flushed again and the table does not fill up across seeks and new videos.
static int queue_index(H q) { // lock held; -1 for unknown or released queues
    if (!q) return -1;
    for (int i = 0; i < queue_count; i++) if (queues[i].q == q) return i;
    return -1;
}
static int queue_add(H q) { // lock held
    if (!q) return -1;
    int i = queue_index(q);
    if (i >= 0) return i;
    for (i = 0; i < queue_count; i++) if (!queues[i].q) break;
    if (i == queue_count) { if (queue_count >= MAX_QUEUES) return -1; queue_count++; }
    memset(&queues[i], 0, sizeof queues[i]);
    queues[i].q = q;
    queues[i].id = i;
    return i;
}
static const char *kernel_name(H k) { // lock held
    for (int i = 0; i < kernel_count; i++) if (kernels[i].kernel == k) return kernels[i].name;
    return "?";
}
static ThreadState *me(void) { // lock held
    int tid = (int)syscall(SYS_gettid);
    for (int i = 0; i < MAX_THREADS; i++) if (threads[i].tid == tid) return &threads[i];
    for (int i = 0; i < MAX_THREADS; i++) if (threads[i].tid == 0) { threads[i].tid = tid; return &threads[i]; }
    return &threads[MAX_THREADS - 1];
}
static void note(const char *fmt, ...) { // lock held
    Entry *e = &ring[ring_next++ % RING];
    e->at = now_ns();
    e->tid = (int)syscall(SYS_gettid);
    va_list args;
    va_start(args, fmt);
    vsnprintf(e->text, sizeof e->text, fmt, args);
    va_end(args);
}
static void remember_event(H event, H q, const char *what) { // lock held
    if (!event) return;
    EventOrigin *o = &events[((uintptr_t)event >> 4) % EVENT_SLOTS];
    o->event = event;
    o->queue = queue_index(q);
    o->at = now_ns();
    snprintf(o->what, sizeof o->what, "%s", what);
}
static EventOrigin *origin(H event) { // lock held
    EventOrigin *o = &events[((uintptr_t)event >> 4) % EVENT_SLOTS];
    return o->event == event ? o : NULL;
}
static void enqueued(H q, H *event, const char *what) {
    pthread_mutex_lock(&lock);
    int i = queue_index(q);
    if (i >= 0) { queues[i].enqueued++; queues[i].unflushed++; queues[i].last_enqueue = now_ns(); }
    if (event && *event) remember_event(*event, q, what);
    note("Q%d %s%s", i, what, event && *event ? " ->event" : "");
    pthread_mutex_unlock(&lock);
    if (flush_after_enqueue && real_clFlush) clFlush(q);
}
static void begin(const char *call, H a, H b) {
    pthread_mutex_lock(&lock);
    ThreadState *t = me();
    t->call = call; t->a = a; t->b = b; t->since = now_ns(); t->reported = 0;
    pthread_mutex_unlock(&lock);
}
static void end(void) {
    pthread_mutex_lock(&lock);
    ThreadState *t = me();
    t->call = NULL;
    pthread_mutex_unlock(&lock);
}
static pthread_rwlock_t queue_life = PTHREAD_RWLOCK_INITIALIZER;
static void flush_everything(void) {
    H all[MAX_QUEUES];
    int n;
    pthread_rwlock_rdlock(&queue_life);
    pthread_mutex_lock(&lock);
    n = queue_count;
    for (int i = 0; i < n; i++) all[i] = queues[i].q;
    pthread_mutex_unlock(&lock);
    for (int i = 0; i < n; i++) if (all[i]) real_clFlush(all[i]);
    pthread_rwlock_unlock(&queue_life);
}
// In flushwait mode: also flush every live queue once a millisecond, so a command whose cross-queue dependency finishes
// while every engine thread is blocked inside the driver (a blocking map or finish) still gets started.
static void *pump(void *unused) {
    (void)unused;
    for (;;) {
        usleep(1000);
        flush_everything();
    }
    return NULL;
}

// ---- report -------------------------------------------------------------------------------------------------------
static void emit(const char *text) {
    __android_log_write(ANDROID_LOG_WARN, "SkyWingClTrace", text);
    if (trace_path[0]) {
        FILE *f = fopen(trace_path, "a");
        if (f) { fputs(text, f); fputc('\n', f); fclose(f); }
    }
}
static void describe_event(H event, char *out, size_t size) { // lock held
    cl_int status = 99;
    cl_uint type = 0;
    H q = NULL;
    if (real_clGetEventInfo) {
        real_clGetEventInfo(event, CL_EVENT_COMMAND_EXECUTION_STATUS, sizeof status, &status, NULL);
        real_clGetEventInfo(event, CL_EVENT_COMMAND_TYPE, sizeof type, &type, NULL);
        real_clGetEventInfo(event, CL_EVENT_COMMAND_QUEUE, sizeof q, &q, NULL);
    }
    EventOrigin *o = origin(event);
    int qi = -1;
    for (int i = 0; i < queue_count; i++) if (queues[i].q == q) qi = i;
    snprintf(out, size, "event %p status=%d (0 done,1 running,2 submitted,3 queued,<0 error) type=0x%x queue=Q%d made by [%s] %.3fs ago",
             event, status, type, qi, o ? o->what : "unknown", o ? (now_ns() - o->at) / 1e9 : -1.0);
}
static void report(ThreadState *stuck, const char *why) { // lock held
    char line[512];
    snprintf(line, sizeof line, "=== OpenCL stall at %.2fs: thread %d %s ===", since_start(now_ns()), stuck->tid, why);
    emit(line);
    if (stuck->call) {
        snprintf(line, sizeof line, "blocked in %s(%p, %p) for %.2fs", stuck->call, stuck->a, stuck->b, (now_ns() - stuck->since) / 1e9);
        emit(line);
        for (int i = 0; i < queue_count; i++) if (queues[i].q == stuck->a) {
            snprintf(line, sizeof line, "  first argument is queue Q%d", i);
            emit(line);
        }
    }
    for (int i = 0; i < queue_count; i++) {
        Queue *q = &queues[i];
        snprintf(line, sizeof line, "Q%d: %ld commands, %ld flushes, %ld enqueued since last flush, last enqueue %.2fs ago, last flush %.2fs ago",
                 i, q->enqueued, q->flushes, q->unflushed, q->last_enqueue ? (now_ns() - q->last_enqueue) / 1e9 : -1.0,
                 q->last_flush ? (now_ns() - q->last_flush) / 1e9 : -1.0);
        emit(line);
    }
    for (int i = 0; i < MAX_THREADS; i++) {
        ThreadState *t = &threads[i];
        if (!t->tid || (!t->call && !t->polled)) continue;
        int64_t since = t->call ? t->since : t->poll_since;
        snprintf(line, sizeof line, "thread %d: %s for %.2fs%s", t->tid, t->call ? t->call : "polling an event",
                 since ? (now_ns() - since) / 1e9 : 0.0, t == stuck ? " (this one)" : "");
        emit(line);
    }
    emit("last OpenCL calls (oldest first):");
    long first = ring_next > RING ? ring_next - RING : 0;
    for (long i = first; i < ring_next; i++) {
        Entry *e = &ring[i % RING];
        snprintf(line, sizeof line, "  %.3fs t%d %s", since_start(e->at), e->tid, e->text);
        emit(line);
    }
    // Event queries go last: if the driver itself is wedged they could block, and everything above is already out.
    if (stuck->call && stuck->b && origin(stuck->b)) { describe_event(stuck->b, line, sizeof line); emit(line); }
    if (stuck->polled && origin(stuck->polled)) { describe_event(stuck->polled, line, sizeof line); emit(line); }
    emit("=== end of stall report ===");
}
static void *watchdog(void *unused) {
    (void)unused;
    for (;;) {
        usleep(250000);
        pthread_mutex_lock(&lock);
        int64_t t = now_ns();
        for (int i = 0; i < MAX_THREADS; i++) {
            ThreadState *s = &threads[i];
            if (!s->tid || s->reported) continue;
            if (s->call && t - s->since > 3000000000LL) { s->reported = 1; report(s, "call does not return"); }
            else if (!s->call && s->polled && t - s->poll_since > 3000000000LL) { s->reported = 1; report(s, "event never completes"); }
        }
        pthread_mutex_unlock(&lock);
    }
    return NULL;
}

static void load(void) {
    start_ns = now_ns();
    const char *variant = getenv("OSVP_CL_VARIANT");
    if (variant) {
        int all = strcmp(variant, "all") == 0;
        flush_on_wait = all || strcmp(variant, "flushwait") == 0;
        flush_after_enqueue = all || strcmp(variant, "flushall") == 0;
        blocking_writes = all || strcmp(variant, "blockwrite") == 0;
    }
    const char *path = getenv("OSVP_TRACE_FILE");
    if (path) snprintf(trace_path, sizeof trace_path, "%s", path);
    vendor = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
    char line[256];
    snprintf(line, sizeof line, "cl_trace: variant=%s vendor=%s", variant ? variant : "trace", vendor ? "loaded" : dlerror());
    emit(line);
    if (!vendor) return;
    LOAD(clGetPlatformIDs) LOAD(clGetDeviceIDs) LOAD(clGetDeviceInfo) LOAD(clCreateContext) LOAD(clCreateCommandQueue)
    LOAD(clCreateProgramWithSource) LOAD(clBuildProgram) LOAD(clGetProgramBuildInfo) LOAD(clCreateKernel)
    LOAD(clGetKernelWorkGroupInfo) LOAD(clCreateBuffer) LOAD(clCreateImage) LOAD(clCreateImage2D) LOAD(clEnqueueReadBuffer)
    LOAD(clEnqueueWriteBuffer) LOAD(clEnqueueReadBufferRect) LOAD(clEnqueueWriteImage) LOAD(clEnqueueFillBuffer)
    LOAD(clEnqueueMapBuffer) LOAD(clEnqueueNDRangeKernel) LOAD(clSetKernelArg) LOAD(clFinish) LOAD(clFlush)
    LOAD(clWaitForEvents) LOAD(clGetEventInfo) LOAD(clReleaseEvent) LOAD(clReleaseMemObject) LOAD(clReleaseCommandQueue)
    LOAD(clReleaseKernel) LOAD(clReleaseProgram)
    pthread_t thread;
    if (pthread_create(&thread, NULL, watchdog, NULL) == 0) pthread_detach(thread);
    if (flush_on_wait && pthread_create(&thread, NULL, pump, NULL) == 0) pthread_detach(thread);
}
#define READY(name, fail) pthread_once(&load_once, load); if (!real_##name) return fail;

// ---- forwarding ---------------------------------------------------------------------------------------------------
EXPORT cl_int clGetPlatformIDs(cl_uint n, H *platforms, cl_uint *count) {
    READY(clGetPlatformIDs, CL_PLATFORM_NOT_FOUND_KHR)
    return real_clGetPlatformIDs(n, platforms, count);
}
EXPORT cl_int clGetDeviceIDs(H platform, cl_ulong type, cl_uint n, H *devices, cl_uint *count) {
    READY(clGetDeviceIDs, -1)
    return real_clGetDeviceIDs(platform, type, n, devices, count);
}
EXPORT cl_int clGetDeviceInfo(H device, cl_uint param, size_t size, void *value, size_t *ret) {
    READY(clGetDeviceInfo, -1)
    return real_clGetDeviceInfo(device, param, size, value, ret);
}
EXPORT H clCreateContext(const intptr_t *props, cl_uint n, const H *devices, void (*notify)(const char *, const void *, size_t, void *), void *user, cl_int *err) {
    READY(clCreateContext, NULL)
    if (n > 0 && devices) {
        char name[128] = "", driver[128] = "", version[128] = "", line[512];
        real_clGetDeviceInfo(devices[0], CL_DEVICE_NAME, sizeof name, name, NULL);
        real_clGetDeviceInfo(devices[0], CL_DRIVER_VERSION, sizeof driver, driver, NULL);
        real_clGetDeviceInfo(devices[0], CL_DEVICE_VERSION, sizeof version, version, NULL);
        snprintf(line, sizeof line, "cl_trace: device=%s driver=%s version=%s", name, driver, version);
        emit(line);
    }
    return real_clCreateContext(props, n, devices, notify, user, err);
}
EXPORT H clCreateCommandQueue(H context, H device, cl_ulong props, cl_int *err) {
    READY(clCreateCommandQueue, NULL)
    H q = real_clCreateCommandQueue(context, device, props, err);
    pthread_mutex_lock(&lock);
    int i = queue_add(q);
    note("create Q%d props=0x%llx", i, (unsigned long long)props);
    pthread_mutex_unlock(&lock);
    return q;
}
EXPORT H clCreateProgramWithSource(H context, cl_uint count, const char **strings, const size_t *lengths, cl_int *err) {
    READY(clCreateProgramWithSource, NULL)
    return real_clCreateProgramWithSource(context, count, strings, lengths, err);
}
EXPORT cl_int clBuildProgram(H program, cl_uint n, const H *devices, const char *options, void (*notify)(H, void *), void *user) {
    READY(clBuildProgram, -1)
    begin("clBuildProgram", program, NULL);
    cl_int rc = real_clBuildProgram(program, n, devices, options, notify, user);
    end();
    return rc;
}
EXPORT cl_int clGetProgramBuildInfo(H program, H device, cl_uint param, size_t size, void *value, size_t *ret) {
    READY(clGetProgramBuildInfo, -1)
    return real_clGetProgramBuildInfo(program, device, param, size, value, ret);
}
EXPORT H clCreateKernel(H program, const char *name, cl_int *err) {
    READY(clCreateKernel, NULL)
    H k = real_clCreateKernel(program, name, err);
    pthread_mutex_lock(&lock);
    if (k && kernel_count < MAX_KERNELS) {
        kernels[kernel_count].kernel = k;
        snprintf(kernels[kernel_count].name, sizeof kernels[kernel_count].name, "%s", name ? name : "?");
        kernel_count++;
    }
    pthread_mutex_unlock(&lock);
    return k;
}
EXPORT cl_int clGetKernelWorkGroupInfo(H kernel, H device, cl_uint param, size_t size, void *value, size_t *ret) {
    READY(clGetKernelWorkGroupInfo, -1)
    return real_clGetKernelWorkGroupInfo(kernel, device, param, size, value, ret);
}
EXPORT H clCreateBuffer(H context, cl_ulong flags, size_t size, void *host, cl_int *err) {
    READY(clCreateBuffer, NULL)
    return real_clCreateBuffer(context, flags, size, host, err);
}
EXPORT H clCreateImage(H context, cl_ulong flags, const void *format, const void *desc, void *host, cl_int *err) {
    READY(clCreateImage, NULL)
    return real_clCreateImage(context, flags, format, desc, host, err);
}
EXPORT H clCreateImage2D(H context, cl_ulong flags, const void *format, size_t w, size_t h, size_t pitch, void *host, cl_int *err) {
    READY(clCreateImage2D, NULL)
    return real_clCreateImage2D(context, flags, format, w, h, pitch, host, err);
}
EXPORT cl_int clEnqueueReadBuffer(H q, H mem, cl_uint blocking, size_t offset, size_t size, void *ptr, cl_uint nw, const H *waits, H *event) {
    READY(clEnqueueReadBuffer, -1)
    if (flush_on_wait && blocking) flush_everything();
    if (blocking) begin("clEnqueueReadBuffer(blocking)", q, nw ? waits[0] : NULL);
    cl_int rc = real_clEnqueueReadBuffer(q, mem, blocking, offset, size, ptr, nw, waits, event);
    if (blocking) end();
    char what[64];
    snprintf(what, sizeof what, "read %zu B%s waits=%u", size, blocking ? " blocking" : "", nw);
    enqueued(q, event, what);
    return rc;
}
EXPORT cl_int clEnqueueWriteBuffer(H q, H mem, cl_uint blocking, size_t offset, size_t size, const void *ptr, cl_uint nw, const H *waits, H *event) {
    READY(clEnqueueWriteBuffer, -1)
    if (blocking_writes) blocking = CL_TRUE;
    if (blocking) begin("clEnqueueWriteBuffer(blocking)", q, NULL);
    cl_int rc = real_clEnqueueWriteBuffer(q, mem, blocking, offset, size, ptr, nw, waits, event);
    if (blocking) end();
    char what[64];
    snprintf(what, sizeof what, "write %zu B%s waits=%u", size, blocking ? " blocking" : "", nw);
    enqueued(q, event, what);
    return rc;
}
EXPORT cl_int clEnqueueReadBufferRect(H q, H mem, cl_uint blocking, const size_t *buffer_origin, const size_t *host_origin, const size_t *region, size_t buffer_row, size_t buffer_slice, size_t host_row, size_t host_slice, void *ptr, cl_uint nw, const H *waits, H *event) {
    READY(clEnqueueReadBufferRect, -1)
    if (flush_on_wait && blocking) flush_everything();
    if (blocking) begin("clEnqueueReadBufferRect(blocking)", q, NULL);
    cl_int rc = real_clEnqueueReadBufferRect(q, mem, blocking, buffer_origin, host_origin, region, buffer_row, buffer_slice, host_row, host_slice, ptr, nw, waits, event);
    if (blocking) end();
    enqueued(q, event, blocking ? "read rect blocking" : "read rect");
    return rc;
}
EXPORT cl_int clEnqueueWriteImage(H q, H image, cl_uint blocking, const size_t *origin, const size_t *region, size_t row, size_t slice, const void *ptr, cl_uint nw, const H *waits, H *event) {
    READY(clEnqueueWriteImage, -1)
    if (blocking_writes) blocking = CL_TRUE;
    if (blocking) begin("clEnqueueWriteImage(blocking)", q, NULL);
    cl_int rc = real_clEnqueueWriteImage(q, image, blocking, origin, region, row, slice, ptr, nw, waits, event);
    if (blocking) end();
    char what[64];
    snprintf(what, sizeof what, "write image %zux%zu%s waits=%u", region ? region[0] : 0, region ? region[1] : 0, blocking ? " blocking" : "", nw);
    enqueued(q, event, what);
    return rc;
}
EXPORT cl_int clEnqueueFillBuffer(H q, H mem, const void *pattern, size_t pattern_size, size_t offset, size_t size, cl_uint nw, const H *waits, H *event) {
    READY(clEnqueueFillBuffer, -1)
    cl_int rc = real_clEnqueueFillBuffer(q, mem, pattern, pattern_size, offset, size, nw, waits, event);
    enqueued(q, event, "fill buffer");
    return rc;
}
EXPORT void *clEnqueueMapBuffer(H q, H mem, cl_uint blocking, cl_ulong flags, size_t offset, size_t size, cl_uint nw, const H *waits, H *event, cl_int *err) {
    READY(clEnqueueMapBuffer, NULL)
    if (flush_on_wait && blocking) flush_everything();
    if (blocking) begin("clEnqueueMapBuffer(blocking)", q, nw ? waits[0] : NULL);
    void *p = real_clEnqueueMapBuffer(q, mem, blocking, flags, offset, size, nw, waits, event, err);
    if (blocking) end();
    enqueued(q, event, blocking ? "map blocking" : "map");
    return p;
}
EXPORT cl_int clEnqueueNDRangeKernel(H q, H kernel, cl_uint dims, const size_t *offset, const size_t *global, const size_t *local, cl_uint nw, const H *waits, H *event) {
    READY(clEnqueueNDRangeKernel, -1)
    cl_int rc = real_clEnqueueNDRangeKernel(q, kernel, dims, offset, global, local, nw, waits, event);
    char what[64];
    pthread_mutex_lock(&lock);
    snprintf(what, sizeof what, "kernel %s %zux%zu local=%s waits=%u rc=%d", kernel_name(kernel), global ? global[0] : 0,
             dims > 1 && global ? global[1] : 1, local ? "set" : "auto", nw, rc);
    pthread_mutex_unlock(&lock);
    enqueued(q, event, what);
    return rc;
}
EXPORT cl_int clSetKernelArg(H kernel, cl_uint index, size_t size, const void *value) {
    READY(clSetKernelArg, -1)
    return real_clSetKernelArg(kernel, index, size, value);
}
EXPORT cl_int clFinish(H q) {
    READY(clFinish, -1)
    if (flush_on_wait) flush_everything();
    pthread_mutex_lock(&lock);
    note("finish Q%d", queue_index(q));
    pthread_mutex_unlock(&lock);
    begin("clFinish", q, NULL);
    cl_int rc = real_clFinish(q);
    end();
    return rc;
}
EXPORT cl_int clFlush(H q) {
    READY(clFlush, -1)
    cl_int rc = real_clFlush(q);
    pthread_mutex_lock(&lock);
    int i = queue_index(q);
    if (i >= 0) { queues[i].flushes++; queues[i].unflushed = 0; queues[i].last_flush = now_ns(); }
    pthread_mutex_unlock(&lock);
    return rc;
}
EXPORT cl_int clWaitForEvents(cl_uint n, const H *events_list) {
    READY(clWaitForEvents, -1)
    if (flush_on_wait) flush_everything();
    pthread_mutex_lock(&lock);
    note("wait %u event(s)", n);
    pthread_mutex_unlock(&lock);
    begin("clWaitForEvents", NULL, n ? events_list[0] : NULL);
    cl_int rc = real_clWaitForEvents(n, events_list);
    end();
    return rc;
}
EXPORT cl_int clGetEventInfo(H event, cl_uint param, size_t size, void *value, size_t *ret) {
    READY(clGetEventInfo, -1)
    if (flush_on_wait && param == CL_EVENT_COMMAND_EXECUTION_STATUS) flush_everything();
    cl_int rc = real_clGetEventInfo(event, param, size, value, ret);
    if (param == CL_EVENT_COMMAND_EXECUTION_STATUS && value && size >= sizeof(cl_int)) {
        cl_int status = *(cl_int *)value;
        pthread_mutex_lock(&lock);
        ThreadState *t = me();
        if (status <= 0 || rc != 0) { t->polled = NULL; }
        else if (t->polled != event) { t->polled = event; t->poll_since = now_ns(); t->reported = 0; note("poll event status=%d", status); }
        if (status < 0) note("event error status=%d", status);
        pthread_mutex_unlock(&lock);
    }
    return rc;
}
EXPORT cl_int clReleaseEvent(H event) {
    READY(clReleaseEvent, -1)
    pthread_mutex_lock(&lock);
    EventOrigin *o = origin(event);
    if (o) o->event = NULL;
    for (int i = 0; i < MAX_THREADS; i++) if (threads[i].polled == event) threads[i].polled = NULL;
    pthread_mutex_unlock(&lock);
    return real_clReleaseEvent(event);
}
EXPORT cl_int clReleaseMemObject(H mem) {
    READY(clReleaseMemObject, -1)
    begin("clReleaseMemObject", mem, NULL);
    cl_int rc = real_clReleaseMemObject(mem);
    end();
    return rc;
}
EXPORT cl_int clReleaseCommandQueue(H q) {
    READY(clReleaseCommandQueue, -1)
    pthread_rwlock_wrlock(&queue_life);
    pthread_mutex_lock(&lock);
    int i = queue_index(q);
    note("release Q%d", i);
    if (i >= 0) queues[i].q = NULL;
    pthread_mutex_unlock(&lock);
    pthread_rwlock_unlock(&queue_life);
    begin("clReleaseCommandQueue", q, NULL);
    cl_int rc = real_clReleaseCommandQueue(q);
    end();
    return rc;
}
EXPORT cl_int clReleaseKernel(H kernel) {
    READY(clReleaseKernel, -1)
    begin("clReleaseKernel", kernel, NULL);
    cl_int rc = real_clReleaseKernel(kernel);
    end();
    return rc;
}
EXPORT cl_int clReleaseProgram(H program) {
    READY(clReleaseProgram, -1)
    begin("clReleaseProgram", program, NULL);
    cl_int rc = real_clReleaseProgram(program);
    end();
    return rc;
}
