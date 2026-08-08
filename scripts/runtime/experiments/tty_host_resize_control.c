/*
 * Probe-only control for the Android host PTY path.
 *
 * It creates a fresh PTY, makes a /system/bin/sh child its controlling-terminal
 * foreground process group, requests TIOCSWINSZ on the master, and confirms a
 * fixed SIGWINCH trap plus a later fixed input round trip.  It never receives
 * app terminal payload, credentials, paths, or guest process identifiers.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#define CONTROL_OUTPUT_MAX 1024

static long long monotonic_millis(void) {
    struct timespec value;
    if (clock_gettime(CLOCK_MONOTONIC, &value) != 0) return 0;
    return (long long)value.tv_sec * 1000LL + value.tv_nsec / 1000000LL;
}

static int wait_for_marker(int fd, char *output, size_t output_size, const char *marker, int timeout_ms) {
    long long deadline = monotonic_millis() + timeout_ms;
    size_t used = strnlen(output, output_size);

    while (monotonic_millis() < deadline) {
        long long remaining = deadline - monotonic_millis();
        struct pollfd poll_fd = {.fd = fd, .events = POLLIN, .revents = 0};
        int polled = poll(&poll_fd, 1, remaining > 100 ? 100 : (int)remaining);
        if (polled < 0 && errno == EINTR) continue;
        if (polled <= 0 || !(poll_fd.revents & (POLLIN | POLLHUP))) continue;
        if (used + 1 >= output_size) return -1;
        ssize_t count = read(fd, output + used, output_size - used - 1);
        if (count < 0 && (errno == EINTR || errno == EAGAIN)) continue;
        if (count <= 0) continue;
        used += (size_t)count;
        output[used] = '\0';
        if (strstr(output, marker) != NULL) return 0;
    }
    return -1;
}

static void stop_child(pid_t child) {
    int status;
    if (child <= 0) return;
    if (waitpid(child, &status, WNOHANG) == child) return;
    (void)kill(child, SIGKILL);
    while (waitpid(child, &status, 0) < 0 && errno == EINTR) {
    }
}

int main(void) {
    const char *shell = "/system/bin/sh";
    const char *script =
        "trap 'printf \"host_resize_winch\\n\"' WINCH; "
        "printf 'host_resize_ready\\n'; "
        "IFS= read -r ignored || true; "
        "printf 'host_resize_after_signal\\n'; "
        "IFS= read -r ignored || true; "
        "printf 'host_resize_after_input\\n'";
    char *const argv[] = {(char *)shell, "-c", (char *)script, NULL};
    char slave_path[128];
    char output[CONTROL_OUTPUT_MAX] = {0};
    struct termios attributes;
    struct winsize initial_size = {.ws_row = 28, .ws_col = 96, .ws_xpixel = 0, .ws_ypixel = 0};
    struct winsize resized = {.ws_row = 40, .ws_col = 120, .ws_xpixel = 0, .ws_ypixel = 0};
    int master = -1;
    int slave = -1;
    pid_t child = -1;
    int result = 1;

    master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0 || grantpt(master) != 0 || unlockpt(master) != 0 ||
        ptsname_r(master, slave_path, sizeof(slave_path)) != 0) {
        goto done;
    }
    slave = open(slave_path, O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (slave < 0 || tcgetattr(slave, &attributes) != 0) goto done;
    attributes.c_lflag &= (tcflag_t)~ECHO;
    if (tcsetattr(slave, TCSANOW, &attributes) != 0 ||
        ioctl(master, TIOCSWINSZ, &initial_size) != 0) {
        goto done;
    }
    child = fork();
    if (child < 0) goto done;
    if (child == 0) {
        close(master);
        if (setsid() < 0 || ioctl(slave, TIOCSCTTY, 0) != 0 ||
            tcsetpgrp(slave, getpid()) != 0 ||
            dup2(slave, STDIN_FILENO) < 0 || dup2(slave, STDOUT_FILENO) < 0 ||
            dup2(slave, STDERR_FILENO) < 0) {
            _exit(125);
        }
        if (slave > STDERR_FILENO) close(slave);
        execv(shell, argv);
        _exit(127);
    }
    close(slave);
    slave = -1;
    if (wait_for_marker(master, output, sizeof(output), "host_resize_ready", 2000) != 0 ||
        ioctl(master, TIOCSWINSZ, &resized) != 0) {
        goto done;
    }
    /* A shell may defer its trap until a blocking read returns. The fixed
     * newline intentionally exercises the first host input after resize. */
    if (write(master, "\n", 1) != 1 ||
        wait_for_marker(master, output, sizeof(output), "host_resize_winch", 2000) != 0 ||
        wait_for_marker(master, output, sizeof(output), "host_resize_after_signal", 2000) != 0 ||
        write(master, "x\n", 2) != 2 ||
        wait_for_marker(master, output, sizeof(output), "host_resize_after_input", 2000) != 0) {
        goto done;
    }
    result = 0;

done:
    if (slave >= 0) close(slave);
    if (master >= 0) close(master);
    stop_child(child);
    puts(result == 0 ? "host_resize_control=PASS" : "host_resize_control=FAIL");
    return result;
}
