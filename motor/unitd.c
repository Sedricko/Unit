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
//   ptrscan ENDEREÇO [NÍVEIS]  caminhos (ponteiros) de uma biblioteca até o endereço: "lib+OFF:o1:...:oN" -> ok N
//   ptr CAMINHO                segue o caminho de agora                                     -> ok ENDEREÇO
//   vivo / fechar              o jogo ainda está aberto? / fecha o jogo
//   android DESDE PACOTE       o que o Android anotou (logcat) sobre o jogo, o Unit, ANR e falhas -> ok N
//   tirar A1,A2,...            tira endereços dos resultados (apagados pela pessoa)        -> ok SOBRARAM TIRADOS
//   info                       regiões, MB legíveis, memória do motor e do jogo (KB)       -> ok N MB KBMOTOR KBJOGO
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
#include <sys/uio.h>
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
    nres = 0;
    enum { BLOCO = 1 << 20 };
    unsigned char *buf = malloc(BLOCO + 8);
    if (!buf) { free(ini); free(fim); puts("err sem memória"); return; }
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
                    for (int j = 0; j < ni; j++) guardar(mem[j], tp[j]);
                    break;  // este lugar já virou grupo: não testa os outros tipos nele
                }
        }
    #undef JA
    free(buf); free(ini); free(fim); free(antes);
    printf("ok %zu\n", nres);
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
    if (!buf) { free(ini); free(fim); puts("err sem memória"); return; }
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
    if (!buf) { free(ini); free(fim); puts("err sem memória"); return; }
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
    for (size_t i = 0; i < na; i++) printf("%" PRIx64 "\n", achados[i]);  // antes do "ok": depois dele iria para o próximo comando
    printf("ok %zu\n", total);
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
    if (!buf) { fclose(f); free(ini); free(fim); puts("err sem memória"); return; }
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
        if (!vb || !vp || !velho || !novo) { free(vb); free(vp); vb = vp = NULL; free(velho); free(novo); fclose(f); difat = 0; puts("err sem memória"); return; }
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
        // passou do limite: não fica com uma parte só (o valor certo podia estar no resto); a foto continua valendo
        if (cheio) { nres = 0; puts("err mudou coisa demais (mais de 8 milhões): use Aumentou ou Diminuiu, ou filtre logo depois da foto"); return; }
        printf("ok %zu\n", nres);
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

// ---- ponteiros: o caminho fixo (biblioteca + deslocamentos) que o jogo usa para chegar num valor ----
// Mapa inteiro: as regiões graváveis de arquivo (o .data das bibliotecas) e a anônima colada logo depois (o .bss) são
// "fixas"; a base de cada biblioteca é o começo da 1ª região dela.
// No Android ARM64 os ponteiros do heap vêm com uma marca no byte de cima (0xb4…): ela é tirada antes de comparar.
#define SEM_MARCA(v) ((v) & 0x00ffffffffffffffULL)
typedef struct { uint64_t ini, fim; int rw, fixa; char lib[64]; } reg_t;
static reg_t *rg; static int nrg;
static void ler_mapa(void) {
    char p[64]; snprintf(p, sizeof p, "/proc/%d/maps", target);
    FILE *f = fopen(p, "r"); nrg = 0; if (!f) return;
    static int cap; char line[512], ult[64] = ""; uint64_t ultfim = 0; int ultfixa = 0;
    while (fgets(line, sizeof line, f)) {
        uint64_t a, b; char perms[8] = "", name[400] = "";
        if (sscanf(line, "%" SCNx64 "-%" SCNx64 " %7s %*s %*s %*s %399[^\n]", &a, &b, perms, name) < 3) continue;
        if (nrg == cap) { cap = cap ? cap * 2 : 1024; rg = realloc(rg, cap * sizeof *rg); }
        reg_t *r = &rg[nrg++]; memset(r, 0, sizeof *r); r->ini = a; r->fim = b;
        r->rw = perms[0] == 'r' && perms[1] == 'w' && !strstr(name, "/dev/") && !strstr(name, "[stack") && !strstr(name, "kgsl");
        const char *nm = strrchr(name, '/');
        // só as bibliotecas do jogo contam como começo de caminho: as do sistema (/system, /apex, /vendor…) guardam ponteiros
        // do alocador que mudam a cada abertura e enchiam o limite de caminhos com caminhos que quebram
        int sistema = !strncmp(name, "/system/", 8) || !strncmp(name, "/apex/", 6) || !strncmp(name, "/vendor/", 8) || !strncmp(name, "/product/", 9)
                   || !strncmp(name, "/system_ext/", 12) || !strncmp(name, "/dev/", 5);
        if (name[0] == '/') { snprintf(r->lib, sizeof r->lib, "%s", nm + 1); snprintf(ult, sizeof ult, "%s", nm + 1); r->fixa = r->rw && !sistema; }
        // só o 1º bloco anônimo colado na biblioteca é o .bss dela; os seguintes são memória comum que por acaso ficou
        // encostada (o relatório mostrou "libil2cpp.so+1ceec3950": 7 GB depois da biblioteca, e todos quebravam ao reabrir)
        else if (a == ultfim && ult[0] && (!name[0] || strstr(name, ".bss"))) { snprintf(r->lib, sizeof r->lib, "%s", ult); r->fixa = r->rw && ultfixa; ult[0] = 0; }
        else ult[0] = 0;
        if (name[0] == '/') ultfixa = !sistema;
        ultfim = b;
    }
    fclose(f);
}
static int achar_reg(uint64_t v) {  // as regiões vêm em ordem no mapa: busca binária
    int lo = 0, hi = nrg - 1;
    while (lo <= hi) { int m = (lo + hi) / 2; if (v < rg[m].ini) hi = m - 1; else if (v >= rg[m].fim) lo = m + 1; else return m; }
    return -1;
}
static uint64_t base_lib(const char *lib) { for (int i = 0; i < nrg; i++) if (!strcmp(rg[i].lib, lib)) return rg[i].ini; return 0; }

