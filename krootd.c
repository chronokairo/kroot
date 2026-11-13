/*
 * krootd - daemon root persistente do Kairos Root (kroot)
 *
 * Obtem uid 0 uma unica vez (via exploit kroot no boot) e serve pedidos de
 * elevacao por um socket Unix abstrato, autenticando o cliente com SO_PEERCRED.
 *
 * Protocolo (linha de cabecalho + payload binario):
 *   EXEC <flags> <uid> <gid> <cols> <rows> <term_len> <cmd_len>\n
 *   <term><cmd>
 *
 * Respostas:
 *   OK\n            -> em seguida vem 1 byte + 2 fds (master pty, status pipe)
 *   DENY <motivo>\n
 */
#define _GNU_SOURCE
#include "common.h"

#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>
#include <stddef.h>

#define MAX_HDR   512
#define MAX_TERM  256
#define MAX_CMD   65536
#define MAX_POL   256

struct pol_entry {
    uid_t uid;
    int allow;
    long exp;
    char pkg[64];
};

static struct pol_entry g_pol[MAX_POL];
static int g_npol;
static time_t g_pol_mtime = (time_t)-1;
static int g_foreground;
static volatile sig_atomic_t g_run = 1;

/* ---------------------------------------------------------------- log ---- */

static void logmsg(const char *fmt, ...) {
    char line[2048];
    char ts[32];
    time_t now = time(NULL);
    struct tm tm;
    va_list ap;
    int n;
    FILE *f;

    localtime_r(&now, &tm);
    strftime(ts, sizeof(ts), "%Y-%m-%d %H:%M:%S", &tm);

    n = snprintf(line, sizeof(line), "%s ", ts);
    if (n < 0 || (size_t)n >= sizeof(line))
        return;

    va_start(ap, fmt);
    vsnprintf(line + n, sizeof(line) - (size_t)n, fmt, ap);
    va_end(ap);

    f = fopen(KROOT_LOG, "a");
    if (f) {
        fprintf(f, "%s\n", line);
        fclose(f);
    }
    if (g_foreground)
        fprintf(stderr, "%s\n", line);
}

/* ----------------------------------------------------------- policy ---- */

static void load_policy(void) {
    struct stat st;
    FILE *f;
    char line[512];

    if (stat(KROOT_POLICY, &st) != 0) {
        g_npol = 0;
        return;
    }
    if (st.st_mtime == g_pol_mtime)
        return;

    g_pol_mtime = st.st_mtime;
    g_npol = 0;

    f = fopen(KROOT_POLICY, "r");
    if (!f)
        return;

    while (fgets(line, sizeof(line), f) && g_npol < MAX_POL) {
        unsigned uid = 0;
        char act[16] = {0};
        char pkg[64] = {0};
        long exp = 0;

        if (line[0] == '#' || line[0] == '\n')
            continue;

        if (sscanf(line, "%u\t%15s\t%63s\t%ld", &uid, act, pkg, &exp) < 2)
            continue;

        g_pol[g_npol].uid = (uid_t)uid;
        g_pol[g_npol].allow = strcmp(act, "allow") == 0;
        g_pol[g_npol].exp = exp;
        snprintf(g_pol[g_npol].pkg, sizeof(g_pol[g_npol].pkg), "%s", pkg);
        g_npol++;
    }

    fclose(f);
}

/* retorna 1 se pode, 0 se nao. preenche why. */
static int authorize(uid_t peer, char *why, size_t whylen) {
    time_t now;

    if (peer == 0)
        return 1;

    load_policy();
    now = time(NULL);

    for (int i = 0; i < g_npol; i++) {
        if (g_pol[i].uid != peer)
            continue;
        if (!g_pol[i].allow) {
            snprintf(why, whylen, "uid %u negado na policy", (unsigned)peer);
            return 0;
        }
        if (g_pol[i].exp > 0 && now > g_pol[i].exp) {
            snprintf(why, whylen, "uid %u expirou", (unsigned)peer);
            return 0;
        }
        return 1;
    }

    snprintf(why, whylen, "uid %u sem permissao", (unsigned)peer);
    return 0;
}

