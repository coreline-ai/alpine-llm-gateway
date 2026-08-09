#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <limits.h>
#include <pty.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#define WAIT_TIMEOUT_RESULT INT_MIN
#define WAIT_ERROR_RESULT (INT_MIN + 1)

static int duplicate_cloexec(int fd) {
    return fcntl(fd, F_DUPFD_CLOEXEC, 0);
}

static void close_if_open(int fd) {
    if (fd >= 0) close(fd);
}

static void free_string_vector(char **values, size_t count) {
    if (values == NULL) return;
    for (size_t index = 0; index < count; index++) free(values[index]);
    free(values);
}

static char **copy_java_string_array(JNIEnv *env, jobjectArray values, size_t *count_out) {
    if (values == NULL || count_out == NULL) return NULL;
    jsize count = (*env)->GetArrayLength(env, values);
    if (count <= 0 || count > 1024) return NULL;
    char **copy = calloc((size_t) count + 1, sizeof(char *));
    if (copy == NULL) return NULL;
    for (jsize index = 0; index < count; index++) {
        jstring value = (jstring) (*env)->GetObjectArrayElement(env, values, index);
        if (value == NULL) goto failure;
        const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
        if (utf == NULL) {
            (*env)->DeleteLocalRef(env, value);
            goto failure;
        }
        copy[index] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, value, utf);
        (*env)->DeleteLocalRef(env, value);
        if (copy[index] == NULL) goto failure;
    }
    *count_out = (size_t) count;
    return copy;

failure:
    free_string_vector(copy, (size_t) count);
    return NULL;
}

static jobject make_descriptor(
    JNIEnv *env,
    int master,
    pid_t child_pid,
    const char *slave_path,
    dev_t slave_device_id
) {
    int read_fd = duplicate_cloexec(master);
    int write_fd = duplicate_cloexec(master);
    int control_fd = duplicate_cloexec(master);
    if (read_fd < 0 || write_fd < 0 || control_fd < 0) goto failure;

    jclass descriptor_class = (*env)->FindClass(
        env,
        "dev/alpine/runtime/android/internal/NativePtyDescriptor"
    );
    if (descriptor_class == NULL) goto failure;
    jmethodID constructor = (*env)->GetMethodID(
        env,
        descriptor_class,
        "<init>",
        "(IIIJLjava/lang/String;I)V"
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
        (jlong) slave_device_id,
        path,
        (jint) child_pid
    );
    if (descriptor == NULL || (*env)->ExceptionCheck(env)) goto failure;
    close(master);
    return descriptor;

failure:
    close_if_open(read_fd);
    close_if_open(write_fd);
    close_if_open(control_fd);
    close_if_open(master);
    if (child_pid > 0) {
        kill(-child_pid, SIGKILL);
        waitpid(child_pid, NULL, 0);
    }
    return NULL;
}

static int slave_identity_from_master(int master, char *path, size_t path_size, dev_t *device_id) {
    if (ptsname_r(master, path, path_size) != 0) return -1;
    struct stat slave_stat;
    if (stat(path, &slave_stat) != 0) return -1;
    *device_id = slave_stat.st_rdev;
    return 0;
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
    return make_descriptor(env, master, 0, slave_path, slave_stat.st_rdev);
}

