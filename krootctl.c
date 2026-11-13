/*
 * krootctl - gerencia a policy do krootd (roda como root)
 */
#define _GNU_SOURCE
#include "common.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define MAX_LINES 512
#define MAX_LINE  512

static int read_lines(char lines[MAX_LINES][MAX_LINE], int *count) {
    FILE *f;
    char buf[MAX_LINE];

    *count = 0;
    f = fopen(KROOT_POLICY, "r");
    if (!f)
        return 0;

    while (fgets(buf, sizeof(buf), f) && *count < MAX_LINES) {
        snprintf(lines[*count], MAX_LINE, "%s", buf);
        (*count)++;
    }

    fclose(f);
    return 0;
}

static int write_lines(char lines[MAX_LINES][MAX_LINE], int count) {
    char tmp[] = KROOT_POLICY ".tmp";
    FILE *f = fopen(tmp, "w");

    if (!f)
        return -1;

    for (int i = 0; i < count; i++)
        fputs(lines[i], f);

    fflush(f);
    fsync(fileno(f));
    fclose(f);

    chmod(tmp, 0600);
    if (rename(tmp, KROOT_POLICY) != 0) {
        unlink(tmp);
        return -1;
    }

    return 0;
}

static int policy_uid(const char *line, unsigned *uid) {
    if (line[0] == '#' || line[0] == '\n')
        return -1;
    return sscanf(line, "%u", uid) == 1 ? 0 : -1;
}

static int cmd_list(void) {
    char lines[MAX_LINES][MAX_LINE];
    int count;

    read_lines(lines, &count);

    printf("%-8s %-6s %-16s %s\n", "UID", "ACAO", "PACOTE", "EXPIRA");

    for (int i = 0; i < count; i++) {
        unsigned uid;
        char act[32] = {0}, pkg[64] = {0};
        long exp = 0;

        if (policy_uid(lines[i], &uid) != 0)
            continue;

        sscanf(lines[i], "%u\t%31s\t%63s\t%ld", &uid, act, pkg, &exp);

        if (exp > 0) {
            char when[32];
            time_t t = (time_t)exp;
            struct tm tm;
            localtime_r(&t, &tm);
            strftime(when, sizeof(when), "%Y-%m-%d %H:%M", &tm);
            printf("%-8u %-6s %-16s %s\n", uid, act, pkg, when);
        } else {
            printf("%-8u %-6s %-16s %s\n", uid, act, pkg, "nunca");
        }
    }

    return 0;
}

static int cmd_set(unsigned uid, int allow, long minutes) {
    char lines[MAX_LINES][MAX_LINE];
    char new_line[MAX_LINE];
    int count;
    long exp = 0;

    if (minutes > 0)
        exp = (long)time(NULL) + minutes * 60;

    read_lines(lines, &count);

    snprintf(new_line, sizeof(new_line), "%u\t%s\tcli\t%ld\n",
             uid, allow ? "allow" : "deny", exp);

    for (int i = 0; i < count; i++) {
        unsigned cur;
        if (policy_uid(lines[i], &cur) == 0 && cur == uid) {
            snprintf(lines[i], MAX_LINE, "%s", new_line);
            return write_lines(lines, count);
        }
    }

    if (count >= MAX_LINES)
        return -1;

    snprintf(lines[count], MAX_LINE, "%s", new_line);
    return write_lines(lines, count + 1);
}

static int cmd_status(void) {
    int fd = kroot_connect();
    char buf[32] = {0};
    int pid = 0;

    printf("socket : %s\n", fd >= 0 ? "online" : "offline");
    if (fd >= 0)
        close(fd);

    FILE *f = fopen(KROOT_PID, "r");
    if (f) {
        if (fgets(buf, sizeof(buf), f))
            pid = atoi(buf);
        fclose(f);
    }

    printf("pid    : %d\n", pid);
    printf("policy : %s\n", KROOT_POLICY);
    printf("log    : %s\n", KROOT_LOG);
    printf("uid    : %d\n", (int)getuid());

    return fd >= 0 ? 0 : 1;
}

static int cmd_log(int n) {
    FILE *f = fopen(KROOT_LOG, "r");
    char lines[128][512];
    int count = 0;

    if (!f) {
        printf("(sem log ainda)\n");
        return 1;
    }

    while (fgets(lines[count % 128], 512, f)) {
        count++;
        if (count >= 1000000)
            break;
    }
    fclose(f);

    int start = count > n ? count - n : 0;
    for (int i = start; i < count; i++)
        fputs(lines[i % 128], stdout);

    return 0;
}

static void usage(void) {
    printf("uso: krootctl <comando>\n"
           "\n"
           "  status                 estado do daemon\n"
           "  list                   lista a policy\n"
           "  allow <uid> [min]      permite (min = expira em N minutos)\n"
           "  deny <uid>             nega\n"
           "  log [n]                ultimas n linhas do log (padrao 20)\n"
           "  start                  sobe o krootd (precisa de root)\n"
           "  stop                   encerra o krootd\n");
}

int main(int argc, char **argv) {
    if (argc < 2) {
        usage();
        return 2;
    }

    if (!strcmp(argv[1], "status"))
        return cmd_status();

    if (!strcmp(argv[1], "list"))
        return cmd_list();

    if (!strcmp(argv[1], "log"))
        return cmd_log(argc > 2 ? atoi(argv[2]) : 20);

    if (!strcmp(argv[1], "start"))
        return execl("/data/local/xbin/krootd", "krootd", "--daemon", (char *)NULL);

    if (!strcmp(argv[1], "stop"))
        return execl("/data/local/xbin/krootd", "krootd", "--stop", (char *)NULL);

    if (!strcmp(argv[1], "allow") || !strcmp(argv[1], "deny")) {
        unsigned uid;
        long minutes = 0;
        int allow = !strcmp(argv[1], "allow");

        if (argc < 3) {
            fprintf(stderr, "krootctl: falta o uid\n");
            return 2;
        }

        uid = (unsigned)strtoul(argv[2], NULL, 10);
        if (argc > 3)
            minutes = strtol(argv[3], NULL, 10);

        if (cmd_set(uid, allow, minutes) != 0) {
            fprintf(stderr, "krootctl: falha ao gravar a policy\n");
            return 1;
        }

        printf("uid %u: %s%s\n", uid, allow ? "allow" : "deny",
               minutes > 0 ? " (temporario)" : "");
        return 0;
    }

    if (!strcmp(argv[1], "-h") || !strcmp(argv[1], "--help")) {
        usage();
        return 0;
    }

    fprintf(stderr, "krootctl: comando desconhecido: %s\n", argv[1]);
    return 2;
}