/* -------------------------------------------------------- pty / exec ---- */

static void child_run(const char *slave_name, uid_t uid, gid_t gid,
                      const char *term, const char *cmd, unsigned flags,
                      unsigned cols, unsigned rows) {
    int slave;
    struct termios tio;
    struct winsize ws;

    setsid();

    slave = open(slave_name, O_RDWR | O_NOCTTY);
    if (slave < 0)
        _exit(126);

    ioctl(slave, TIOCSCTTY, 0);

    if (tcgetattr(slave, &tio) == 0) {
        tio.c_oflag &= ~(ONLCR | OPOST);
        tio.c_iflag &= ~(ICRNL | INLCR | IXON);
        tio.c_lflag |= ECHO | ICANON | ISIG | IEXTEN;
        tcsetattr(slave, TCSANOW, &tio);
    }

    if (cols > 0 && rows > 0) {
        memset(&ws, 0, sizeof(ws));
        ws.ws_col = (unsigned short)cols;
        ws.ws_row = (unsigned short)rows;
        ioctl(slave, TIOCSWINSZ, &ws);
    }

    dup2(slave, STDIN_FILENO);
    dup2(slave, STDOUT_FILENO);
    dup2(slave, STDERR_FILENO);
    if (slave > STDERR_FILENO)
        close(slave);

    setenv("PATH", "/sbin:/system/sbin:/system/bin:/system/xbin:/data/local/xbin:/data/local/bin", 1);
    setenv("TERM", term[0] ? term : "xterm-256color", 1);
    setenv("HOME", uid == 0 ? "/root" : "/", 1);
    setenv("USER", uid == 0 ? "root" : "nobody", 1);
    setenv("LOGNAME", uid == 0 ? "root" : "nobody", 1);

    if (uid != 0 || gid != 0) {
        setgroups(0, NULL);
        if (setgid(gid) != 0)
            _exit(126);
        if (setuid(uid) != 0)
            _exit(126);
    }

    chdir("/");

    if (cmd[0])
        execl("/system/bin/sh", "sh", "-c", cmd, (char *)NULL);
    else if (flags & KF_LOGIN)
        execl("/system/bin/sh", "-sh", (char *)NULL);
    else
        execl("/system/bin/sh", "sh", (char *)NULL);

    _exit(127);
}

static void deny(int fd, const char *why) {
    char buf[256];
    int n = snprintf(buf, sizeof(buf), "DENY %s\n", why);
    if (n > 0)
        k_write_full(fd, buf, (size_t)n);
}

/* ------------------------------------------------------------ handler ---- */

