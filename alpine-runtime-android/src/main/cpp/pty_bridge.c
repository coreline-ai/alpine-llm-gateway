#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <unistd.h>

static int duplicate_cloexec(int fd) {
    return fcntl(fd, F_DUPFD_CLOEXEC, 0);
}

JNIEXPORT jobject JNICALL
Java_dev_alpine_runtime_android_internal_NativePtyBridge_nativeOpen(
    JNIEnv *env,
    jobject instance
) {
    (void) instance;
    int master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0) return NULL;
    if (grantpt(master) != 0 || unlockpt(master) != 0) {
        close(master);
        return NULL;
    }

    char slave_path[128];
    if (ptsname_r(master, slave_path, sizeof(slave_path)) != 0) {
        close(master);
        return NULL;
    }
    int read_fd = duplicate_cloexec(master);
    int write_fd = duplicate_cloexec(master);
    /* TIOCSWINSZ is conventionally issued on the PTY master. Keep a dedicated
     * master duplicate so stream ownership cannot close the resize endpoint. */
    int control_fd = duplicate_cloexec(master);
    if (read_fd < 0 || write_fd < 0 || control_fd < 0) {
        if (read_fd >= 0) close(read_fd);
        if (write_fd >= 0) close(write_fd);
        if (control_fd >= 0) close(control_fd);
        close(master);
        return NULL;
    }

    jclass descriptor_class = (*env)->FindClass(
        env,
        "dev/alpine/runtime/android/internal/NativePtyDescriptor"
    );
    if (descriptor_class == NULL) goto failure;
    jmethodID constructor = (*env)->GetMethodID(
        env,
        descriptor_class,
        "<init>",
        "(IIILjava/lang/String;)V"
    );
    if (constructor == NULL) goto failure;
    jstring path = (*env)->NewStringUTF(env, slave_path);
    if (path == NULL) goto failure;
    jobject descriptor = (*env)->NewObject(
        env,
        descriptor_class,
        constructor,
        read_fd,
        write_fd,
        control_fd,
        path
    );
    if (descriptor == NULL || (*env)->ExceptionCheck(env)) goto failure;
    close(master);
    return descriptor;

failure:
    close(read_fd);
    close(write_fd);
    close(control_fd);
    close(master);
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_dev_alpine_runtime_android_internal_NativePtyBridge_nativeResize(
    JNIEnv *env,
    jobject instance,
    jint fd,
    jint columns,
    jint rows
) {
    (void) env;
    (void) instance;
    if (fd < 0 || columns <= 0 || rows <= 0 || columns > 1000 || rows > 1000) {
        return JNI_FALSE;
    }
    struct winsize size = {
        .ws_row = (unsigned short) rows,
        .ws_col = (unsigned short) columns,
        .ws_xpixel = 0,
        .ws_ypixel = 0,
    };
    return ioctl(fd, TIOCSWINSZ, &size) == 0 ? JNI_TRUE : JNI_FALSE;
}

static jlong read_terminal_size(int fd) {
    struct winsize size;
    if (fd < 0 || !isatty(fd) || ioctl(fd, TIOCGWINSZ, &size) != 0) return 0;
    return ((jlong) size.ws_row << 32) | (jlong) size.ws_col;
}

JNIEXPORT jlong JNICALL
Java_dev_alpine_runtime_android_internal_NativePtyBridge_nativeReadSize(
    JNIEnv *env,
    jobject instance,
    jint fd
) {
    (void) env;
    (void) instance;
    return read_terminal_size(fd);
}

static jboolean resize_process_terminal_fd(int pid, int fd, int columns, int rows) {
    if (pid <= 1 || fd < 0 || fd > 255 || columns <= 0 || rows <= 0 ||
        columns > 1000 || rows > 1000) {
        return JNI_FALSE;
    }
    char path[64];
    int path_length = snprintf(path, sizeof(path), "/proc/%d/fd/%d", pid, fd);
    if (path_length <= 0 || (size_t) path_length >= sizeof(path)) return JNI_FALSE;
    int terminal_fd = open(path, O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (terminal_fd < 0) return JNI_FALSE;
    struct winsize size = {
        .ws_row = (unsigned short) rows,
        .ws_col = (unsigned short) columns,
        .ws_xpixel = 0,
        .ws_ypixel = 0,
    };
    jboolean result =
        isatty(terminal_fd) && ioctl(terminal_fd, TIOCSWINSZ, &size) == 0
            ? JNI_TRUE
            : JNI_FALSE;
    close(terminal_fd);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_dev_alpine_runtime_android_internal_NativePtyBridge_nativeResizeProcessTerminal(
    JNIEnv *env,
    jobject instance,
    jint pid,
    jint columns,
    jint rows
) {
    (void) env;
    (void) instance;
    return resize_process_terminal_fd(pid, 0, columns, rows);
}

JNIEXPORT jboolean JNICALL
Java_dev_alpine_runtime_android_internal_NativePtyBridge_nativeResizeProcessTerminalFd(
    JNIEnv *env,
    jobject instance,
    jint pid,
    jint fd,
    jint columns,
    jint rows
) {
    (void) env;
    (void) instance;
    return resize_process_terminal_fd(pid, fd, columns, rows);
}

static jlong read_process_terminal_size(int pid, int fd) {
    if (pid <= 1 || fd < 0 || fd > 255) return 0;
    char path[64];
    int path_length = snprintf(path, sizeof(path), "/proc/%d/fd/%d", pid, fd);
    if (path_length <= 0 || (size_t) path_length >= sizeof(path)) return 0;
    int terminal_fd = open(path, O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (terminal_fd < 0) return 0;
    jlong result = read_terminal_size(terminal_fd);
    close(terminal_fd);
    return result;
}

JNIEXPORT jlong JNICALL
Java_dev_alpine_runtime_android_internal_NativePtyBridge_nativeReadProcessTerminalSize(
    JNIEnv *env,
    jobject instance,
    jint pid
) {
    (void) env;
    (void) instance;
    return read_process_terminal_size(pid, 0);
}

JNIEXPORT jlong JNICALL
Java_dev_alpine_runtime_android_internal_NativePtyBridge_nativeReadProcessTerminalSizeFd(
    JNIEnv *env,
    jobject instance,
    jint pid,
    jint fd
) {
    (void) env;
    (void) instance;
    return read_process_terminal_size(pid, fd);
}
