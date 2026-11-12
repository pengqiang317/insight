#include <jni.h>
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <string.h>
#include <time.h>
#include <errno.h>
#include <stdlib.h>
#include <sys/syscall.h>
#include <sys/ucontext.h>
#include <unwind.h>

/*
 * 只在信号处理里使用 async-signal-safe 的调用：open/read/write/close、
 * sigaction、raise，以及不抢锁的内存读写。栈展开用 _Unwind_Backtrace，
 * 这是 Android 崩溃收集里的通行做法，但它本身不是严格的 signal-safe。
 * 写完报告后把信号交还原来的处理器，让系统 tombstone 和其它 SDK 继续工作。
 */

#define MAX_SIG 32
#define MAX_FRAMES 48
#define PATH_CAP 180
#define REPORT_CAP (64 * 1024)

static struct sigaction g_old[MAX_SIG];
static int g_hooked[MAX_SIG];
static char g_dir[512];
static char g_abi[32];
static char g_alt_stack[64 * 1024];
static int g_alt_ready;
static volatile sig_atomic_t g_handling;
static volatile sig_atomic_t g_java_crash;

static char g_report[REPORT_CAP];
static int g_len;
static uintptr_t g_frames[MAX_FRAMES];
static uintptr_t g_frame_off[MAX_FRAMES];
static int g_frame_ok[MAX_FRAMES];
static char g_frame_path[MAX_FRAMES][PATH_CAP];
static int g_frame_count;

static void append_char(char c) {
    if (g_len < REPORT_CAP - 1) {
        g_report[g_len++] = c;
    }
}

static void append(const char *s) {
    if (s == NULL) {
        return;
    }
    while (*s) {
        append_char(*s++);
    }
}

static void append_dec(unsigned long v) {
    char buf[24];
    int i = 0;
    if (v == 0) {
        append_char('0');
        return;
    }
    while (v > 0 && i < (int) sizeof(buf)) {
        buf[i++] = (char) ('0' + (v % 10));
        v /= 10;
    }
    while (i > 0) {
        append_char(buf[--i]);
    }
}

static void append_hex_pad(uintptr_t v, int digits) {
    int i;
    for (i = digits - 1; i >= 0; i--) {
        int shift = i * 4;
        int nibble = (int) ((v >> shift) & 0xf);
        append_char("0123456789abcdef"[nibble]);
    }
}

static const char *signal_name(int sig) {
    switch (sig) {
        case SIGSEGV:
            return "SIGSEGV";
        case SIGABRT:
            return "SIGABRT";
        case SIGBUS:
            return "SIGBUS";
        case SIGFPE:
            return "SIGFPE";
        case SIGILL:
            return "SIGILL";
        default:
            return "SIG";
    }
}

static void copy_string(JNIEnv *env, jstring value, char *dest, size_t cap) {
    const char *utf;
    size_t i = 0;
    if (dest == NULL || cap == 0) {
        return;
    }
    dest[0] = '\0';
    if (value == NULL) {
        return;
    }
    utf = (*env)->GetStringUTFChars(env, value, NULL);
    if (utf == NULL) {
        return;
    }
    while (utf[i] != '\0' && i + 1 < cap) {
        dest[i] = utf[i];
        i++;
    }
    dest[i] = '\0';
    (*env)->ReleaseStringUTFChars(env, value, utf);
}

static const char *parse_hex(const char *s, const char *end, uintptr_t *out) {
    uintptr_t v = 0;
    int digits = 0;
    while (s < end) {
        char c = *s;
        int h;
        if (c >= '0' && c <= '9') {
            h = c - '0';
        } else if (c >= 'a' && c <= 'f') {
            h = c - 'a' + 10;
        } else if (c >= 'A' && c <= 'F') {
            h = c - 'A' + 10;
        } else {
            break;
        }
        v = (v << 4) | (uintptr_t) h;
        s++;
        digits++;
    }
    if (digits == 0) {
        return NULL;
    }
    *out = v;
    return s;
}

static const char *skip_token(const char *s, const char *end) {
    while (s < end && *s == ' ') {
        s++;
    }
    while (s < end && *s != ' ') {
        s++;
    }
    return s;
}

static void note_mapping(uintptr_t start, uintptr_t end, uintptr_t off, const char *path, int path_len) {
    int i;
    if (path_len <= 0 || end <= start) {
        return;
    }
    for (i = 0; i < g_frame_count; i++) {
        uintptr_t pc;
        int n;
        int k;
        if (g_frame_ok[i]) {
            continue;
        }
        pc = g_frames[i];
        if (pc < start || pc >= end) {
            continue;
        }
        n = path_len;
        if (n > PATH_CAP - 1) {
            n = PATH_CAP - 1;
        }
        g_frame_ok[i] = 1;
        g_frame_off[i] = off + (pc - start);
        for (k = 0; k < n; k++) {
            g_frame_path[i][k] = path[k];
        }
        g_frame_path[i][n] = '\0';
    }
}

