/*
 * su - cliente do krootd (Kairos Root)
 *
 * Conecta no daemon; se ele nao estiver no ar, dispara o bootstrap (exploit
 * kroot) e tenta de novo. Nao roda mais o exploit a cada comando.
 */
#define _GNU_SOURCE
#include "common.h"

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pwd.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <unistd.h>

static int g_master = -1;

static void usage(void) {
    printf("uso: su [opcoes] [usuario] [comando...]\n"
           "\n"
           "  -c, --command <cmd>   executa <cmd> e sai\n"
           "  -l, --login           shell de login\n"
           "  -u, --user <uid>      uid alvo (padrao 0)\n"
           "      --daemon          sobe o krootd\n"
           "      --stop            encerra o krootd\n"
           "      --status          mostra o estado do krootd\n"
           "  -h, --help            ajuda\n");
}

static int resolve_uid(const char *s, uid_t *out) {
    char *end = NULL;
    long v;

    errno = 0;
    v = strtol(s, &end, 10);
    if (errno == 0 && end && *end == '\0' && v >= 0) {
        *out = (uid_t)v;
        return 0;
    }

    struct passwd *pw = getpwnam(s);
    if (!pw)
        return -1;

    *out = pw->pw_uid;
    return 0;
}

static void bootstrap(void) {
    fprintf(stderr, "kroot: daemon ausente, subindo...\n");

    if (getuid() == 0)
        (void)system("/data/local/xbin/krootd --daemon");
    else
        (void)system("echo '/data/local/xbin/krootd --daemon' | /data/local/tmp/su >/dev/null 2>&1");

    for (int i = 0; i < 40; i++) {
        int fd = kroot_connect();
        if (fd >= 0) {
            close(fd);
            return;
        }
        usleep(250000);
    }
}

static void on_winch(int sig) {
    struct winsize ws;
    (void)sig;
    if (g_master < 0)
        return;
    if (ioctl(STDIN_FILENO, TIOCGWINSZ, &ws) == 0)
        ioctl(g_master, TIOCSWINSZ, &ws);
}

