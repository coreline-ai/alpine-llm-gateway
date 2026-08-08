/*
 * Probe-only PRoot terminal session supervisor for a virtual winsize proof.
 *
 * A fixed five-byte request (V + big-endian columns/rows) is accepted on an
 * app-private AF_UNIX socket and written to a four-byte private memfd inherited
 * only by the direct PRoot process. PRoot consumes this fixed state only when
 * guest TIOCGWINSZ exits. No post-launch host/guest signal, guest PID lookup,
 * FIFO/polling loop, terminal-data parsing, or user text logging is used.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/signalfd.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#include <linux/memfd.h>

#define VIRTUAL_REQUEST ((uint8_t)0x56) /* V */
#define RELAY_ACCEPTED ((uint8_t)0x41) /* A */
#define RELAY_REJECTED ((uint8_t)0x45) /* E */
#define MAX_DIMENSION 1000

/* Android exposes memfd_create() only from API 30, while this Probe's
 * minSdk is 26. Invoke the architecture syscall directly so the supervisor
 * can fail closed on devices whose kernel does not provide it. */
static int create_virtual_winsize_memfd(void) {
#if defined(__NR_memfd_create)
    return (int)syscall(__NR_memfd_create, "alpine-tty-winsize", MFD_CLOEXEC);
#elif defined(SYS_memfd_create)
    return (int)syscall(SYS_memfd_create, "alpine-tty-winsize", MFD_CLOEXEC);
#else
    errno = ENOSYS;
    return -1;
#endif
}

static int close_fd(int *fd) {
    if (*fd >= 0) {
        int status = close(*fd);
        *fd = -1;
        return status;
    }
    return 0;
}

static int write_byte(int fd, uint8_t value) {
    return write(fd, &value, sizeof(value)) == (ssize_t)sizeof(value) ? 0 : -1;
}

static int read_byte(int fd, uint8_t *value) {
    return read(fd, value, sizeof(*value)) == (ssize_t)sizeof(*value) ? 0 : -1;
}

static int read_exact(int fd, uint8_t *buffer, size_t length) {
    size_t offset = 0;
    while (offset < length) {
        ssize_t read_count = read(fd, buffer + offset, length - offset);
        if (read_count <= 0) return -1;
        offset += (size_t)read_count;
    }
    return 0;
}

/* Fixed Probe literals only; no PID, terminal bytes, command or user text. */
static void record_relay_stage(const char *stage) {
    const char *path = getenv("PROOT_TTY_DIAGNOSTIC_FILE");
    int fd;

    if (path == NULL || path[0] == '\0') return;
    fd = open(path, O_WRONLY | O_APPEND | O_CLOEXEC);
    if (fd < 0) return;
    (void)write(fd, stage, strlen(stage));
    close(fd);
}

static int open_control_socket(const char *path) {
    struct sockaddr_un address;
    size_t path_length;
    int fd;

    if (path == NULL) return -1;
    path_length = strlen(path);
    if (path_length == 0 || path_length >= sizeof(address.sun_path)) return -1;
    fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    memcpy(address.sun_path, path, path_length + 1);
    (void)unlink(path);
    if (bind(fd, (const struct sockaddr *)&address, sizeof(address)) != 0 ||
        chmod(path, S_IRUSR | S_IWUSR) != 0 || listen(fd, 1) != 0) {
        close(fd);
        (void)unlink(path);
        return -1;
    }
    return fd;
}

static int publish_virtual_winsize_fd(int fd) {
    char value[32];
    int flags;
    int length;

    flags = fcntl(fd, F_GETFD);
    if (flags < 0 || fcntl(fd, F_SETFD, flags & ~FD_CLOEXEC) != 0) return -1;
    length = snprintf(value, sizeof(value), "%d", fd);
    if (length <= 0 || (size_t)length >= sizeof(value) ||
        setenv("PROOT_TTY_VIRTUAL_WINSIZE_FD", value, 1) != 0) return -1;
    return 0;
}

/* Probe-only topology control: ProcessBuilder normally opens the same slave
 * path independently for stdin/stdout/stderr. Native PTY hosts commonly dup a
 * single slave descriptor instead. This switch isolates that distinction
 * without touching the product launcher or replaying terminal input. */
static int canonicalize_stdio_if_requested(void) {
    if (getenv("ALPINE_TTY_CANONICALIZE_STDIO") == NULL) return 0;
    return dup2(STDIN_FILENO, STDOUT_FILENO) >= 0 &&
        dup2(STDIN_FILENO, STDERR_FILENO) >= 0 ? 0 : -1;
}

static void configure_child_group(int parent_to_child_read, int child_to_parent_write,
                                  int virtual_winsize_fd) {
    uint8_t go = 0;

    if (setpgid(0, 0) != 0 || publish_virtual_winsize_fd(virtual_winsize_fd) != 0 ||
        write_byte(child_to_parent_write, RELAY_ACCEPTED) != 0 ||
        read_byte(parent_to_child_read, &go) != 0 || go != RELAY_ACCEPTED) {
        _exit(125);
    }
    close(parent_to_child_read);
    close(child_to_parent_write);
    if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() == 1) _exit(124);
}