static void parse_map_line(const char *line, int len) {
    const char *end = line + len;
    uintptr_t start = 0;
    uintptr_t map_end = 0;
    uintptr_t off = 0;
    const char *s = parse_hex(line, end, &start);
    const char *perms;
    const char *perms_end;
    int executable = 0;
    if (s == NULL || s >= end || *s != '-') {
        return;
    }
    s = parse_hex(s + 1, end, &map_end);
    if (s == NULL || s >= end || *s != ' ') {
        return;
    }
    perms = s + 1;
    perms_end = perms;
    while (perms_end < end && *perms_end != ' ') {
        if (*perms_end == 'x') {
            executable = 1;
        }
        perms_end++;
    }
    if (!executable || perms_end >= end) {
        return;
    }
    s = parse_hex(perms_end + 1, end, &off);
    if (s == NULL) {
        return;
    }
    s = skip_token(s, end);
    s = skip_token(s, end);
    while (s < end && *s == ' ') {
        s++;
    }
    if (s >= end) {
        return;
    }
    note_mapping(start, map_end, off, s, (int) (end - s));
}

static void scan_maps(void) {
    int fd = open("/proc/self/maps", O_RDONLY | O_CLOEXEC);
    char chunk[256];
    char line[384];
    int line_len = 0;
    if (fd < 0) {
        return;
    }
    for (;;) {
        int n = (int) read(fd, chunk, sizeof(chunk));
        int i;
        if (n <= 0) {
            break;
        }
        for (i = 0; i < n; i++) {
            char c = chunk[i];
            if (c == '\n') {
                parse_map_line(line, line_len);
                line_len = 0;
            } else if (line_len < (int) sizeof(line) - 1) {
                line[line_len++] = c;
            }
        }
    }
    if (line_len > 0) {
        parse_map_line(line, line_len);
    }
    close(fd);
}

static _Unwind_Reason_Code unwind_cb(struct _Unwind_Context *ctx, void *arg) {
    uintptr_t ip;
    (void) arg;
    if (g_frame_count >= MAX_FRAMES) {
        return _URC_END_OF_STACK;
    }
    ip = (uintptr_t) _Unwind_GetIP(ctx);
    if (ip != 0) {
        g_frames[g_frame_count++] = ip;
    }
    return _URC_NO_REASON;
}

static uintptr_t read_pc(void *ucontext) {
    ucontext_t *uc = (ucontext_t *) ucontext;
    if (uc == NULL) {
        return 0;
    }
#if defined(__aarch64__)
    return (uintptr_t) uc->uc_mcontext.pc;
#elif defined(__arm__)
    return (uintptr_t) uc->uc_mcontext.arm_pc;
#elif defined(__x86_64__)
    return (uintptr_t) uc->uc_mcontext.gregs[REG_RIP];
#elif defined(__i386__)
    return (uintptr_t) uc->uc_mcontext.gregs[REG_EIP];
#else
    (void) uc;
    return 0;
#endif
}