JNIEXPORT jobject JNICALL
Java_dev_alpine_runtime_android_internal_NativePtyBridge_nativeForkExec(
    JNIEnv *env,
    jobject instance,
    jobjectArray argv_values,
    jobjectArray environment_values,
    jstring working_directory,
    jint columns,
    jint rows
) {
    (void) instance;
    if (argv_values == NULL || environment_values == NULL || working_directory == NULL ||
        columns <= 0 || rows <= 0 || columns > 1000 || rows > 1000) {
        return NULL;
    }

    size_t argc = 0;
    size_t environment_count = 0;
    char **argv = copy_java_string_array(env, argv_values, &argc);
    char **environment = copy_java_string_array(env, environment_values, &environment_count);
    const char *working_directory_utf = (*env)->GetStringUTFChars(env, working_directory, NULL);
    char *working_directory_copy = working_directory_utf == NULL ? NULL : strdup(working_directory_utf);
    if (working_directory_utf != NULL) {
        (*env)->ReleaseStringUTFChars(env, working_directory, working_directory_utf);
    }
    if (argv == NULL || environment == NULL || working_directory_copy == NULL || argc == 0 || argv[0][0] == '\0') {
        free_string_vector(argv, argc);
        free_string_vector(environment, environment_count);
        free(working_directory_copy);
        return NULL;
    }

    struct winsize size = {
        .ws_row = (unsigned short) rows,
        .ws_col = (unsigned short) columns,
        .ws_xpixel = 0,
        .ws_ypixel = 0,
    };
    int master = -1;
    pid_t pid = forkpty(&master, NULL, NULL, &size);
    if (pid < 0) {
        close_if_open(master);
        free_string_vector(argv, argc);
        free_string_vector(environment, environment_count);
        free(working_directory_copy);
        return NULL;
    }
    if (pid == 0) {
        /* After fork, do not access JNI, logging, or Java-owned state. forkpty() already made
         * this child a session leader with the slave PTY as its controlling terminal. */
        if (chdir(working_directory_copy) != 0) _exit(126);
        execve(argv[0], argv, environment);
        _exit(127);
    }

    free_string_vector(argv, argc);
    free_string_vector(environment, environment_count);
    free(working_directory_copy);
    char slave_path[128];
    dev_t slave_device_id = 0;
    if (slave_identity_from_master(master, slave_path, sizeof(slave_path), &slave_device_id) != 0) {
        kill(-pid, SIGKILL);
        waitpid(pid, NULL, 0);
        close_if_open(master);
        return NULL;
    }
    return make_descriptor(env, master, pid, slave_path, slave_device_id);
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

JNIEXPORT jint JNICALL
Java_dev_alpine_runtime_android_internal_NativePtyBridge_nativeWaitForChild(
    JNIEnv *env,
    jobject instance,
    jint pid,
    jlong timeout_millis
) {
    (void) env;
    (void) instance;
    if (pid <= 0 || timeout_millis < 0 || timeout_millis > 10000) return WAIT_ERROR_RESULT;
    const long long deadline_nanos = timeout_millis == 0 ? 0 :
        (long long) timeout_millis * 1000000LL;
    long long elapsed_nanos = 0;
    const struct timespec interval = { .tv_sec = 0, .tv_nsec = 10 * 1000 * 1000 };
    while (1) {
        int status = 0;
        pid_t result = waitpid((pid_t) pid, &status, WNOHANG);
        if (result == (pid_t) pid) {
            if (WIFEXITED(status)) return (jint) WEXITSTATUS(status);
            if (WIFSIGNALED(status)) return (jint) -(128 + WTERMSIG(status));
            return WAIT_ERROR_RESULT;
        }
        if (result < 0) return WAIT_ERROR_RESULT;
        if (timeout_millis == 0 || elapsed_nanos >= deadline_nanos) return WAIT_TIMEOUT_RESULT;
        nanosleep(&interval, NULL);
        elapsed_nanos += interval.tv_nsec;
    }
}

JNIEXPORT jboolean JNICALL
Java_dev_alpine_runtime_android_internal_NativePtyBridge_nativeSignalProcessGroup(
    JNIEnv *env,
    jobject instance,
    jint pid,
    jint signal
) {
    (void) env;
    (void) instance;
    if (pid <= 0 || signal <= 0 || signal >= 128) return JNI_FALSE;
    if (kill(-(pid_t) pid, signal) == 0) return JNI_TRUE;
    if (errno != ESRCH) return JNI_FALSE;
    return kill((pid_t) pid, signal) == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_alpine_runtime_android_internal_NativePtyBridge_nativeIsChildAlive(
    JNIEnv *env,
    jobject instance,
    jint pid
) {
    (void) env;
    (void) instance;
    if (pid <= 0) return JNI_FALSE;
    if (kill((pid_t) pid, 0) == 0) return JNI_TRUE;
    return errno == EPERM ? JNI_TRUE : JNI_FALSE;
}
