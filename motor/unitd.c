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
//   pause / resume             para e continua o jogo (SIGSTOP/SIGCONT)
//   snap ARQUIVO               foto da memória (para a busca por mudança)                  -> ok BYTES
//   diff MODO                  mudou|igual|foto|aumentou|diminuiu (1ª vez compara com a foto) -> ok N
//   watch ENDEREÇO [SEG]       quem escreve aqui (vigia do processador; espera até SEG s)    -> pc/lib/ins/base, ok
//   patch ENDEREÇO HEX         troca bytes (também no código) e devolve os antigos            -> ok ANTIGOS
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
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ptrace.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <stddef.h>
#include <time.h>
#include <unistd.h>

enum { T_I32, T_F32, T_I64, T_F64, NT };
static const char *TNAME[NT] = {"i32", "f32", "i64", "f64"};
static const int TSIZE[NT] = {4, 4, 8, 8};

typedef struct { uint64_t addr; uint8_t type; } hit_t;
static pid_t target;
static hit_t *res; static size_t nres, cap;
static uint32_t *vb, *vp; static int difat;  // busca por mudança: os 4 bytes da foto e da última olhada de cada resultado

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
// valor "N" ou faixa "A~B" (o ~ do GameGuardian). Na faixa os tipos com vírgula pensam como a tela: "100~100" pega
// tudo que aparece como 100 (de 99,5 até antes de 100,5).
typedef struct { double v, lo, hi; int faixa; } alvo_t;
static int alvo(const char *s, alvo_t *a) {
    char *e; a->faixa = 0;
    const char *t = strchr(s, '~');
    if (t) {
        a->lo = strtod(s, &e); if (e != t) return -1;
        a->hi = strtod(t + 1, &e); if (e == t + 1 || *e) return -1;
        if (a->lo > a->hi) { double x = a->lo; a->lo = a->hi; a->hi = x; }
        a->faixa = 1; a->v = a->lo; return 0;
    }
    a->v = strtod(s, &e); return (e == s || *e) ? -1 : 0;
}
static int confere(int t, double mem, const alvo_t *a) {
    if (!a->faixa) return bate(t, mem, a->v);
    if (t == T_I32 || t == T_I64) return mem >= a->lo && mem <= a->hi;
    if (isnan(mem) || isinf(mem)) return 0;
    return mem >= a->lo - 0.5 && mem < a->hi + 0.5;
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

// busca em grupo (como o GameGuardian): "100;200" ou "100;200::64" = os números perto um do outro (padrão 512 bytes).
// Cada número em qualquer tipo (Auto). Guarda todos os membros de cada grupo. refinar=1: só fica quem já estava nos
// resultados (o grupo novo, ex. "90;200", depois de gastar).
// shortcut: o grupo precisa caber no mesmo bloco de 1 MB lido; um grupo bem na borda de dois blocos escapa (raro)
static int cmpu64(const void *a, const void *b) { uint64_t x = *(const uint64_t *)a, y = *(const uint64_t *)b; return x < y ? -1 : x > y; }
static void cmd_grupo(const char *texto, int refinar) {
    difat = 0;
    char tmp[1100]; snprintf(tmp, sizeof tmp, "%s", texto);
    long dist = 512; char *dd = strstr(tmp, "::");
    if (dd) { *dd = 0; if (dd[2]) dist = strtol(dd + 2, NULL, 10); if (dist < 4 || dist > 4096) { puts("err distância de 4 a 4096"); return; } }
    alvo_t it[8]; int ni = 0; char *sv;
    for (char *x = strtok_r(tmp, ";", &sv); x; x = strtok_r(NULL, ";", &sv)) {
        if (ni == 8 || alvo(x, &it[ni])) { printf("err grupo inválido: %s\n", texto); return; }
        ni++;
    }
    if (ni < 2) { puts("err o grupo precisa de 2 números ou mais"); return; }
    // os resultados de antes (para refinar), em ordem para a busca binária
    uint64_t *antes = NULL; size_t na = 0;
    if (refinar) { na = nres; antes = malloc((na ? na : 1) * 8); for (size_t i = 0; i < na; i++) antes[i] = res[i].addr; qsort(antes, na, 8, cmpu64); }
    #define JA(a) (!refinar || bsearch(&(uint64_t){a}, antes, na, 8, cmpu64))
    uint64_t *ini, *fim; int nr = regioes(&ini, &fim);
    if (nr < 0) { puts("err não li o mapa de memória do jogo"); free(antes); return; }
    nres = 0; size_t grupos = 0;
    enum { BLOCO = 1 << 20 };
    unsigned char *buf = malloc(BLOCO + 8);
    for (int r = 0; r < nr; r++)
        for (uint64_t a = ini[r]; a < fim[r]; a += BLOCO) {
            size_t n = fim[r] - a < BLOCO ? fim[r] - a : BLOCO;
            if (ler(a, buf, n)) continue;
            for (size_t o = 0; o + 4 <= n; o += 4)
                for (int t0 = 0; t0 < NT; t0++) {
                    if (o + TSIZE[t0] > n || !confere(t0, numero(t0, buf + o), &it[0]) || !JA(a + o)) continue;
                    if ((t0 == T_I32 || t0 == T_I64) && !it[0].faixa && it[0].v != floor(it[0].v)) continue;
                    uint64_t mem[8]; int tp[8]; mem[0] = a + o; tp[0] = t0; int ok = 1;
                    for (int j = 1; j < ni && ok; j++) {
                        ok = 0;
                        size_t de = o > (size_t)dist ? o - dist : 0, ate = o + dist < n ? o + dist : n;
                        for (size_t q = de; q + 4 <= ate && !ok; q += 4) {
                            if (q == o) continue;
                            for (int t = 0; t < NT && !ok; t++)
                                if (q + TSIZE[t] <= n && confere(t, numero(t, buf + q), &it[j]) && JA(a + q)) { mem[j] = a + q; tp[j] = t; ok = 1; }
                        }
                    }
                    if (!ok) continue;
                    grupos++;
                    for (int j = 0; j < ni; j++) guardar(mem[j], tp[j]);
                    break;  // este lugar já virou grupo: não testa os outros tipos nele
                }
        }
    #undef JA
    free(buf); free(ini); free(fim); free(antes);
    printf("ok %zu\n", nres);
    fprintf(stderr, "grupos %zu\n", grupos);
}

static void cmd_search(const char *valor, const char *tipos) {
    difat = 0;
    if (strchr(valor, ';')) { cmd_grupo(valor, 0); return; }
    alvo_t al; if (alvo(valor, &al)) { printf("err valor inválido: %s\n", valor); return; }
    double want = al.v;
    int usar[NT] = {0};
    if (!tipos || !*tipos || !strcmp(tipos, "auto")) for (int t = 0; t < NT; t++) usar[t] = 1;
    else { char tmp[64]; snprintf(tmp, sizeof tmp, "%s", tipos); char *sv; for (char *s = strtok_r(tmp, ",", &sv); s; s = strtok_r(NULL, ",", &sv)) { int t = tipo(s); if (t >= 0) usar[t] = 1; } }
    // inteiro só se o número for inteiro (0,5 não está guardado em Dword)
    if (!al.faixa && want != floor(want)) usar[T_I32] = usar[T_I64] = 0;
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
                    if (confere(t, numero(t, buf + o), &al)) guardar(a + o, t);
            }
        }
    free(buf); free(ini); free(fim);
    printf("ok %zu\n", nres);
}