static void handle_client(int cfd, struct ucred cr) {
    char hdr[MAX_HDR];
    char why[160];
    char *term = NULL;
    char *cmd = NULL;
    unsigned flags = 0, uid = 0, gid = 0, cols = 0, rows = 0;
    unsigned tlen = 0, clen = 0;
    int master = -1, sp[2] = {-1, -1};
    pid_t pid;
    char slave_name[128];
    int status, code;

    signal(SIGCHLD, SIG_DFL);

    if (k_read_line(cfd, hdr, sizeof(hdr)) <= 0)
        goto out;

    if (sscanf(hdr, "EXEC %u %u %u %u %u %u %u",
               &flags, &uid, &gid, &cols, &rows, &tlen, &clen) != 7) {
        deny(cfd, "pedido invalido");
        logmsg("BAD peer=%u hdr='%s'", (unsigned)cr.uid, hdr);
        goto out;
    }

    if (tlen > MAX_TERM || clen > MAX_CMD) {
        deny(cfd, "tamanho invalido");
        goto out;
    }

    term = calloc(1, tlen + 1);
    cmd = calloc(1, clen + 1);
    if (!term || !cmd) {
        deny(cfd, "sem memoria");
        goto out;
    }

    if (tlen && k_read_full(cfd, term, tlen) != 0) {
        deny(cfd, "payload truncado");
        goto out;
    }
    if (clen && k_read_full(cfd, cmd, clen) != 0) {
        deny(cfd, "payload truncado");
        goto out;
    }

    why[0] = '\0';
    if (!authorize(cr.uid, why, sizeof(why))) {
        deny(cfd, why);
        logmsg("DENY peer=%u(pid=%d) uid=%u cmd='%s' motivo='%s'",
               (unsigned)cr.uid, (int)cr.pid, uid, cmd, why);
        goto out;
    }

    master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0 || grantpt(master) != 0 || unlockpt(master) != 0) {
        deny(cfd, "falha ao criar pty");
        goto out;
    }

    snprintf(slave_name, sizeof(slave_name), "%s", ptsname(master));

    if (pipe(sp) != 0) {
        deny(cfd, "falha ao criar pipe");
        goto out;
    }

    pid = fork();
    if (pid < 0) {
        deny(cfd, "falha no fork");
        goto out;
    }

    if (pid == 0) {
        close(cfd);
        close(master);
        close(sp[0]);
        child_run(slave_name, (uid_t)uid, (gid_t)gid, term, cmd, flags, cols, rows);
        _exit(127);
    }

    if (k_write_full(cfd, "OK\n", 3) != 0) {
        logmsg("cliente sumiu antes do OK (peer=%u)", (unsigned)cr.uid);
        goto kill_child;
    }

    {
        int fds[2] = {master, sp[0]};
        if (k_send_fds(cfd, "F", 1, fds, 2) != 1) {
            logmsg("falha ao enviar fds (peer=%u)", (unsigned)cr.uid);
            goto kill_child;
        }
    }

    close(master);
    master = -1;
    close(sp[0]);
    sp[0] = -1;

    logmsg("EXEC peer=%u(pid=%d) uid=%u gid=%u cmd='%s'",
           (unsigned)cr.uid, (int)cr.pid, uid, gid, cmd[0] ? cmd : "(shell)");

    if (waitpid(pid, &status, 0) < 0)
        code = 1;
    else if (WIFEXITED(status))
        code = WEXITSTATUS(status);
    else if (WIFSIGNALED(status))
        code = 128 + WTERMSIG(status);
    else
        code = 1;

    (void)k_write_full(sp[1], &code, sizeof(code));
    goto out;

kill_child:
    kill(pid, SIGKILL);
    waitpid(pid, &status, 0);

out:
    if (master >= 0)
        close(master);
    if (sp[0] >= 0)
        close(sp[0]);
    if (sp[1] >= 0)
        close(sp[1]);
    free(term);
    free(cmd);
    close(cfd);
}

/* ------------------------------------------------------------- socket ---- */

static int sock_addr(struct sockaddr_un *sa, socklen_t *len) {
    memset(sa, 0, sizeof(*sa));
    sa->sun_family = AF_UNIX;
    sa->sun_path[0] = '\0';
    strncpy(sa->sun_path + 1, KROOT_SOCK_NAME, sizeof(sa->sun_path) - 2);
    *len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + strlen(KROOT_SOCK_NAME));
    return 0;
}

static int daemon_running(void) {
    int fd = kroot_connect();
    if (fd >= 0) {
        close(fd);
        return 1;
    }
    return 0;
}

static void write_pidfile(void) {
    char buf[32];
    int fd = open(KROOT_PID, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd < 0)
        return;
    int n = snprintf(buf, sizeof(buf), "%d\n", (int)getpid());
    (void)k_write_full(fd, buf, (size_t)n);
    close(fd);
}

static void on_term(int sig) {
    (void)sig;
    g_run = 0;
}