static int wait_for_child_group(pid_t child, int parent_to_child_write, int child_to_parent_read) {
    uint8_t ready = 0;

    if (read_byte(child_to_parent_read, &ready) != 0 || ready != RELAY_ACCEPTED ||
        tcsetpgrp(STDIN_FILENO, child) != 0 || write_byte(parent_to_child_write, RELAY_ACCEPTED) != 0) {
        close(parent_to_child_write);
        close(child_to_parent_read);
        return -1;
    }
    close(parent_to_child_write);
    close(child_to_parent_read);
    return 0;
}

static int dispatch_virtual_winsize(int client_fd, int memfd) {
    uint8_t request[5];
    uint8_t frame[4];
    unsigned int columns;
    unsigned int rows;

    if (read_exact(client_fd, request, sizeof(request)) != 0 || request[0] != VIRTUAL_REQUEST) {
        return -1;
    }
    columns = ((unsigned int)request[1] << 8) | request[2];
    rows = ((unsigned int)request[3] << 8) | request[4];
    if (columns == 0 || rows == 0 || columns > MAX_DIMENSION || rows > MAX_DIMENSION) return -1;
    if (getenv("ALPINE_TTY_VIRTUAL_WINSIZE_NO_WRITE") != NULL) {
        record_relay_stage("schema=1 event=VIRTUAL_WINSIZE stage=virtual_winsize_supervisor_no_write_control\n");
        return 0;
    }
    memcpy(frame, request + 1, sizeof(frame));
    if (pwrite(memfd, frame, sizeof(frame), 0) != (ssize_t)sizeof(frame)) return -1;
    record_relay_stage("schema=1 event=VIRTUAL_WINSIZE stage=virtual_winsize_supervisor_stored\n");
    return 0;
}

static int wait_for_child_or_relay(int server_fd, int memfd, pid_t child) {
    sigset_t blocked;
    struct pollfd poll_fds[2];
    int signal_fd = -1;
    int child_status = 125;

    sigemptyset(&blocked);
    sigaddset(&blocked, SIGCHLD);
    if (sigprocmask(SIG_BLOCK, &blocked, NULL) != 0) return child_status;
    signal_fd = signalfd(-1, &blocked, SFD_CLOEXEC);
    if (signal_fd < 0) return child_status;

    poll_fds[0].fd = server_fd;
    poll_fds[0].events = POLLIN;
    poll_fds[1].fd = signal_fd;
    poll_fds[1].events = POLLIN;
    while (1) {
        int polled = poll(poll_fds, 2, -1);
        if (polled < 0 && errno == EINTR) continue;
        if (polled <= 0) break;
        if (poll_fds[1].revents & POLLIN) {
            struct signalfd_siginfo signal_info;
            (void)read(signal_fd, &signal_info, sizeof(signal_info));
            if (waitpid(child, &child_status, WNOHANG) == child) break;
        }
        if (poll_fds[0].revents & POLLIN) {
            int client_fd = accept4(server_fd, NULL, NULL, SOCK_CLOEXEC);
            if (client_fd >= 0) {
                uint8_t response = dispatch_virtual_winsize(client_fd, memfd) == 0
                    ? RELAY_ACCEPTED : RELAY_REJECTED;
                (void)write_byte(client_fd, response);
                close(client_fd);
            }
        }
    }
    close_fd(&signal_fd);
    if (WIFEXITED(child_status)) return WEXITSTATUS(child_status);
    if (WIFSIGNALED(child_status)) return 128 + WTERMSIG(child_status);
    return 125;
}

int main(int argc, char *argv[]) {
    const char *socket_path = getenv("ALPINE_TTY_RELAY_SOCKET");
    int server_fd = -1;
    int parent_to_child[2] = {-1, -1};
    int child_to_parent[2] = {-1, -1};
    int virtual_winsize_fd = -1;
    pid_t child;
    int exit_status = 125;

    if (argc < 2 || socket_path == NULL) return 127;
    if (setsid() < 0 || ioctl(STDIN_FILENO, TIOCSCTTY, 0) < 0 ||
        canonicalize_stdio_if_requested() != 0) return 126;
    server_fd = open_control_socket(socket_path);
    virtual_winsize_fd = create_virtual_winsize_memfd();
    if (server_fd < 0 || virtual_winsize_fd < 0 || ftruncate(virtual_winsize_fd, 4) != 0 ||
        pipe2(parent_to_child, O_CLOEXEC) != 0 || pipe2(child_to_parent, O_CLOEXEC) != 0) {
        close_fd(&virtual_winsize_fd);
        close_fd(&server_fd);
        (void)unlink(socket_path);
        return 125;
    }
    child = fork();
    if (child == 0) {
        close(parent_to_child[1]);
        close(child_to_parent[0]);
        configure_child_group(parent_to_child[0], child_to_parent[1], virtual_winsize_fd);
        execv(argv[1], &argv[1]);
        _exit(errno == ENOENT ? 127 : 123);
    }
    close(parent_to_child[0]);
    close(child_to_parent[1]);
    if (child < 0 || wait_for_child_group(child, parent_to_child[1], child_to_parent[0]) != 0) {
        if (child > 0) (void)kill(child, SIGKILL);
        close_fd(&virtual_winsize_fd);
        close_fd(&server_fd);
        (void)unlink(socket_path);
        return 125;
    }
    exit_status = wait_for_child_or_relay(server_fd, virtual_winsize_fd, child);
    close_fd(&virtual_winsize_fd);
    close_fd(&server_fd);
    (void)unlink(socket_path);
    return exit_status;
}
