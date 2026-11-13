/* common.h - utilitarios compartilhados do kroot */
#ifndef KROOT_COMMON_H
#define KROOT_COMMON_H

#include <stddef.h>
#include <sys/types.h>

#define KROOT_RUN_DIR   "/data/local/kroot"
#define KROOT_POLICY    KROOT_RUN_DIR "/policy.tsv"
#define KROOT_LOG       KROOT_RUN_DIR "/krootd.log"
#define KROOT_PID       KROOT_RUN_DIR "/krootd.pid"
#define KROOT_SOCK_NAME "krootd"

#define KROOT_PROTO 1

/* flags do pedido EXEC */
#define KF_PTY   1u  /* aloca pseudo-terminal */
#define KF_LOGIN 2u  /* shell de login (argv[0] = "-sh") */

#ifndef MSG_NOSIGNAL
#define MSG_NOSIGNAL 0
#endif

/* conecta no socket abstrato do krootd. retorna fd ou -1. */
int kroot_connect(void);

ssize_t k_send_fds(int sock, const void *buf, size_t len, const int *fds, int nfds);
int     k_recv_fds(int sock, void *buf, size_t len, int *fds, int maxfds);

/* le uma linha terminada em \n (sem o \n). retorna tamanho ou -1. */
int k_read_line(int fd, char *buf, size_t cap);

/* le exatamente len bytes. retorna 0 ok, -1 erro. */
int k_read_full(int fd, void *buf, size_t len);

/* escreve tudo. retorna 0 ok, -1 erro. */
int k_write_full(int fd, const void *buf, size_t len);

#endif