static void write_report(int sig, siginfo_t *info, void *ucontext) {
    char path[640];
    int i;
    int fd;
    struct timespec now;
    uintptr_t pc;
    size_t dir_len;
    int path_len = 0;

    g_len = 0;
    g_frame_count = 0;
    memset(g_frame_ok, 0, sizeof(g_frame_ok));
    clock_gettime(CLOCK_REALTIME, &now);
    pc = read_pc(ucontext);
    if (pc != 0) {
        g_frames[g_frame_count++] = pc;
    }
    _Unwind_Backtrace(unwind_cb, NULL);
    scan_maps();

    append("*** *** *** Insight native crash *** *** ***\n");
    append("signal ");
    append_dec((unsigned long) sig);
    append(" (");
    append(signal_name(sig));
    append("), code ");
    append_dec(info != NULL ? (unsigned long) info->si_code : 0);
    append(", fault addr ");
    if (info != NULL && info->si_addr != NULL) {
        append("0x");
        append_hex_pad((uintptr_t) info->si_addr, (int) (sizeof(uintptr_t) * 2));
    } else {
        append("0x0");
    }
    append("\n");
    append("pid: ");
    append_dec((unsigned long) getpid());
    append(", tid: ");
    append_dec((unsigned long) syscall(SYS_gettid));
    append(", abi: ");
    append(g_abi);
    append("\n");
    append("time: ");
    append_dec((unsigned long) now.tv_sec);
    append("\n");
    append("backtrace:\n");

    for (i = 0; i < g_frame_count; i++) {
        if (i > 0 && g_frames[i] == g_frames[i - 1]) {
            continue;
        }
        append("    #");
        if (i < 10) {
            append_char('0');
        }
        append_dec((unsigned long) i);
        append(" pc ");
        append_hex_pad(g_frame_ok[i] ? g_frame_off[i] : g_frames[i], (int) (sizeof(uintptr_t) * 2));
        append("  ");
        if (g_frame_ok[i]) {
            append(g_frame_path[i]);
        } else {
            append("(no map)");
        }
        append("\n");
    }

    dir_len = strlen(g_dir);
    if (dir_len + 24 >= sizeof(path)) {
        return;
    }
    for (i = 0; i < (int) dir_len; i++) {
        path[i] = g_dir[i];
    }
    path[dir_len] = '/';
    path_len = (int) dir_len + 1;
    path[path_len++] = 'n';
    path[path_len++] = '-';
    {
        unsigned long sec = (unsigned long) now.tv_sec;
        char num[24];
        int n = 0;
        if (sec == 0) {
            num[n++] = '0';
        }
        while (sec > 0 && n < (int) sizeof(num)) {
            num[n++] = (char) ('0' + (sec % 10));
            sec /= 10;
        }
        while (n > 0) {
            path[path_len++] = num[--n];
        }
    }
    path[path_len++] = '.';
    path[path_len++] = 't';
    path[path_len++] = 'x';
    path[path_len++] = 't';
    path[path_len] = '\0';

    fd = open(path, O_CREAT | O_WRONLY | O_TRUNC | O_CLOEXEC, 0600);
    if (fd >= 0) {
        int left = g_len;
        int off = 0;
        while (left > 0) {
            int n = (int) write(fd, g_report + off, (size_t) left);
            if (n <= 0) {
                break;
            }
            off += n;
            left -= n;
        }
        close(fd);
    }
}

static void chain(int sig, siginfo_t *info, void *ucontext) {
    if (sig >= 0 && sig < MAX_SIG && g_hooked[sig]) {
        struct sigaction old = g_old[sig];
        if ((old.sa_flags & SA_SIGINFO) && old.sa_sigaction != NULL) {
            old.sa_sigaction(sig, info, ucontext);
            return;
        }
        if (old.sa_handler != NULL && old.sa_handler != SIG_DFL && old.sa_handler != SIG_IGN) {
            old.sa_handler(sig);
            return;
        }
    }
    {
        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_handler = SIG_DFL;
        sigaction(sig, &sa, NULL);
        raise(sig);
    }
}

static void crash_handler(int sig, siginfo_t *info, void *ucontext) {
    if (g_handling) {
        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_handler = SIG_DFL;
        sigaction(sig, &sa, NULL);
        raise(sig);
        return;
    }
    g_handling = 1;
    if (!g_java_crash) {
        write_report(sig, info, ucontext);
    }
    chain(sig, info, ucontext);
}

static void hook_one(int sig) {
    struct sigaction cur;
    struct sigaction neu;
    if (sig < 0 || sig >= MAX_SIG) {
        return;
    }
    memset(&cur, 0, sizeof(cur));
    if (sigaction(sig, NULL, &cur) != 0) {
        return;
    }
    if (cur.sa_sigaction == crash_handler) {
        return;
    }
    memset(&neu, 0, sizeof(neu));
    neu.sa_sigaction = crash_handler;
    neu.sa_flags = SA_SIGINFO;
    if (g_alt_ready) {
        neu.sa_flags |= SA_ONSTACK;
    }
    sigemptyset(&neu.sa_mask);
    if (sigaction(sig, &neu, NULL) == 0) {
        g_old[sig] = cur;
        g_hooked[sig] = 1;
    }
}

JNIEXPORT void JNICALL
Java_com_bigtree_insight_NativeBridge_installNative(JNIEnv *env, jclass clazz, jstring dir, jstring abi) {
    stack_t ss;
    (void) clazz;
    copy_string(env, dir, g_dir, sizeof(g_dir));
    copy_string(env, abi, g_abi, sizeof(g_abi));
    if (!g_alt_ready) {
        memset(&ss, 0, sizeof(ss));
        ss.ss_sp = g_alt_stack;
        ss.ss_size = sizeof(g_alt_stack);
        ss.ss_flags = 0;
        if (sigaltstack(&ss, NULL) == 0) {
            g_alt_ready = 1;
        }
    }
    hook_one(SIGSEGV);
    hook_one(SIGABRT);
    hook_one(SIGBUS);
    hook_one(SIGFPE);
    hook_one(SIGILL);
}

JNIEXPORT void JNICALL
Java_com_bigtree_insight_NativeBridge_markJavaCrashNative(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    g_java_crash = 1;
}
