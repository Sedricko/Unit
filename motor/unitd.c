// unitd: o motor do Unit. Roda como root (pelo su) e conversa por linhas: um comando por linha; a resposta são linhas de
// dados e no fim uma linha "ok ..." ou "err ...". Lê e escreve a memória de outro processo (process_vm_readv/writev).
//
// Comandos:
//   ps                         processos com nome de pacote:   "pid nome"
//   attach PID                 escolhe o jogo
//   search VALOR [TIPOS]       busca nova; TIPOS = i32,f32,i64,f64 (padrão: todos = "auto")   -> ok N
//   refine VALOR               fica só quem tem VALOR agora                                -> ok N
//   list N                     os N primeiros resultados: "endereço tipo valor"              -> ok TOTAL
//   read ENDEREÇO TIPO         -> ok VALOR
//   write ENDEREÇO TIPO VALOR  -> ok
//   freeze ENDEREÇO TIPO VALOR / unfreeze ENDEREÇO|all / frozen
//   sleep MS                   espera (testes e scripts: dá tempo ao congelador)
//   quit
#define _GNU_SOURCE
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <math.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/uio.h>
#include <unistd.h>

enum { T_I32, T_F32, T_I64, T_F64, NT };
static const char *TNAME[NT] = {"i32", "f32", "i64", "f64"};
static const int TSIZE[NT] = {4, 4, 8, 8};

typedef struct { uint64_t addr; uint8_t type; } hit_t;
static pid_t target;
static hit_t *res; static size_t nres, cap;

static int tipo(const char *s) { for (int t = 0; t < NT; t++) if (!strcmp(s, TNAME[t])) return t; return -1; }

static int ler(uint64_t a, void *buf, size_t n) {
    struct iovec l = {buf, n}, r = {(void *)(uintptr_t)a, n};
    return process_vm_readv(target, &l, 1, &r, 1, 0) == (ssize_t)n ? 0 : -1;
}
static int escrever(uint64_t a, const void *buf, size_t n) {
    struct iovec l = {(void *)buf, n}, r = {(void *)(uintptr_t)a, n};
    if (process_vm_writev(target, &l, 1, &r, 1, 0) == (ssize_t)n) return 0;
    // página só de leitura para o processo (ex.: código): /proc/PID/mem escreve mesmo assim (root)
    char p[64]; snprintf(p, sizeof p, "/proc/%d/mem", target);
    int fd = open(p, O_RDWR); if (fd < 0) return -1;
    ssize_t w = pwrite(fd, buf, n, (off_t)a); close(fd);
    return w == (ssize_t)n ? 0 : -1;
}

// valor da memória (bytes) como número
static double numero(int t, const unsigned char *b) {
    switch (t) {
    case T_I32: { int32_t v; memcpy(&v, b, 4); return v; }
    case T_F32: { float v; memcpy(&v, b, 4); return v; }
    case T_I64: { int64_t v; memcpy(&v, b, 8); return (double)v; }
    default: { double v; memcpy(&v, b, 8); return v; }
    }
}
static void texto(int t, const unsigned char *b, char *out, size_t n) {
    if (t == T_I32) { int32_t v; memcpy(&v, b, 4); snprintf(out, n, "%" PRId32, v); }
    else if (t == T_I64) { int64_t v; memcpy(&v, b, 8); snprintf(out, n, "%" PRId64, v); }
    else snprintf(out, n, "%.9g", numero(t, b));
}
// o número da tela bate com o da memória? inteiro: igual; com vírgula: a tela arredonda (87,5 / 87.49999)
static int bate(int t, double mem, double want) {
    if (t == T_I32 || t == T_I64) return mem == want;
    if (isnan(mem) || isinf(mem)) return 0;
    return fabs(mem - want) <= fmax(1e-4, fabs(want) * 1e-6) || (want == floor(want) && mem >= want - 0.5 && mem < want + 0.5 && fabs(mem) > 1e-3);
}
static int codificar(int t, const char *s, unsigned char *b) {
    char *e; double d = strtod(s, &e);
    if (e == s || *e) return -1;
    if (t == T_I32) { if (d != floor(d) || d < INT32_MIN || d > UINT32_MAX) return -1; int32_t v = (int32_t)(int64_t)d; memcpy(b, &v, 4); }
    else if (t == T_I64) { if (d != floor(d)) return -1; int64_t v = (int64_t)d; memcpy(b, &v, 8); }
    else if (t == T_F32) { float v = (float)d; memcpy(b, &v, 4); }
    else memcpy(b, &d, 8);
    return 0;
}

static void guardar(uint64_t a, int t) {
    if (nres == cap) { cap = cap ? cap * 2 : 4096; res = realloc(res, cap * sizeof *res); if (!res) { puts("err sem memória"); exit(1); } }
    res[nres++] = (hit_t){a, (uint8_t)t};
}