typedef struct { uint64_t v, onde; } pt_t;        // um ponteiro: o valor (para onde aponta) e onde ele mora
typedef struct { uint64_t t; int pai; uint32_t o; } no_t;  // um passo do caminho: o alvo, quem veio antes e o deslocamento
static int cmp_pt(const void *a, const void *b) { uint64_t x = ((const pt_t *)a)->v, y = ((const pt_t *)b)->v; return x < y ? -1 : x > y; }
enum { PMAX = 12000000, NMAX = 400000, CMAX = 1000, DIST = 0x400 };
// shortcut: até 12 milhões de ponteiros, 400 mil passos e 1000 caminhos (memória do celular); jogos maiores podem perder caminhos
// nota de um caminho (menor = mais confiável): cada pulo a mais é uma chance de quebrar; começar no código do jogo
// (il2cpp/unity/main) é melhor que noutra biblioteca; pulos pequenos são campos de verdade, grandes costumam ser acaso
typedef struct { char txt[240]; unsigned nota; } cam_t;
static unsigned nota(int nivel, const char *lib, unsigned soma) {
    int jogo = strstr(lib, "il2cpp") || strstr(lib, "unity") || strstr(lib, "libmain") || strstr(lib, "libgame");
    return (unsigned)nivel * 1000 + (jogo ? 0 : 500) + (soma > 0x3000 ? 0x3000 : soma) / 16;
}
static int cmp_cam(const void *a, const void *b) { unsigned x = ((const cam_t *)a)->nota, y = ((const cam_t *)b)->nota; return x < y ? -1 : x > y; }
static void cmd_ptrscan(const char *addr, const char *niv) {
    uint64_t alvo = strtoull(addr, NULL, 16); int nmax = niv[0] ? atoi(niv) : 8; if (nmax < 1 || nmax > 8) nmax = 8;
    ler_mapa(); if (!nrg) { puts("err não li o mapa de memória do jogo"); return; }
    pt_t *pt = malloc((size_t)PMAX * sizeof *pt); size_t np = 0;
    enum { BLOCO = 1 << 20 }; unsigned char *buf = malloc(BLOCO);
    if (!pt || !buf) { free(pt); free(buf); puts("err sem memória para os ponteiros"); return; }
    for (int r = 0; r < nrg && np < PMAX; r++) {
        if (!rg[r].rw) continue;
        for (uint64_t a = rg[r].ini; a < rg[r].fim && np < PMAX; a += BLOCO) {
            size_t n = rg[r].fim - a < BLOCO ? rg[r].fim - a : BLOCO;
            if (ler(a, buf, n)) continue;
            for (size_t o = 0; o + 8 <= n && np < PMAX; o += 8) {
                uint64_t v; memcpy(&v, buf + o, 8); v = SEM_MARCA(v);
                if (v < 0x10000) continue;
                int k = achar_reg(v); if (k < 0 || !rg[k].rw) continue;
                pt[np].v = v; pt[np].onde = a + o; np++;
            }
        }
    }
    free(buf);
    qsort(pt, np, sizeof *pt, cmp_pt);
    no_t *no = malloc(NMAX * sizeof *no); int nn = 0, nc = 0;
    cam_t *cams = malloc(CMAX * sizeof *cams);
    if (!no || !cams) { free(no); free(cams); free(pt); puts("err sem memória para os caminhos"); return; }
    no[nn++] = (no_t){alvo, -1, 0};
    int ini = 0;
    for (int nivel = 1; nivel <= nmax && nc < CMAX; nivel++) {
        int fim = nn;
        for (int i = ini; i < fim && nc < CMAX; i++) {
            uint64_t t = no[i].t, de = t >= DIST ? t - DIST : 0;
            size_t lo = 0, hi = np;  // 1º ponteiro com valor >= t - DIST
            while (lo < hi) { size_t m = (lo + hi) / 2; if (pt[m].v < de) lo = m + 1; else hi = m; }
            for (size_t j = lo; j < np && pt[j].v <= t && nc < CMAX; j++) {
                int k = achar_reg(pt[j].onde);
                uint32_t o = (uint32_t)(t - pt[j].v);
                // chegou numa biblioteca: um caminho (a menos de 256 MB do começo dela; mais longe não é parte dela)
                if (k >= 0 && rg[k].fixa && pt[j].onde - base_lib(rg[k].lib) < 0x10000000ULL) {
                    cam_t *c = &cams[nc++]; int len = snprintf(c->txt, sizeof c->txt, "%s+%" PRIx64 ":%x", rg[k].lib, pt[j].onde - base_lib(rg[k].lib), o);
                    unsigned soma = o;
                    for (int x = i; no[x].pai >= 0 && len < (int)sizeof c->txt; x = no[x].pai) { len += snprintf(c->txt + len, sizeof c->txt - len, ":%x", no[x].o); soma += no[x].o; }
                    c->nota = nota(nivel, rg[k].lib, soma);
                } else if (nn < NMAX && nivel < nmax) no[nn++] = (no_t){pt[j].onde, i, o};
            }
        }
        ini = fim;
    }
    free(no); free(pt);
    qsort(cams, nc, sizeof *cams, cmp_cam);  // o mais confiável primeiro
    for (int i = 0; i < nc; i++) puts(cams[i].txt);
    free(cams);
    printf("ok %d\n", nc);
}
// segue "lib+OFF:o1:o2:...": lê o ponteiro em base+OFF, soma o1, lê de novo, ... o último deslocamento dá o valor
static void cmd_ptr(const char *cam) {
    char tmp[600]; snprintf(tmp, sizeof tmp, "%s", cam);
    char *mais = strrchr(tmp, '+'); if (!mais) { puts("err caminho inválido"); return; }
    *mais = 0; ler_mapa();
    uint64_t b = base_lib(tmp); if (!b) { printf("err biblioteca %s não está aberta\n", tmp); return; }
    char *sv, *x = strtok_r(mais + 1, ":", &sv); if (!x) { puts("err caminho inválido"); return; }
    uint64_t a = b + strtoull(x, NULL, 16);
    for (x = strtok_r(NULL, ":", &sv); x; x = strtok_r(NULL, ":", &sv)) {
        uint64_t p; if (ler(a, &p, 8)) { puts("err o caminho quebrou"); return; }
        a = SEM_MARCA(p) + strtoull(x, NULL, 16);
    }
    printf("ok %" PRIx64 "\n", a);
}

