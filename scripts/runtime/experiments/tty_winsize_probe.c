/*
 * Probe-only independent guest-side TIOCGWINSZ check.
 *
 * This helper deliberately reports a small fixed classification instead of
 * terminal content, paths, process ids, or the measured dimensions.  It is
 * packaged only by alpine-runtime-probe and copied into that app's private
 * workspace for the PRoot resize investigation.
 */
/*
 * It has no Bionic or musl dependency: PRoot executes this as a guest binary
 * under the Alpine rootfs, while the Android app's own runtime library uses
 * Bionic.  Keeping the Linux/aarch64 syscall stub freestanding avoids making
 * either loader part of the experiment.
 */
typedef unsigned short u16;
typedef unsigned long ulong;

struct winsize {
    u16 rows;
    u16 columns;
    u16 xpixel;
    u16 ypixel;
};

static long syscall1(long number, long arg0) {
    register long x0 asm("x0") = arg0;
    register long x8 asm("x8") = number;
    asm volatile("svc #0" : "+r"(x0) : "r"(x8) : "memory");
    return x0;
}

static long syscall3(long number, long arg0, long arg1, long arg2) {
    register long x0 asm("x0") = arg0;
    register long x1 asm("x1") = arg1;
    register long x2 asm("x2") = arg2;
    register long x8 asm("x8") = number;
    asm volatile("svc #0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x8) : "memory");
    return x0;
}

__attribute__((noreturn)) void _start(void) {
    static const char dynamic[] = "tty_winsize_state=dynamic\n";
    static const char alternate[] = "tty_winsize_state=alternate\n";
    static const char initial[] = "tty_winsize_state=initial\n";
    static const char unexpected[] = "tty_winsize_state=unexpected\n";
    static const char unavailable[] = "tty_winsize_state=unavailable\n";
    const char *message = unavailable;
    ulong message_size = sizeof(unavailable) - 1;
    struct winsize size = {0, 0, 0, 0};

    /* aarch64 Linux: ioctl=29, write=64, exit=93; TIOCGWINSZ=0x5413. */
    if (syscall3(29, 0, 0x5413, (long)&size) == 0) {
        if (size.rows == 40 && size.columns == 120) {
            message = dynamic;
            message_size = sizeof(dynamic) - 1;
        } else if (size.rows == 24 && size.columns == 80) {
            message = alternate;
            message_size = sizeof(alternate) - 1;
        } else if (size.rows == 28 && size.columns == 96) {
            message = initial;
            message_size = sizeof(initial) - 1;
        } else {
            message = unexpected;
            message_size = sizeof(unexpected) - 1;
        }
    }
    (void)syscall3(64, 1, (long)message, (long)message_size);
    (void)syscall1(93, 0);
    __builtin_unreachable();
}