// regiões onde o jogo guarda dados: as que ele pode ler e escrever (pula as de vídeo e de sistema)
static int regioes(uint64_t **ini, uint64_t **fim) {
    char p[64]; snprintf(p, sizeof p, "/proc/%d/maps", target);
    FILE *f = fopen(p, "r"); if (!f) return -1;
    size_t n = 0, c = 0; char line[512];
    *ini = NULL; *fim = NULL;
    while (fgets(line, sizeof line, f)) {
        uint64_t a, b; char perms[8] = "", name[400] = "";
        if (sscanf(line, "%" SCNx64 "-%" SCNx64 " %7s %*s %*s %*s %399[^\n]", &a, &b, perms, name) < 3) continue;
        if (perms[0] != 'r' || perms[1] != 'w') continue;
        if (strstr(name, "/dev/") || strstr(name, "[vvar") || strstr(name, "kgsl") || strstr(name, "mali") || strstr(name, ".ttf")) continue;
        if (n == c) { c = c ? c * 2 : 256; *ini = realloc(*ini, c * 8); *fim = realloc(*fim, c * 8); }
        (*ini)[n] = a; (*fim)[n] = b; n++;
    }
    fclose(f);
    return (int)n;
}

static void cmd_search(const char *valor, const char *tipos) {
    char *e; double want = strtod(valor, &e);
    if (e == valor || *e) { printf("err valor inválido: %s\n", valor); return; }
    int usar[NT] = {0};
    if (!tipos || !*tipos || !strcmp(tipos, "auto")) for (int t = 0; t < NT; t++) usar[t] = 1;
    else { char tmp[64]; snprintf(tmp, sizeof tmp, "%s", tipos); for (char *s = strtok(tmp, ","); s; s = strtok(NULL, ",")) { int t = tipo(s); if (t >= 0) usar[t] = 1; } }
    // inteiro só se o número for inteiro (0,5 não está guardado em Dword)
    if (want != floor(want)) usar[T_I32] = usar[T_I64] = 0;
    uint64_t *ini, *fim; int nr = regioes(&ini, &fim);
    if (nr < 0) { puts("err não li o mapa de memória do jogo"); return; }
    nres = 0;
    enum { BLOCO = 1 << 20 };
    unsigned char *buf = malloc(BLOCO + 8);
    for (int r = 0; r < nr; r++)
        for (uint64_t a = ini[r]; a < fim[r]; a += BLOCO) {
            size_t n = fim[r] - a < BLOCO ? fim[r] - a : BLOCO;
            if (ler(a, buf, n)) continue;  // página sem mapa no meio: pula o bloco
            for (int t = 0; t < NT; t++) {
                if (!usar[t]) continue;
                int sz = TSIZE[t];
                for (size_t o = 0; o + sz <= n; o += sz)
                    if (bate(t, numero(t, buf + o), want)) guardar(a + o, t);
            }
        }
    free(buf); free(ini); free(fim);
    printf("ok %zu\n", nres);
}

static void cmd_refine(const char *valor) {
    char *e; double want = strtod(valor, &e);
    if (e == valor || *e) { printf("err valor inválido: %s\n", valor); return; }
    size_t k = 0; unsigned char b[8];
    for (size_t i = 0; i < nres; i++)
        if (!ler(res[i].addr, b, TSIZE[res[i].type]) && bate(res[i].type, numero(res[i].type, b), want)) res[k++] = res[i];
    nres = k;
    printf("ok %zu\n", nres);
}

static void cmd_list(size_t n) {
    unsigned char b[8]; char v[64];
    for (size_t i = 0; i < nres && i < n; i++) {
        int t = res[i].type;
        if (ler(res[i].addr, b, TSIZE[t])) strcpy(v, "?"); else texto(t, b, v, sizeof v);
        printf("%" PRIx64 " %s %s\n", res[i].addr, TNAME[t], v);
    }
    printf("ok %zu\n", nres);
}

// ---- congelar: uma thread reescreve a cada 100 ms ----
#define FMAX 256
static struct { uint64_t addr; int type; unsigned char b[8]; int on; } fz[FMAX];
static pthread_mutex_t fzm = PTHREAD_MUTEX_INITIALIZER;
static void *congelador(void *x) {
    (void)x;
    for (;;) {
        pthread_mutex_lock(&fzm);
        for (int i = 0; i < FMAX; i++) if (fz[i].on && target) escrever(fz[i].addr, fz[i].b, TSIZE[fz[i].type]);
        pthread_mutex_unlock(&fzm);
        usleep(100000);
    }
    return NULL;
}