static int serve(void) {
    struct sockaddr_un sa;
    socklen_t len;
    int lfd;

    umask(077);
    mkdir(KROOT_RUN_DIR, 0700);
    chmod(KROOT_RUN_DIR, 0700);

    lfd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (lfd < 0)
        return 1;

    sock_addr(&sa, &len);
    unlink(KROOT_PID);

    if (bind(lfd, (struct sockaddr *)&sa, len) != 0) {
        close(lfd);
        return 1;
    }

    if (listen(lfd, 16) != 0) {
        close(lfd);
        return 1;
    }

    signal(SIGCHLD, SIG_IGN);
    signal(SIGPIPE, SIG_IGN);
    signal(SIGTERM, on_term);
    signal(SIGINT, on_term);

    write_pidfile();
    logmsg("krootd iniciado pid=%d", (int)getpid());

    while (g_run) {
        struct ucred cr;
        socklen_t crlen = sizeof(cr);
        int cfd = accept(lfd, NULL, NULL);
        pid_t pid;

        if (cfd < 0) {
            if (errno == EINTR)
                continue;
            break;
        }

        if (getsockopt(cfd, SOL_SOCKET, SO_PEERCRED, &cr, &crlen) != 0) {
            close(cfd);
            continue;
        }

        pid = fork();
        if (pid == 0) {
            close(lfd);
            handle_client(cfd, cr);
            _exit(0);
        }

        close(cfd);
        if (pid < 0)
            logmsg("fork falhou: %s", strerror(errno));
    }

    close(lfd);
    unlink(KROOT_PID);
    logmsg("krootd encerrado");
    return 0;
}

static void daemonize(void) {
    pid_t pid = fork();

    if (pid < 0)
        _exit(1);
    if (pid > 0)
        _exit(0);

    setsid();

    pid = fork();
    if (pid < 0)
        _exit(1);
    if (pid > 0)
        _exit(0);

    if (chdir("/") != 0)
        _exit(1);

    {
        int fd = open("/dev/null", O_RDWR);
        if (fd >= 0) {
            dup2(fd, STDIN_FILENO);
            dup2(fd, STDOUT_FILENO);
            dup2(fd, STDERR_FILENO);
            if (fd > STDERR_FILENO)
                close(fd);
        }
    }
}

static int stop_daemon(void) {
    int fd = open(KROOT_PID, O_RDONLY);
    char buf[32] = {0};
    int pid = 0;

    if (fd < 0)
        return 1;

    if (read(fd, buf, sizeof(buf) - 1) > 0)
        pid = atoi(buf);
    close(fd);

    if (pid <= 0)
        return 1;

    if (kill(pid, SIGTERM) != 0)
        return 1;

    for (int i = 0; i < 50; i++) {
        if (kill(pid, 0) != 0)
            return 0;
        usleep(100000);
    }

    kill(pid, SIGKILL);
    return 0;
}

int main(int argc, char **argv) {
    int daemon_mode = 0;

    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--daemon") || !strcmp(argv[i], "-d"))
            daemon_mode = 1;
        else if (!strcmp(argv[i], "--foreground") || !strcmp(argv[i], "-f"))
            g_foreground = 1;
        else if (!strcmp(argv[i], "--stop"))
            return stop_daemon();
        else if (!strcmp(argv[i], "--status"))
            return daemon_running() ? 0 : 1;
        else if (!strcmp(argv[i], "--help") || !strcmp(argv[i], "-h")) {
            printf("uso: krootd [--daemon|-d] [--foreground|-f] [--stop] [--status]\n");
            return 0;
        }
    }

    if (getuid() != 0) {
        fprintf(stderr, "krootd: precisa de root (uid atual %d)\n", (int)getuid());
        return 1;
    }

    if (daemon_running()) {
        if (!g_foreground)
            return 0;
    }

    if (daemon_mode && !g_foreground)
        daemonize();

    return serve();
}