static void cmd_refine(const char *valor) {
    difat = 0;
    if (strchr(valor, ';')) { cmd_grupo(valor, 1); return; }
    alvo_t al; if (alvo(valor, &al)) { printf("err valor inválido: %s\n", valor); return; }
    size_t k = 0; unsigned char b[8];
    for (size_t i = 0; i < nres; i++)
        if (!ler(res[i].addr, b, TSIZE[res[i].type]) && confere(res[i].type, numero(res[i].type, b), &al)) res[k++] = res[i];
    nres = k;
    printf("ok %zu\n", nres);
}

static void cmd_list(size_t n) {
    unsigned char b[8]; char v[64];
    for (size_t i = 0; i < nres && i < n; i++) {
        int t = res[i].type;
        if (ler(res[i].addr, b, TSIZE[t])) strcpy(v, "?"); else texto(t, b, v, sizeof v);
        if (difat && t <= T_F32) {  // na busca por mudança mostra também o valor da foto
            char f[64]; unsigned char fb[8] = {0}; memcpy(fb, &vb[i], 4); texto(t, fb, f, sizeof f);
            printf("%" PRIx64 " %s %s %s\n", res[i].addr, TNAME[t], v, f);
        } else printf("%" PRIx64 " %s %s\n", res[i].addr, TNAME[t], v);
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

// bytes ADDR N: os N bytes (até 4096) a partir de ADDR em hex, para montar a assinatura (o "desenho" em volta do valor)
static void cmd_bytes(const char *addr, const char *qtd) {
    uint64_t a = strtoull(addr, NULL, 16); int n = atoi(qtd);
    if (n <= 0 || n > 4096) { puts("err quantidade de 1 a 4096"); return; }
    static unsigned char b[4096];  // o "Em volta" lê 100 campos para cada lado
    if (ler(a, b, (size_t)n)) { printf("err não li %s\n", addr); return; }
    printf("ok ");
    for (int i = 0; i < n; i++) printf("%02x", b[i]);
    putchar('\n');
}

// aob PADRAO: procura o desenho de bytes em hex ("??" = qualquer byte) na memória do jogo; mostra até 20 endereços
static void cmd_aob(const char *hex) {
    size_t L = strlen(hex);
    if (L < 8 || L % 2 || L > 1024) { puts("err padrão em hex, de 4 a 512 bytes"); return; }
    size_t m = L / 2; unsigned char pat[512], msk[512]; size_t ancora = m;
    for (size_t i = 0; i < m; i++) {
        if (hex[2 * i] == '?') { msk[i] = 0; pat[i] = 0; continue; }
        unsigned v; if (sscanf(hex + 2 * i, "%2x", &v) != 1) { puts("err padrão inválido"); return; }
        pat[i] = (unsigned char)v; msk[i] = 1; if (ancora == m) ancora = i;
    }
    if (ancora == m) { puts("err padrão só com ??"); return; }
    uint64_t *ini, *fim; int nr = regioes(&ini, &fim);
    if (nr < 0) { puts("err não li o mapa de memória do jogo"); return; }
    enum { BLOCO = 1 << 20 };
    unsigned char *buf = malloc(BLOCO + 512);
    uint64_t achados[20]; size_t na = 0, total = 0;
    for (int r = 0; r < nr; r++)
        for (uint64_t a = ini[r]; a < fim[r]; a += BLOCO) {
            size_t n = fim[r] - a < BLOCO + m ? fim[r] - a : BLOCO + m - 1;  // sobra m-1 para o padrão que cruza o bloco
            if (ler(a, buf, n)) continue;
            for (size_t o = 0; o + m <= n && o < BLOCO; o++) {
                if (buf[o + ancora] != pat[ancora]) continue;
                size_t i = 0; while (i < m && (!msk[i] || buf[o + i] == pat[i])) i++;
                if (i == m) { if (na < 20) achados[na++] = a + o; total++; }
            }
        }
    free(buf); free(ini); free(fim);
    printf("ok %zu\n", total);
    for (size_t i = 0; i < na; i++) printf("%" PRIx64 "\n", achados[i]);
}

// ---- busca por mudança: foto da memória num arquivo e depois "mudou / igual / igual à foto / aumentou / diminuiu" ----
// Cada resultado guarda os 4 bytes da foto (vb) e os da última olhada (vp). Campos de 4 bytes; o tipo é um palpite
// (inteiro pequeno = Dword, senão número com vírgula com cara de jogo = Float).
// shortcut: só campos de 4 bytes (Dword/Float); Qword/Double ficam de fora até alguém precisar
static char foto[512];
enum { DMAX = 8000000 };  // shortcut: limite de resultados da 1ª comparação (memória do motor); avisar se faltar
static int palpite(uint32_t raw) {
    int32_t i; float f; memcpy(&i, &raw, 4); memcpy(&f, &raw, 4);
    if (i > -100000000 && i < 100000000) return T_I32;
    return (isfinite(f) && fabsf(f) > 1e-4f && fabsf(f) < 1e8f) ? T_F32 : T_I32;
}
static void cmd_snap(const char *arq) {
    uint64_t *ini, *fim; int nr = regioes(&ini, &fim);
    if (nr < 0) { puts("err não li o mapa de memória do jogo"); return; }
    FILE *f = fopen(arq, "wb"); if (!f) { printf("err não gravei a foto: %s\n", strerror(errno)); free(ini); free(fim); return; }
    enum { BLOCO = 1 << 20 }; unsigned char *buf = malloc(BLOCO); uint64_t total = 0;
    for (int r = 0; r < nr; r++)
        for (uint64_t a = ini[r]; a < fim[r]; a += BLOCO) {
            uint64_t n = fim[r] - a < BLOCO ? fim[r] - a : BLOCO;
            if (ler(a, buf, n)) continue;
            fwrite(&a, 8, 1, f); fwrite(&n, 8, 1, f); fwrite(buf, 1, n, f); total += n;
        }
    free(buf); free(ini); free(fim);
    if (fclose(f)) { puts("err a foto não coube no armazenamento"); return; }
    snprintf(foto, sizeof foto, "%s", arq); nres = 0; difat = 1;
    printf("ok %" PRIu64 "\n", total);
}
static int passa(int modo, int t, uint32_t agora, uint32_t antes, uint32_t dafoto) {
    unsigned char x[8] = {0}, y[8] = {0}; memcpy(x, &agora, 4); memcpy(y, &antes, 4);
    switch (modo) {
    case 0: return agora != antes;                       // mudou
    case 1: return agora == antes;                       // igual
    case 2: return agora == dafoto;                      // igual à foto (voltou)
    case 3: return numero(t, x) > numero(t, y);          // aumentou
    default: return numero(t, x) < numero(t, y);         // diminuiu
    }
}
static void cmd_diff(const char *m) {
    static const char *MODOS[] = {"mudou", "igual", "foto", "aumentou", "diminuiu"};
    int modo = -1; for (int i = 0; i < 5; i++) if (!strcmp(m, MODOS[i])) modo = i;
    if (modo < 0) { printf("err modo: mudou|igual|foto|aumentou|diminuiu\n"); return; }
    if (!difat) { puts("err tire a foto primeiro (snap)"); return; }
    if (nres == 0) {  // 1ª comparação: a memória de agora contra a foto inteira
        if (modo == 1 || modo == 2) { puts("err na 1ª comparação use mudou, aumentou ou diminuiu"); return; }
        FILE *f = fopen(foto, "rb"); if (!f) { puts("err a foto sumiu: tire de novo"); return; }
        enum { BLOCO = 1 << 20 }; unsigned char *velho = malloc(BLOCO), *novo = malloc(BLOCO);
        uint64_t a, n; int cheio = 0;
        free(vb); free(vp); vb = malloc(DMAX * 4); vp = malloc(DMAX * 4);
        while (!cheio && fread(&a, 8, 1, f) == 1 && fread(&n, 8, 1, f) == 1 && n <= BLOCO && fread(velho, 1, n, f) == n) {
            if (ler(a, novo, n)) continue;
            for (uint64_t o = 0; o + 4 <= n; o += 4) {
                uint32_t x, y; memcpy(&x, novo + o, 4); memcpy(&y, velho + o, 4);
                if (x == y && modo == 0) continue;
                int t = palpite(x);
                if (!passa(modo, t, x, y, y)) continue;
                if (nres == DMAX) { cheio = 1; break; }
                guardar(a + o, t); vb[nres - 1] = y; vp[nres - 1] = x;
            }
        }
        fclose(f); free(velho); free(novo);
        printf("ok %zu%s\n", nres, cheio ? " cheio" : "");
        return;
    }
    size_t k = 0;
    for (size_t i = 0; i < nres; i++) {
        uint32_t x; if (ler(res[i].addr, &x, 4)) continue;
        if (!passa(modo, res[i].type, x, vp[i], vb[i])) continue;
        res[k] = res[i]; vb[k] = vb[i]; vp[k] = x; k++;
    }
    nres = k;
    printf("ok %zu\n", nres);
}

// ---- vigia: "quem escreve aqui?" com o registro de vigia do processador (precisa de root/ptrace) ----
// Prende todas as linhas (threads) do jogo, arma o vigia de escrita em 4 bytes e espera a 1ª escrita. Mostra o comando
// (pc), a biblioteca + deslocamento, a instrução e, no ARM, o registro base (o começo do "bloco" onde o valor mora).
// shortcut: só as threads que já existem quando o vigia começa; uma criada depois não é vigiada
#ifndef NT_PRSTATUS
#define NT_PRSTATUS 1
#endif
#ifndef TRAP_HWBKPT
#define TRAP_HWBKPT 4
#endif
#if defined(__aarch64__)
#ifndef NT_ARM_HW_WATCH
#define NT_ARM_HW_WATCH 0x403
#endif
struct hw { uint32_t info, pad; struct { uint64_t addr; uint32_t ctrl, pad; } r[16]; };
static int armar(pid_t tid, uint64_t a, int on) {
    struct hw h; memset(&h, 0, sizeof h);
    if (on) {
        h.r[0].addr = a & ~7ULL;
        uint32_t bas = (0xfu << (a & 7)) & 0xff;                  // os 4 bytes dentro dos 8 alinhados
        h.r[0].ctrl = (bas << 5) | (2u << 3) | (2u << 1) | 1u;    // BAS | escrita | modo usuário | ligado
    }
    struct iovec io = {&h, offsetof(struct hw, r) + sizeof h.r[0]};
    return (int)ptrace(PTRACE_SETREGSET, tid, (void *)(uintptr_t)NT_ARM_HW_WATCH, &io);
}
static int registros(pid_t tid, uint64_t *pc, uint64_t *x) {
    struct { uint64_t x[31], sp, pc, pstate; } r; struct iovec io = {&r, sizeof r};
    if (ptrace(PTRACE_GETREGSET, tid, (void *)(uintptr_t)NT_PRSTATUS, &io)) return -1;
    *pc = r.pc; memcpy(x, r.x, sizeof r.x); x[31] = r.sp; return 0;
}
#elif defined(__x86_64__)
#include <sys/user.h>
static int armar(pid_t tid, uint64_t a, int on) {
    long dr7 = on ? (1L | (1L << 16) | (3L << 18)) : 0;           // L0 | escrita | 4 bytes
    if (on && ptrace(PTRACE_POKEUSER, tid, (void *)offsetof(struct user, u_debugreg[0]), (void *)(uintptr_t)(a & ~3ULL))) return -1;
    return (int)ptrace(PTRACE_POKEUSER, tid, (void *)offsetof(struct user, u_debugreg[7]), (void *)dr7);
}
static int registros(pid_t tid, uint64_t *pc, uint64_t *x) {
    struct user_regs_struct r; if (ptrace(PTRACE_GETREGS, tid, 0, &r)) return -1;
    *pc = r.rip; memset(x, 0, 32 * 8); return 0;
}
#else
static int armar(pid_t tid, uint64_t a, int on) { (void)tid; (void)a; (void)on; errno = ENOSYS; return -1; }
static int registros(pid_t tid, uint64_t *pc, uint64_t *x) { (void)tid; (void)pc; (void)x; return -1; }
#endif
static void onde(uint64_t pc) {  // "lib nome+deslocamento" pelo mapa
    char p[64]; snprintf(p, sizeof p, "/proc/%d/maps", target);
    FILE *f = fopen(p, "r"); if (!f) return; char line[512];
    while (fgets(line, sizeof line, f)) {
        uint64_t a, b, off; char name[400] = "";
        if (sscanf(line, "%" SCNx64 "-%" SCNx64 " %*s %" SCNx64 " %*s %*s %399[^\n]", &a, &b, &off, name) < 3) continue;
        if (pc < a || pc >= b) continue;
        const char *n = strrchr(name, '/'); n = n ? n + 1 : (name[0] ? name : "anon");
        printf("lib %s+%" PRIx64 "\n", n, pc - a + off); break;
    }
    fclose(f);
}
static void cmd_watch(const char *addr, const char *seg) {
    uint64_t a = strtoull(addr, NULL, 16); int s = seg[0] ? atoi(seg) : 30; if (s < 1 || s > 120) s = 30;
    char p[64]; snprintf(p, sizeof p, "/proc/%d/task", target);
    DIR *d = opendir(p); if (!d) { puts("err não li as linhas do jogo"); return; }
    pid_t tids[1024]; int nt = 0; struct dirent *de;
    while ((de = readdir(d)) && nt < 1024) if (isdigit((unsigned char)de->d_name[0])) tids[nt++] = atoi(de->d_name);
    closedir(d);
    int presos = 0, st;
    for (int i = 0; i < nt; i++) {
        if (ptrace(PTRACE_SEIZE, tids[i], 0, 0)) { tids[i] = 0; continue; }
        ptrace(PTRACE_INTERRUPT, tids[i], 0, 0);
        if (waitpid(tids[i], &st, __WALL) != tids[i] || armar(tids[i], a, 1)) { ptrace(PTRACE_DETACH, tids[i], 0, 0); tids[i] = 0; continue; }
        ptrace(PTRACE_CONT, tids[i], 0, 0); presos++;
    }
    if (!presos) { printf("err o vigia não prendeu o jogo (precisa de root): %s\n", strerror(errno)); return; }
    pid_t quem = 0; uint64_t pc = 0, x[32] = {0};
    time_t fim = time(NULL) + s;
    while (!quem && time(NULL) < fim) {
        int algum = 0;
        for (int i = 0; i < nt && !quem; i++) {
            if (!tids[i]) continue;
            pid_t w = waitpid(tids[i], &st, __WALL | WNOHANG);
            if (w <= 0) continue;
            algum = 1;
            if (WIFEXITED(st) || WIFSIGNALED(st)) { tids[i] = 0; continue; }
            int sig = WSTOPSIG(st); siginfo_t si;
            if (sig == SIGTRAP && (st >> 16) == 0 && !ptrace(PTRACE_GETSIGINFO, tids[i], 0, &si) && si.si_code == TRAP_HWBKPT) {
                quem = tids[i]; registros(quem, &pc, x); break;   // fica parado aqui até soltar
            }
            // outras paradas: o sinal segue para o jogo (a parada do PTRACE_INTERRUPT não tem sinal a repassar)
            ptrace(PTRACE_CONT, tids[i], 0, (void *)(uintptr_t)((st >> 16) ? 0 : sig));
        }
        if (!algum) usleep(5000);
    }
    // solta tudo: para cada linha, para (se roda), desarma e solta
    for (int i = 0; i < nt; i++) {
        if (!tids[i]) continue;
        if (tids[i] != quem) { ptrace(PTRACE_INTERRUPT, tids[i], 0, 0); waitpid(tids[i], &st, __WALL); }
        armar(tids[i], a, 0); ptrace(PTRACE_DETACH, tids[i], 0, 0);
    }
    if (!quem) { printf("err ninguém escreveu em %s em %d s\n", addr, s); return; }
    printf("pc %" PRIx64 "\n", pc); onde(pc);
#if defined(__aarch64__)
    uint32_t ins; if (!ler(pc, &ins, 4)) {
        printf("ins %08x\n", ins);
        int rn = (ins >> 5) & 31;  // nas instruções de guardar (str/stur/stp) o registro base fica nos bits 9..5
        if ((ins & 0x3a000000) == 0x38000000 || (ins & 0x3a000000) == 0x28000000) printf("base %" PRIx64 " x%d\n", x[rn], rn);
    }
    puts("ok arm64");
#else
    puts("ok x86_64");  // no x86 o pc já é a instrução seguinte: só para estudo, sem "desligar"
#endif
}
static void cmd_patch(const char *addr, const char *hex) {
    uint64_t a = strtoull(addr, NULL, 16); size_t L = strlen(hex);
    if (L < 2 || L > 32 || L % 2) { puts("err de 1 a 16 bytes em hex"); return; }
    unsigned char novo[16], velho[16]; size_t n = L / 2;
    for (size_t i = 0; i < n; i++) { unsigned v; if (sscanf(hex + 2 * i, "%2x", &v) != 1) { puts("err hex inválido"); return; } novo[i] = (unsigned char)v; }
    if (ler(a, velho, n)) { printf("err não li %s\n", addr); return; }
    if (escrever(a, novo, n)) { printf("err não troquei %s: %s\n", addr, strerror(errno)); return; }
    printf("ok "); for (size_t i = 0; i < n; i++) printf("%02x", velho[i]); putchar('\n');
}

int main(void) {
    setvbuf(stdout, NULL, _IOLBF, 0);
    pthread_t th; pthread_create(&th, NULL, congelador, NULL);
    printf("ok unitd 0.1 uid=%d\n", getuid());
    char line[1600], c[32], a1[1100], a2[128], a3[128];  // a1 grande: o padrão do aob
    while (fgets(line, sizeof line, stdin)) {
        c[0] = a1[0] = a2[0] = a3[0] = 0;
        sscanf(line, "%31s %1099s %127s %127s", c, a1, a2, a3);
        if (!c[0]) continue;
        if (!strcmp(c, "quit")) break;
        if (!strcmp(c, "ps")) { cmd_ps(); continue; }
        if (!strcmp(c, "sleep")) { usleep((useconds_t)(atoi(a1) % 10001) * 1000); puts("ok"); continue; }
        if (!strcmp(c, "attach")) {
            pid_t p = (pid_t)atoi(a1); char path[64]; snprintf(path, sizeof path, "/proc/%d", p);
            if (p <= 0 || access(path, F_OK)) { printf("err processo %s não existe\n", a1); continue; }
            if (target) kill(target, SIGCONT);  // o jogo de antes nunca fica parado
            pthread_mutex_lock(&fzm); memset(fz, 0, sizeof fz); target = p; nres = 0; pthread_mutex_unlock(&fzm);
            printf("ok %d\n", p); continue;
        }
        if (!target) { puts("err escolha o jogo primeiro (attach)"); continue; }
        char path[64]; snprintf(path, sizeof path, "/proc/%d", target);
        if (access(path, F_OK)) { puts("err o jogo fechou"); continue; }
        if (!strcmp(c, "search")) cmd_search(a1, a2);
        else if (!strcmp(c, "refine")) cmd_refine(a1);
        else if (!strcmp(c, "list")) cmd_list(strtoul(a1, NULL, 10));
        else if (!strcmp(c, "bytes")) cmd_bytes(a1, a2);
        else if (!strcmp(c, "aob")) cmd_aob(a1);
        else if (!strcmp(c, "snap")) cmd_snap(a1);
        else if (!strcmp(c, "diff")) cmd_diff(a1);
        else if (!strcmp(c, "watch")) cmd_watch(a1, a2);
        else if (!strcmp(c, "patch")) cmd_patch(a1, a2);
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
        else if (!strcmp(c, "pause")) puts(kill(target, SIGSTOP) ? "err não pausou" : "ok");
        else if (!strcmp(c, "resume")) puts(kill(target, SIGCONT) ? "err não continuou" : "ok");
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
    // o Unit fechou ou caiu (fim da entrada): não deixa o jogo congelado, senão o Android mostra "não está respondendo"
    if (target) kill(target, SIGCONT);
    return 0;
}