static void cmd_ps(void) {
    DIR *d = opendir("/proc"); struct dirent *de;
    if (!d) { puts("err sem /proc"); return; }
    int n = 0;
    while ((de = readdir(d))) {
        if (!isdigit((unsigned char)de->d_name[0])) continue;
        char p[300], nome[256] = ""; snprintf(p, sizeof p, "/proc/%s/cmdline", de->d_name);
        FILE *f = fopen(p, "r"); if (!f) continue;
        size_t k = fread(nome, 1, sizeof nome - 1, f); fclose(f); nome[k] = 0;
        if (!strchr(nome, '.') || strchr(nome, '/')) continue;  // apps: nome de pacote (com ponto, sem caminho)
        printf("%s %s\n", de->d_name, nome); n++;
    }
    closedir(d);
    printf("ok %d\n", n);
}

int main(void) {
    setvbuf(stdout, NULL, _IOLBF, 0);
    pthread_t th; pthread_create(&th, NULL, congelador, NULL);
    printf("ok unitd 0.1 uid=%d\n", getuid());
    char line[512], c[32], a1[128], a2[128], a3[128];
    while (fgets(line, sizeof line, stdin)) {
        c[0] = a1[0] = a2[0] = a3[0] = 0;
        sscanf(line, "%31s %127s %127s %127s", c, a1, a2, a3);
        if (!c[0]) continue;
        if (!strcmp(c, "quit")) break;
        if (!strcmp(c, "ps")) { cmd_ps(); continue; }
        if (!strcmp(c, "sleep")) { usleep((useconds_t)(atoi(a1) % 10001) * 1000); puts("ok"); continue; }
        if (!strcmp(c, "attach")) {
            pid_t p = (pid_t)atoi(a1); char path[64]; snprintf(path, sizeof path, "/proc/%d", p);
            if (p <= 0 || access(path, F_OK)) { printf("err processo %s não existe\n", a1); continue; }
            pthread_mutex_lock(&fzm); memset(fz, 0, sizeof fz); target = p; nres = 0; pthread_mutex_unlock(&fzm);
            printf("ok %d\n", p); continue;
        }
        if (!target) { puts("err escolha o jogo primeiro (attach)"); continue; }
        char path[64]; snprintf(path, sizeof path, "/proc/%d", target);
        if (access(path, F_OK)) { puts("err o jogo fechou"); continue; }
        if (!strcmp(c, "search")) cmd_search(a1, a2);
        else if (!strcmp(c, "refine")) cmd_refine(a1);
        else if (!strcmp(c, "list")) cmd_list(strtoul(a1, NULL, 10));
        else if (!strcmp(c, "read") || !strcmp(c, "write") || !strcmp(c, "freeze")) {
            int t = tipo(a2); uint64_t a = strtoull(a1, NULL, 16); unsigned char b[8]; char v[64];
            if (t < 0) { printf("err tipo inválido: %s\n", a2); continue; }
            if (!strcmp(c, "read")) { if (ler(a, b, TSIZE[t])) printf("err não li %s\n", a1); else { texto(t, b, v, sizeof v); printf("ok %s\n", v); } continue; }
            if (codificar(t, a3, b)) { printf("err valor inválido para %s: %s\n", TNAME[t], a3); continue; }
            if (escrever(a, b, TSIZE[t])) { printf("err não escrevi em %s: %s\n", a1, strerror(errno)); continue; }
            if (!strcmp(c, "freeze")) {
                pthread_mutex_lock(&fzm);
                int i, livre = -1;
                for (i = 0; i < FMAX; i++) { if (fz[i].on && fz[i].addr == a) break; if (!fz[i].on && livre < 0) livre = i; }
                if (i == FMAX) i = livre;
                if (i >= 0) { fz[i].addr = a; fz[i].type = t; memcpy(fz[i].b, b, 8); fz[i].on = 1; }
                pthread_mutex_unlock(&fzm);
                if (i < 0) { puts("err congelados demais"); continue; }
            }
            puts("ok");
        }
        else if (!strcmp(c, "unfreeze")) {
            uint64_t a = strtoull(a1, NULL, 16); int n = 0;
            pthread_mutex_lock(&fzm);
            for (int i = 0; i < FMAX; i++) if (fz[i].on && (!strcmp(a1, "all") || fz[i].addr == a)) { fz[i].on = 0; n++; }
            pthread_mutex_unlock(&fzm);
            printf("ok %d\n", n);
        }
        else if (!strcmp(c, "frozen")) {
            int n = 0; pthread_mutex_lock(&fzm);
            for (int i = 0; i < FMAX; i++) if (fz[i].on) { printf("%" PRIx64 " %s\n", fz[i].addr, TNAME[fz[i].type]); n++; }
            pthread_mutex_unlock(&fzm); printf("ok %d\n", n);
        }
        else printf("err comando desconhecido: %s\n", c);
    }
    return 0;
}