int main(int argc, char **argv) {
    const char *cmd = NULL;
    char cmd_buf[4096];
    uid_t uid = 0;
    gid_t gid = 0;
    int login = 0;
    int has_user = 0;
    int fd;
    char hdr[256];
    char reply[256];
    const char *term;
    unsigned cols = 0, rows = 0;
    int fds[2];
    char byte;
    struct termios saved;
    int raw = 0;
    int exit_code = 0;
    int in_eof = 0;
    int master;
    int sfd;

    cmd_buf[0] = '\0';

    for (int i = 1; i < argc; i++) {
        const char *a = argv[i];

        if (!strcmp(a, "-c") || !strcmp(a, "--command")) {
            if (i + 1 >= argc) {
                fprintf(stderr, "su: -c precisa de argumento\n");
                return 2;
            }
            cmd = argv[++i];
        } else if (!strcmp(a, "-l") || !strcmp(a, "--login")) {
            login = 1;
        } else if (!strcmp(a, "-u") || !strcmp(a, "--user")) {
            if (i + 1 >= argc) {
                fprintf(stderr, "su: -u precisa de argumento\n");
                return 2;
            }
            if (resolve_uid(argv[++i], &uid) != 0) {
                fprintf(stderr, "su: usuario desconhecido\n");
                return 2;
            }
            has_user = 1;
        } else if (!strcmp(a, "-s") || !strcmp(a, "--shell")) {
            i++;
        } else if (!strcmp(a, "--daemon")) {
            execl("/data/local/xbin/krootd", "krootd", "--daemon", (char *)NULL);
            fprintf(stderr, "su: nao achei /data/local/xbin/krootd\n");
            return 1;
        } else if (!strcmp(a, "--stop")) {
            execl("/data/local/xbin/krootd", "krootd", "--stop", (char *)NULL);
            return 1;
        } else if (!strcmp(a, "--status")) {
            fd = kroot_connect();
            if (fd < 0) {
                printf("krootd: offline\n");
                return 1;
            }
            close(fd);
            printf("krootd: online\n");
            return 0;
        } else if (!strcmp(a, "-h") || !strcmp(a, "--help")) {
            usage();
            return 0;
        } else if (a[0] == '-') {
            fprintf(stderr, "su: opcao desconhecida: %s\n", a);
            return 2;
        } else if (!has_user && !cmd) {
            if (resolve_uid(a, &uid) != 0) {
                fprintf(stderr, "su: usuario desconhecido: %s\n", a);
                return 2;
            }
            has_user = 1;
        } else {
            size_t cur = strlen(cmd_buf);
            size_t need = strlen(a) + (cur ? 2 : 1);
            if (cur + need >= sizeof(cmd_buf)) {
                fprintf(stderr, "su: comando longo demais\n");
                return 2;
            }
            if (cur)
                strcat(cmd_buf, " ");
            strcat(cmd_buf, a);
        }
    }

    if (cmd_buf[0])
        cmd = cmd_buf;

    gid = (gid_t)uid;

    fd = kroot_connect();
    if (fd < 0) {
        bootstrap();
        fd = kroot_connect();
    }
    if (fd < 0) {
        fprintf(stderr, "su: krootd indisponivel\n");
        return 1;
    }

    term = getenv("TERM");
    if (!term || !term[0])
        term = "xterm-256color";

    if (isatty(STDIN_FILENO)) {
        struct winsize ws;
        if (ioctl(STDIN_FILENO, TIOCGWINSZ, &ws) == 0) {
            cols = ws.ws_col;
            rows = ws.ws_row;
        }
    }

    {
        unsigned flags = KF_PTY | (login ? KF_LOGIN : 0);
        size_t clen = cmd ? strlen(cmd) : 0;
        int n = snprintf(hdr, sizeof(hdr), "EXEC %u %u %u %u %u %u %u\n",
                         flags, (unsigned)uid, (unsigned)gid, cols, rows,
                         (unsigned)strlen(term), (unsigned)clen);
        if (n <= 0 || (size_t)n >= sizeof(hdr)) {
            close(fd);
            return 1;
        }
        if (k_write_full(fd, hdr, (size_t)n) != 0 ||
            k_write_full(fd, term, strlen(term)) != 0 ||
            (clen && k_write_full(fd, cmd, clen) != 0)) {
            fprintf(stderr, "su: falha ao enviar pedido\n");
            close(fd);
            return 1;
        }
    }

    if (k_read_line(fd, reply, sizeof(reply)) <= 0) {
        fprintf(stderr, "su: daemon nao respondeu\n");
        close(fd);
        return 1;
    }

    if (strncmp(reply, "OK", 2) != 0) {
        fprintf(stderr, "su: %s\n", reply[0] ? reply : "negado");
        close(fd);
        return 126;
    }

    if (k_recv_fds(fd, &byte, 1, fds, 2) != 2) {
        fprintf(stderr, "su: nao recebi os descritores\n");
        close(fd);
        return 1;
    }

    close(fd);
    master = fds[0];
    sfd = fds[1];
    g_master = master;

    signal(SIGPIPE, SIG_IGN);
    signal(SIGWINCH, on_winch);

    if (isatty(STDIN_FILENO)) {
        if (tcgetattr(STDIN_FILENO, &saved) == 0) {
            struct termios r = saved;
            cfmakeraw(&r);
            if (tcsetattr(STDIN_FILENO, TCSANOW, &r) == 0)
                raw = 1;
        }
    }

    for (;;) {
        struct pollfd p[3];

        p[0].fd = in_eof ? -1 : STDIN_FILENO;
        p[0].events = POLLIN;
        p[0].revents = 0;
        p[1].fd = master;
        p[1].events = POLLIN;
        p[1].revents = 0;
        p[2].fd = sfd;
        p[2].events = POLLIN;
        p[2].revents = 0;

        if (poll(p, 3, -1) < 0) {
            if (errno == EINTR)
                continue;
            break;
        }

        if (p[1].revents & (POLLIN | POLLHUP | POLLERR)) {
            char buf[8192];
            ssize_t r = read(master, buf, sizeof(buf));
            if (!(r > 0))
                break;
            if (k_write_full(STDOUT_FILENO, buf, (size_t)r) != 0)
                break;
        }

        if (p[2].revents & (POLLIN | POLLHUP | POLLERR)) {
            int code = 0;
            if (read(sfd, &code, sizeof(code)) == (ssize_t)sizeof(code))
                exit_code = code;
            break;
        }

        if (p[0].revents & (POLLIN | POLLHUP)) {
            char buf[4096];
            ssize_t r = read(STDIN_FILENO, buf, sizeof(buf));
            if (r > 0) {
                if (k_write_full(master, buf, (size_t)r) != 0)
                    break;
            } else {
                /* EOF no stdin: manda ^D para o pty */
                char eot = 4;
                (void)k_write_full(master, &eot, 1);
                in_eof = 1;
            }
        }
    }

    if (raw)
        tcsetattr(STDIN_FILENO, TCSANOW, &saved);

    close(master);
    close(sfd);
    return exit_code;
}
