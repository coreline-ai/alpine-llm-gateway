#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
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
    struct stat slave_stat;
    if (stat(slave_path, &slave_stat) != 0) {
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
        "(IIIJLjava/lang/String;)V"
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
        (jlong) slave_stat.st_rdev,
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

JNIEXPORT jboolean JNICALL
Java_dev_alpine_runtime_android_internal_NativePtyBridge_nativeResizeAndRequestProbeRelay(
    JNIEnv *env,
    jobject instance,
    jint fd,
    jint columns,
    jint rows,
    jstring relay_socket_path
) {
    (void) env;
    (void) instance;
    if (fd < 0 || columns <= 0 || rows <= 0 || columns > 1000 || rows > 1000 ||
        relay_socket_path == NULL) {
        return JNI_FALSE;
    }
    struct winsize size = {
        .ws_row = (unsigned short) rows,
        .ws_col = (unsigned short) columns,
        .ws_xpixel = 0,
        .ws_ypixel = 0,
    };
    if (ioctl(fd, TIOCSWINSZ, &size) != 0) return JNI_FALSE;

    const char *path = (*env)->GetStringUTFChars(env, relay_socket_path, NULL);
    if (path == NULL) return JNI_FALSE;
    size_t path_length = strlen(path);
    if (path_length == 0 || path_length >= sizeof(((struct sockaddr_un *) 0)->sun_path)) {
        (*env)->ReleaseStringUTFChars(env, relay_socket_path, path);
        return JNI_FALSE;
    }
    int socket_fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (socket_fd < 0) {
        (*env)->ReleaseStringUTFChars(env, relay_socket_path, path);
        return JNI_FALSE;
    }
    struct sockaddr_un address;
    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    memcpy(address.sun_path, path, path_length + 1);
    int connected = connect(socket_fd, (struct sockaddr *) &address, sizeof(address)) == 0;
    unsigned char request = 0x52; /* "R": request an already-owned relay. */
    unsigned char response = 0;
    int relayed = connected &&
        write(socket_fd, &request, sizeof(request)) == (ssize_t) sizeof(request) &&
        read(socket_fd, &response, sizeof(response)) == (ssize_t) sizeof(response) &&
        response == 0x41; /* "A": supervisor delivered to its PRoot child. */
    close(socket_fd);
    (*env)->ReleaseStringUTFChars(env, relay_socket_path, path);
    return relayed ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_alpine_runtime_android_internal_NativePtyBridge_nativeRequestProbeVirtualResize(
    JNIEnv *env,
    jobject instance,
    jint columns,
    jint rows,
    jstring relay_socket_path
) {
    (void) instance;
    if (columns <= 0 || rows <= 0 || columns > 1000 || rows > 1000 ||
        relay_socket_path == NULL) {
        return JNI_FALSE;
    }
    const char *path = (*env)->GetStringUTFChars(env, relay_socket_path, NULL);
    if (path == NULL) return JNI_FALSE;
    size_t path_length = strlen(path);
    if (path_length == 0 || path_length >= sizeof(((struct sockaddr_un *) 0)->sun_path)) {
        (*env)->ReleaseStringUTFChars(env, relay_socket_path, path);
        return JNI_FALSE;
    }
    int socket_fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (socket_fd < 0) {
        (*env)->ReleaseStringUTFChars(env, relay_socket_path, path);
        return JNI_FALSE;
    }
    struct sockaddr_un address;
    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    memcpy(address.sun_path, path, path_length + 1);
    int connected = connect(socket_fd, (struct sockaddr *) &address, sizeof(address)) == 0;
    unsigned char request[5] = {
        0x56, /* V: Probe-only virtual winsize frame. */
        (unsigned char) (((unsigned int) columns >> 8) & 0xff),
        (unsigned char) ((unsigned int) columns & 0xff),
        (unsigned char) (((unsigned int) rows >> 8) & 0xff),
        (unsigned char) ((unsigned int) rows & 0xff),
    };
    unsigned char response = 0;
    int relayed = connected &&
        write(socket_fd, request, sizeof(request)) == (ssize_t) sizeof(request) &&
        read(socket_fd, &response, sizeof(response)) == (ssize_t) sizeof(response) &&
        response == 0x41; /* A: direct PRoot control pipe acknowledged. */
    close(socket_fd);
    (*env)->ReleaseStringUTFChars(env, relay_socket_path, path);
    return relayed ? JNI_TRUE : JNI_FALSE;
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