// o jogo ainda existe? (um processo morto que ninguém recolheu ainda aparece no /proc como "Z": conta como fechado)
static int aberto(pid_t p) {
    char path[64], st[256] = ""; snprintf(path, sizeof path, "/proc/%d/stat", p);
    FILE *f = fopen(path, "r"); if (!f) return 0;
    size_t n = fread(st, 1, sizeof st - 1, f); fclose(f); st[n] = 0;
    char *fim = strrchr(st, ')');  // o nome vem entre parênteses e pode ter espaço; o estado vem logo depois
    return fim && fim[1] == ' ' && fim[2] != 'Z' && fim[2] != 'X';
}

// android DESDE PACOTE: o que o Android anotou (logcat) desde DESDE (segundos, ex. 1730510000.5) sobre o jogo, o Unit, ANR,
// falhas e falta de memória. Só números/ponto e letras/ponto: os dois vão para um comando do sistema.
static int seguro(const char *s, const char *ok) { if (!*s) return 0; for (; *s; s++) if (!isalnum((unsigned char)*s) && !strchr(ok, *s)) return 0; return 1; }
static void cmd_android(const char *desde, const char *pac) {
    if (!seguro(desde, ".") || !seguro(pac, "._")) { puts("err uso: android SEGUNDOS PACOTE"); return; }
    char c[256]; snprintf(c, sizeof c, "logcat -d -v epoch -b main,system,crash,events -T '%s' '*:W' 2>/dev/null", desde);
    FILE *p = popen(c, "r"); if (!p) { puts("err não li o logcat"); return; }
    static const char *chaves[] = {"ANR in", "Input dispatching timed out", "lowmemorykiller", "am_kill", "am_anr", "am_crash",
                                    "FATAL EXCEPTION", "Fatal signal", "dev.unit.app", "unitd", NULL};
    char ln[1200]; int n = 0;
    while (fgets(ln, sizeof ln, p)) {
        int bate = strstr(ln, pac) != NULL;
        for (int i = 0; !bate && chaves[i]; i++) bate = strstr(ln, chaves[i]) != NULL;
        if (!bate || n >= 80) continue;
        ln[strcspn(ln, "\n")] = 0;
        if (!isdigit((unsigned char)ln[strspn(ln, " ")])) continue;  // pedaço de outra mensagem (ex.: as listas de .dex): ruído
        puts(ln); n++;
    }
    pclose(p);
    printf("ok %d\n", n);
}

