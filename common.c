#define _GNU_SOURCE
#include "common.h"

#include <errno.h>
#include <stddef.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

int kroot_connect(void) {
    struct sockaddr_un sa;
    socklen_t len;
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);

    if (fd < 0)
        return -1;

    memset(&sa, 0, sizeof(sa));
    sa.sun_family = AF_UNIX;
    sa.sun_path[0] = '\0';
    strncpy(sa.sun_path + 1, KROOT_SOCK_NAME, sizeof(sa.sun_path) - 2);
    len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + strlen(KROOT_SOCK_NAME));

    if (connect(fd, (struct sockaddr *)&sa, len) != 0) {
        close(fd);
        return -1;
    }

    return fd;
}

ssize_t k_send_fds(int sock, const void *buf, size_t len, const int *fds, int nfds) {
    struct msghdr msg;
    struct iovec io;
    union {
        struct cmsghdr align;
        char buf[CMSG_SPACE(sizeof(int) * 8)];
    } cbuf;

    memset(&msg, 0, sizeof(msg));
    memset(&cbuf, 0, sizeof(cbuf));

    io.iov_base = (void *)buf;
    io.iov_len = len;
    msg.msg_iov = &io;
    msg.msg_iovlen = 1;

    if (nfds > 0) {
        msg.msg_control = cbuf.buf;
        msg.msg_controllen = CMSG_SPACE(sizeof(int) * nfds);
        struct cmsghdr *c = CMSG_FIRSTHDR(&msg);
        c->cmsg_level = SOL_SOCKET;
        c->cmsg_type = SCM_RIGHTS;
        c->cmsg_len = CMSG_LEN(sizeof(int) * nfds);
        memcpy(CMSG_DATA(c), fds, sizeof(int) * nfds);
    }

    return sendmsg(sock, &msg, MSG_NOSIGNAL);
}

int k_recv_fds(int sock, void *buf, size_t len, int *fds, int maxfds) {
    struct msghdr msg;
    struct iovec io;
    union {
        struct cmsghdr align;
        char buf[CMSG_SPACE(sizeof(int) * 8)];
    } cbuf;
    int got = 0;

    memset(&msg, 0, sizeof(msg));
    memset(&cbuf, 0, sizeof(cbuf));

    io.iov_base = buf;
    io.iov_len = len;
    msg.msg_iov = &io;
    msg.msg_iovlen = 1;
    msg.msg_control = cbuf.buf;
    msg.msg_controllen = sizeof(cbuf.buf);

    if (recvmsg(sock, &msg, 0) < 0)
        return -1;

    for (struct cmsghdr *c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
        if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS && c->cmsg_len > CMSG_LEN(0)) {
            int cnt = (int)((c->cmsg_len - CMSG_LEN(0)) / sizeof(int));
            for (int i = 0; i < cnt && got < maxfds; i++)
                fds[got++] = ((int *)CMSG_DATA(c))[i];
        }
    }

    return got;
}

int k_read_line(int fd, char *buf, size_t cap) {
    size_t n = 0;

    if (cap == 0)
        return -1;

    for (;;) {
        char c;
        ssize_t r = read(fd, &c, 1);
        if (r < 0) {
            if (errno == EINTR)
                continue;
            return -1;
        }
        if (r == 0)
            break;
        if (c == '\n')
            break;
        if (n + 1 < cap)
            buf[n++] = c;
    }

    buf[n] = '\0';
    return (int)n;
}

int k_read_full(int fd, void *buf, size_t len) {
    size_t off = 0;

    while (off < len) {
        ssize_t r = read(fd, (char *)buf + off, len - off);
        if (r < 0) {
            if (errno == EINTR)
                continue;
            return -1;
        }
        if (r == 0)
            return -1;
        off += (size_t)r;
    }

    return 0;
}

int k_write_full(int fd, const void *buf, size_t len) {
    size_t off = 0;

    while (off < len) {
        ssize_t w = write(fd, (const char *)buf + off, len - off);
        if (w < 0) {
            if (errno == EINTR)
                continue;
            return -1;
        }
        off += (size_t)w;
    }

    return 0;
}