// tirar A1,A2,...: tira esses endereços dos resultados (a pessoa apagou da lista; o próximo refino não traz de volta)
static void cmd_tirar(const char *lista) {
    uint64_t fora[128]; int nf = 0; char tmp[1100]; snprintf(tmp, sizeof tmp, "%s", lista); char *sv;
    for (char *x = strtok_r(tmp, ",", &sv); x && nf < 128; x = strtok_r(NULL, ",", &sv)) fora[nf++] = strtoull(x, NULL, 16);
    if (!nf) { puts("err uso: tirar ENDEREÇO,ENDEREÇO,..."); return; }
    qsort(fora, nf, 8, cmpu64);
    size_t k = 0, antes = nres;
    for (size_t i = 0; i < nres; i++) {
        if (bsearch(&res[i].addr, fora, nf, 8, cmpu64)) continue;
        if (difat && vb && vp) { vb[k] = vb[i]; vp[k] = vp[i]; }
        res[k++] = res[i];
    }
    nres = k;
    printf("ok %zu %zu\n", nres, antes - nres);  // sobraram, tirados
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
        if (!aberto(target)) { puts("err o jogo fechou"); continue; }
        if (!strcmp(c, "search")) cmd_search(a1, a2);
        else if (!strcmp(c, "refine")) cmd_refine(a1);
        else if (!strcmp(c, "list")) cmd_list(strtoul(a1, NULL, 10));
        else if (!strcmp(c, "bytes")) cmd_bytes(a1, a2);
        else if (!strcmp(c, "aob")) cmd_aob(a1);
        else if (!strcmp(c, "snap")) cmd_snap(a1);
        else if (!strcmp(c, "diff")) cmd_diff(a1);
        else if (!strcmp(c, "ptrscan")) cmd_ptrscan(a1, a2);
        else if (!strcmp(c, "ptr")) cmd_ptr(a1);
        else if (!strcmp(c, "vivo")) puts("ok");
        else if (!strcmp(c, "tirar")) cmd_tirar(a1);
        else if (!strcmp(c, "android")) cmd_android(a1, a2);
        else if (!strcmp(c, "info")) {  // para o autoteste: quantas regiões e quantos MB do jogo o Unit consegue ler
            uint64_t *ini, *fim; int nr = regioes(&ini, &fim); uint64_t tot = 0;
            if (nr < 0) { puts("err não li o mapa de memória do jogo"); continue; }
            for (int i = 0; i < nr; i++) tot += fim[i] - ini[i];
            free(ini); free(fim);
            // + memória real (RSS, em KB) do próprio motor e do jogo: o relatório acompanha para achar vazamento
            long rm = 0, rj = 0; char st[64], ln[256]; FILE *f;
            if ((f = fopen("/proc/self/status", "r"))) { while (fgets(ln, sizeof ln, f)) sscanf(ln, "VmRSS: %ld", &rm); fclose(f); }
            snprintf(st, sizeof st, "/proc/%d/status", target);
            if ((f = fopen(st, "r"))) { while (fgets(ln, sizeof ln, f)) sscanf(ln, "VmRSS: %ld", &rj); fclose(f); }
            printf("ok %d %" PRIu64 " %ld %ld\n", nr, tot >> 20, rm, rj);
        }  // o teste "o jogo fechou" já foi feito acima, antes de qualquer comando
        else if (!strcmp(c, "fechar")) { kill(target, SIGCONT); puts(kill(target, SIGKILL) ? "err não fechei o jogo" : "ok"); }
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
