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
//   classe ENDEREÇO            classe do Unity do objeto do valor; "texto OFF HEX" = assinatura -> ok CLASSE ESPAÇO CAMPO
//   pelonome CLASSE ESPAÇO CAMPO [OFF=HEX,...]  o valor achado pelo nome da classe e a assinatura -> ok ACHADOS OBJETOS
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
typedef struct { uint64_t ini, fim; int rw, fixa, longe, usa, anon; char lib[64]; } reg_t;
static reg_t *rg; static int nrg;
// onde a biblioteca termina na memória (fim do último PT_LOAD do ELF, em páginas); 0 se não deu para ler. Guardado por caminho:
// o ptr chama o mapa a cada caminho conferido
static uint64_t fim_elf(const char *caminho) {
    static struct { char p[200]; uint64_t fim; } cache[64]; static int nc;
    for (int i = 0; i < nc; i++) if (!strcmp(cache[i].p, caminho)) return cache[i].fim;
    char pr[480]; snprintf(pr, sizeof pr, "/proc/%d/root%s", target, caminho);
    FILE *f = fopen(pr, "rb"); if (!f) f = fopen(caminho, "rb");
    uint64_t fim = 0; unsigned char e[64];
    if (f && fread(e, 1, 64, f) == 64 && !memcmp(e, "\177ELF", 4) && e[4] == 2) {
        uint64_t phoff; uint16_t phent, phnum; memcpy(&phoff, e + 32, 8); memcpy(&phent, e + 54, 2); memcpy(&phnum, e + 56, 2);
        for (int i = 0; i < phnum && phent >= 56; i++) {
            unsigned char ph[56]; uint32_t tipo; uint64_t va, msz;
            if (fseek(f, (long)(phoff + (uint64_t)i * phent), SEEK_SET) || fread(ph, 1, 56, f) != 56) break;
            memcpy(&tipo, ph, 4); memcpy(&va, ph + 16, 8); memcpy(&msz, ph + 40, 8);
            if (tipo == 1 && va + msz > fim) fim = va + msz;
        }
    }
    if (f) fclose(f);
    fim = (fim + 4095) & ~4095ULL;
    if (nc < 64 && strlen(caminho) < sizeof cache[0].p) { snprintf(cache[nc].p, sizeof cache[nc].p, "%s", caminho); cache[nc++].fim = fim; }
    return fim;
}
static void ler_mapa(void) {
    char p[64]; snprintf(p, sizeof p, "/proc/%d/maps", target);
    const char *teste = getenv("UNITD_MAPS");  // só o teste: um mapa com nomes de região do Android (o Linux do teste não dá nome)
    FILE *f = fopen(teste ? teste : p, "r"); nrg = 0; if (!f) return;
    static int cap; char line[512], ult[64] = ""; uint64_t ultfim = 0, libini = 0, libfim = 0; int ultfixa = 0; char libnome[64] = "";
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
        if (name[0] == '/') {
            if (strcmp(libnome, nm + 1)) { libini = a; libfim = sistema ? 0 : fim_elf(name); snprintf(libnome, sizeof libnome, "%s", nm + 1); }  // 1ª região: a base
            snprintf(r->lib, sizeof r->lib, "%s", nm + 1); snprintf(ult, sizeof ult, "%s", nm + 1); r->fixa = r->rw && !sistema;
        }
        // só o 1º bloco anônimo colado na biblioteca é o .bss dela; os seguintes são memória comum que por acaso ficou
        // encostada (o relatório mostrou "libil2cpp.so+1ceec3950": 7 GB depois da biblioteca, e todos quebravam ao reabrir)
        else if (a == ultfim && ult[0] && (!name[0] || strstr(name, ".bss")) && !(libfim && a >= libini + libfim)) {
            snprintf(r->lib, sizeof r->lib, "%s", ult); r->fixa = r->rw && ultfixa; ult[0] = 0;
            // sem nome, o Linux junta a .bss com a memória anônima que vier colada depois numa linha só: o que passa do fim da
            // biblioteca (pelo ELF) é memória comum. O teste mostrou caminhos "libil2cpp.so+105ae8", depois do fim da .bss
            if (libfim && b > libini + libfim) {
                uint64_t corte = libini + libfim; r->fim = corte;
                if (nrg == cap) { cap *= 2; rg = realloc(rg, cap * sizeof *rg); }
                reg_t *q = &rg[nrg++]; memset(q, 0, sizeof *q); q->ini = corte; q->fim = b; q->rw = rg[nrg - 2].rw; q->anon = 1;
                q->usa = q->rw && !name[0];
                r = &rg[nrg - 2];  // o realloc pode ter mudado o endereço
            }
        }
        else ult[0] = 0;
        // regiões que entram na procura de ponteiros: as mesmas que o GameGuardian procura por padrão (Ca, Ch, Cd, Cb, A), menos
        // a Java. Ficam de fora as que ele chama de "lentas" (O): pilhas das threads, linker, bibliotecas do sistema, ashmem…
        // Elas guardam ponteiros velhos que viravam atalhos falsos e deixavam a procura mais lenta
        r->anon = name[0] != '/';
        r->usa = r->rw && (r->fixa || !name[0] || strstr(name, "malloc") || !strcmp(name, "[anon:scudo:primary]")
                 || !strcmp(name, "[anon:scudo:secondary]") || strstr(name, "[heap]"));
        if (strstr(name, "/dev/zero") && perms[0] == 'r' && perms[1] == 'w') { r->usa = 1; r->anon = 1; }
        if (name[0] == '/') ultfixa = !sistema;
        ultfim = b;
    }
    fclose(f);
    // jogo Unity (tem libil2cpp): só o código do jogo conta como começo de caminho. As outras bibliotecas (Firebase,
    // anúncios…) guardam ponteiros para tudo e enchiam a procura (o relatório mostrou 1000 caminhos do Firebase, todos falsos)
    int unity = 0;
    for (int i = 0; i < nrg; i++) if (strstr(rg[i].lib, "libil2cpp")) unity = 1;
    if (unity) for (int i = 0; i < nrg; i++)
        if (rg[i].fixa && !strstr(rg[i].lib, "il2cpp")) rg[i].fixa = 0;  // os campos C# do jogo vêm da libil2cpp; a libunity é o
        // motor gráfico e o gerenciador de memória: aponta para o heap todo e enchia os candidatos (Orecraft: 1500 caminhos, todos quebraram)
    // salto longo (tabela[índice]) só saindo da libil2cpp, onde ficam as tabelas de classes. A libunity guarda ponteiros do
    // gerenciador de memória para o heap todo: com salto longo, qualquer coisa virava caminho (Orecraft: 1500, todos quebraram)
    for (int i = 0; i < nrg; i++) rg[i].longe = rg[i].fixa && (!unity || strstr(rg[i].lib, "il2cpp"));
}
static int achar_reg(uint64_t v) {  // as regiões vêm em ordem no mapa: busca binária
    int lo = 0, hi = nrg - 1;
    while (lo <= hi) { int m = (lo + hi) / 2; if (v < rg[m].ini) hi = m - 1; else if (v >= rg[m].fim) lo = m + 1; else return m; }
    return -1;
}
static uint64_t base_lib(const char *lib) { for (int i = 0; i < nrg; i++) if (!strcmp(rg[i].lib, lib)) return rg[i].ini; return 0; }

typedef struct { uint64_t v, onde; } pt_t;        // um ponteiro: o valor (para onde aponta) e onde ele mora
typedef struct { uint64_t t; int pai; uint32_t o, custo; int nivel; } no_t;  // um passo: o endereço, quem veio antes, o deslocamento, o custo (soma)
static int cmp_pt(const void *a, const void *b) { uint64_t x = ((const pt_t *)a)->v, y = ((const pt_t *)b)->v; return x < y ? -1 : x > y; }
// DIST: salto entre estruturas (até 4 KB, como o Cheat Engine). DISTFIXA: o salto que sai da biblioteca pode ser grande: nos
// jogos Unity ele entra numa tabela (biblioteca → tabela[índice] → classe → dados); com 1 KB o caminho certo ficava de fora
// CMAX: caminhos que saem; CPOOL: candidatos juntados antes de escolher (uma biblioteca só não enche tudo - o relatório
// mostrou 1000 caminhos do Firebase e nenhum do jogo); POR_LIB: no máximo isso de cada biblioteca na saída.
// DISTFIXA 512 KB: jogos Unity têm dezenas de milhares de classes, e o salto entra na tabela delas (índice × 8)
enum { NMAX = 2000000, CMAX = 3000, CPOOL = 20000, POR_LIB = 3000, DIST = 0x1000, DISTFIXA = 0x80000, HCAP = 1 << 23, PENA = 0x20 };
// PENA: custo de cada nível a mais (um pulo a mais é uma chance a mais de quebrar)
// Sem limite de quem aponta para um mesmo objeto: um objeto popular (um gerenciador que milhares usam) tinha o ponteiro
// verdadeiro cortado ao acaso (o limite de 64 era da procura antiga, por nível); a procura pelo menor custo não precisa
// shortcut: até 100 milhões de ponteiros (1/4 da memória livre), 2 milhões de passos, 20 mil candidatos e 3000 caminhos na saída (memória do celular); jogos maiores podem perder caminhos
// nota de um caminho (menor = mais confiável): cada pulo a mais é uma chance de quebrar; começar no código do jogo
// (il2cpp/unity/main) é melhor que noutra biblioteca; pulos pequenos são campos de verdade, grandes costumam ser acaso
typedef struct { char txt[240]; unsigned nota; } cam_t;
static unsigned nota(int nivel, const char *lib, unsigned soma) {
    int jogo = strstr(lib, "il2cpp") || strstr(lib, "unity") || strstr(lib, "libmain") || strstr(lib, "libgame");
    // a mesma conta da procura: soma dos deslocamentos + PENA por nível (antes o nível pesava mais, e os atalhos de 2 níveis,
    // que quebram ao reabrir, vinham na frente do caminho de verdade de 6)
    return (unsigned)nivel * PENA + (jogo ? 0 : 0x200) + (soma > 0x3000 ? 0x3000 : soma);
}
static int cmp_cam(const void *a, const void *b) { unsigned x = ((const cam_t *)a)->nota, y = ((const cam_t *)b)->nota; return x < y ? -1 : x > y; }
// liga o endereço t às bibliotecas: cada ponteiro de biblioteca até DISTFIXA antes de t vira um caminho
// "lib+OFF:oLib[:oExtra]:(deslocamentos de pai até o alvo)". O salto longo só vale saindo da biblioteca (tabelas do Unity)
// é a ficha de uma classe do Unity (Il2CppClass)? Em +0x10 ela aponta para o nome da classe em texto (ex.: "Recursos").
// É o que os scripts do GameGuardian usam para achar classes pelo nome; aqui serve para reconhecer o caminho de verdade
static int eh_classe(uint64_t c) {
    uint64_t pn; char nm[48];
    if (ler(SEM_MARCA(c) + 0x10, &pn, 8)) return 0;
    pn = SEM_MARCA(pn); if (pn < 0x10000 || ler(pn, nm, sizeof nm)) return 0;
    if (!isalpha((unsigned char)nm[0]) && nm[0] != '_' && nm[0] != '<') return 0;
    for (int i = 1; i < (int)sizeof nm; i++) {
        if (!nm[i]) return i >= 2;
        if (!isalnum((unsigned char)nm[i]) && !strchr("_<>`.$", nm[i])) return 0;
    }
    return 0;
}
// nome e espaço de nomes de uma ficha de classe (0 se não for ficha). O espaço de nomes vazio vira "-"
static int nome_classe(uint64_t c, char *nome, char *esp) {
    if (!eh_classe(c)) return 0;
    uint64_t p[2]; if (ler(SEM_MARCA(c) + 0x10, p, 16)) return 0;
    char b[64]; memset(b, 0, sizeof b);
    if (ler(SEM_MARCA(p[0]), b, 63)) return 0;
    snprintf(nome, 64, "%s", b);
    memset(b, 0, sizeof b);
    if (!SEM_MARCA(p[1]) || ler(SEM_MARCA(p[1]), b, 63) || !b[0]) snprintf(esp, 64, "-"); else snprintf(esp, 64, "%s", b);
    for (char *x = esp; *x; x++) if (*x == ' ') *x = '_';
    return 1;
}
// texto de um System.String do Unity (cabeçalho 16 bytes, tamanho int32 em +0x10, letras UTF-16 em +0x14), em hex. Só
// textos curtos e legíveis (nomes de itens, ids): é a "assinatura" que separa objetos da mesma classe
static int texto_unity(uint64_t s, char *hex, size_t nhex) {
    char nome[64], esp[64]; uint64_t k; int32_t n; uint16_t u[64];
    s = SEM_MARCA(s);
    if (s < 0x10000 || ler(s, &k, 8) || !nome_classe(k, nome, esp) || strcmp(nome, "String") || strcmp(esp, "System")) return 0;
    if (ler(s + 0x10, &n, 4) || n < 1 || n > 63 || ler(s + 0x14, u, (size_t)n * 2)) return 0;
    if ((size_t)n * 2 + 1 > nhex) return 0;
    for (int i = 0; i < n; i++) { if (u[i] < 0x20 || u[i] > 0x7e) return 0; snprintf(hex + i * 2, 3, "%02x", u[i]); }
    return 1;
}
// é uma posição da tabela de classes? As vizinhas (−8 e +8) também são fichas de classe ou vazias, com pelo menos uma ficha.
// Um campo qualquer de um objeto que por acaso aponta para uma classe (o cabeçalho de todo objeto aponta) tem lixo em volta
static int eh_tabela(uint64_t pos) {
    uint64_t v[2]; int classes = 0;
    if (ler(pos - 8, &v[0], 8) || ler(pos + 8, &v[1], 8)) return 0;
    for (int i = 0; i < 2; i++) { if (!SEM_MARCA(v[i])) continue; if (!eh_classe(v[i])) return 0; classes++; }
    return classes > 0;
}
// guarda um candidato: a lista é um monte com o pior (maior nota) em cima; cheia, o novo melhor toma o lugar do pior
static unsigned long achados;  // todos os caminhos achados na procura (a lista guarda só os CPOOL melhores)
static void por_cam(cam_t *cams, int *nc, const cam_t *c) {
    int p; achados++;
    if (*nc < CPOOL) { p = (*nc)++; cams[p] = *c; while (p && cams[(p - 1) / 2].nota < cams[p].nota) { cam_t x = cams[p]; cams[p] = cams[(p - 1) / 2]; cams[(p - 1) / 2] = x; p = (p - 1) / 2; } return; }
    cams[0] = *c; p = 0;
    for (;;) {
        int l = 2 * p + 1, r = l + 1, m = p;
        if (l < *nc && cams[l].nota > cams[m].nota) m = l;
        if (r < *nc && cams[r].nota > cams[m].nota) m = r;
        if (m == p) break;
        cam_t x = cams[p]; cams[p] = cams[m]; cams[m] = x; p = m;
    }
}
static void ligar(const pt_t *pt, size_t n1, size_t nf, uint64_t t, int pai, uint32_t oext, int temext, int nivel, const no_t *no, cam_t *cams, int *nc) {
    for (int parte = 0; parte < 2; parte++) {  // libil2cpp com janela longa; as outras bibliotecas só DIST
    size_t ini = parte ? n1 : 0, fim = parte ? nf : n1; uint64_t w = parte ? DIST : DISTFIXA;
    uint64_t de = t >= w ? t - w : 0; size_t lo = ini, hi = fim;
    while (lo < hi) { size_t m = (lo + hi) / 2; if (pt[m].v < de) lo = m + 1; else hi = m; }
    for (size_t j = lo; j < fim && pt[j].v <= t; j++) {
        int k = achar_reg(pt[j].onde); if (k < 0 || !rg[k].fixa) continue;
        uint64_t off = pt[j].onde - base_lib(rg[k].lib);
        if (off >= 0x10000000ULL) continue;  // a mais de 256 MB do começo da biblioteca: não é parte dela
        // o salto longo da libil2cpp é tabela[índice da classe]: o tamanho dele não diz nada (é índice × 8): custo fixo,
        // nem barato (os saltos longos falsos passariam na frente) nem pelo tamanho (o caminho de tabela ia para o fim)
        uint32_t o = (uint32_t)(t - pt[j].v); unsigned soma = o > DIST ? 0x400 : o;
        if (temext) soma += oext;
        for (int x = pai; no[x].pai >= 0; x = no[x].pai) soma += no[x].o;
        unsigned nt = nota(nivel, rg[k].lib, soma);
        // passa por uma ficha de classe do Unity e +0xb8 (os campos estáticos): é o jeito de o jogo guardar dados que duram.
        // Direto (variável da biblioteca → classe) ou pela tabela de classes (tabela[índice] → classe). Vai para a frente: as
        // variáveis do coletor de lixo da libil2cpp também apontam para o heap e geravam milhares de caminhos mais curtos
        uint64_t cls = o == 0xb8 ? pt[j].v : (o > DIST && temext && oext == 0xb8 && eh_tabela(t)) ? no[pai].t - 0xb8 : 0;
        if (cls && eh_classe(cls)) nt = nt > 0x1000 ? nt - 0x1000 : 0;
        // lista cheia: só entra se for melhor que o pior dela (antes a procura parava ao encher, e o certo podia nem ter aparecido)
        if (*nc == CPOOL && nt >= cams[0].nota) { achados++; continue; }
        cam_t c; c.nota = nt; int len = snprintf(c.txt, sizeof c.txt, "%s+%" PRIx64 ":%x", rg[k].lib, off, o);
        if (temext) len += snprintf(c.txt + len, sizeof c.txt - len, ":%x", oext);
        for (int x = pai; no[x].pai >= 0 && len < (int)sizeof c.txt; x = no[x].pai) len += snprintf(c.txt + len, sizeof c.txt - len, ":%x", no[x].o);
        por_cam(cams, nc, &c);
    }
    }
}
// quantos ponteiros cabem: um quarto da memória livre (MemAvailable), entre 12 e 100 milhões. Fixo em 12 milhões, num jogo
// grande o limite enchia antes de ler o fim do heap e o caminho que morava lá nunca era achado (o teste do jogo grande mostrou).
// O malloc só ocupa de verdade o que é usado. UNITD_MEM_MB: finge a memória livre (o teste usa a do celular do usuário)
static size_t limite_ponteiros(void) {
    unsigned long long kb = 0; char l[128]; const char *e = getenv("UNITD_MEM_MB");
    if (e) kb = strtoull(e, NULL, 10) * 1024;
    else { FILE *f = fopen("/proc/meminfo", "r"); while (f && fgets(l, sizeof l, f)) if (sscanf(l, "MemAvailable: %llu kB", &kb) == 1) break; if (f) fclose(f); }
    size_t n = (size_t)(kb * 1024 / 4 / sizeof(pt_t));
    return n < 12000000 ? 12000000 : n > 100000000 ? 100000000 : n;
}
static void cmd_ptrscan(const char *addr, const char *niv) {
    uint64_t alvo = strtoull(addr, NULL, 16); int nmax = niv[0] ? atoi(niv) : 8; if (nmax < 1 || nmax > 8) nmax = 8;
    ler_mapa(); if (!nrg) { puts("err não li o mapa de memória do jogo"); return; }
    // a região onde o valor mora sempre entra, mesmo com nome de região "lenta": senão o próprio caminho seria cortado
    int ka = achar_reg(alvo); const char *incluida = "";
    if (ka >= 0 && rg[ka].rw && !rg[ka].usa) { rg[ka].usa = 1; incluida = " (a região do valor estava fora e entrou)"; }
    size_t PMAX = limite_ponteiros(); pt_t *pt = malloc(PMAX * sizeof *pt); size_t np = 0;
    enum { BLOCO = 1 << 20 }; unsigned char *buf = malloc(BLOCO);
    // retrato parado: o jogo pausa só enquanto os ponteiros são lidos (uns segundos); montar os caminhos usa o retrato,
    // com o jogo já rodando. Assim um objeto criado/apagado no meio da leitura não deixa o retrato pela metade
    if (!pt || !buf) { free(pt); free(buf); puts("err sem memória para os ponteiros"); return; }
    kill(target, SIGSTOP);  // depois da conferência de memória: a saída de erro acima nunca deixa o jogo parado
    // as bibliotecas primeiro: se o limite encher no meio do heap, os ponteiros de onde os caminhos começam já estão lá
    // (antes o mapa era lido em ordem e o limite podia acabar antes das bibliotecas: 0 caminhos)
    int cheio = 0;
    // pt[0..n1): bibliotecas com salto longo (libil2cpp); pt[n1..nf): outras bibliotecas; o resto: heap
    size_t n1 = 0, nf = 0; uint64_t fora = 0, pulou = 0;
    char pmc[64]; snprintf(pmc, sizeof pmc, "/proc/%d/pagemap", target); int pmfd = open(pmc, O_RDONLY);
    for (int passo = 0; passo < 3; n1 = passo == 0 ? np : n1, nf = passo == 1 ? np : nf, passo++)
    for (int r = 0; r < nrg && np < PMAX; r++) {
        if (passo == 0 && rg[r].rw && !rg[r].usa) fora += rg[r].fim - rg[r].ini;
        if (!rg[r].usa || (passo == 0 ? !rg[r].longe : passo == 1 ? !rg[r].fixa || rg[r].longe : rg[r].fixa)) continue;
        for (uint64_t a = rg[r].ini; a < rg[r].fim && np < PMAX; a += BLOCO) {
            size_t n = rg[r].fim - a < BLOCO ? rg[r].fim - a : BLOCO;
            // pagemap (como o GameGuardian): página anônima nunca usada não está na memória nem no swap; é só zero, nem lê
            uint64_t pm[BLOCO / 4096]; size_t npg = (n + 4095) / 4096; int temmapa = 0;
            if (pmfd >= 0 && rg[r].anon && pread(pmfd, pm, npg * 8, (off_t)(a / 4096 * 8)) == (ssize_t)(npg * 8)) temmapa = 1;
            for (size_t pg = 0; pg < npg && np < PMAX;) {
                size_t fimpg = pg;  // um trecho de páginas seguidas: usadas (lê) ou nunca usadas (pula)
                int usada = !temmapa || (pm[pg] & (3ULL << 62));
                while (fimpg < npg && (!temmapa || !!(pm[fimpg] & (3ULL << 62)) == usada)) fimpg++;
                size_t ini = pg * 4096, tam = (fimpg * 4096 < n ? fimpg * 4096 : n) - ini; pg = fimpg;
                if (!usada) { pulou += tam; continue; }
                if (ler(a + ini, buf, tam)) continue;
                for (size_t o = 0; o + 8 <= tam && np < PMAX; o += 8) {
                    uint64_t v; memcpy(&v, buf + o, 8); v = SEM_MARCA(v);
                    if (v < 0x10000 || (v & 7)) continue;  // objeto começa alinhado: endereço ímpar é texto/byte, não objeto
                    int k = achar_reg(v); if (k < 0 || !rg[k].usa) continue;
                    pt[np].v = v; pt[np].onde = a + ini + o; np++;
                }
            }
        }
    }
    if (pmfd >= 0) close(pmfd);
    free(buf);
    kill(target, SIGCONT);
    cheio = np >= PMAX;
    qsort(pt, n1, sizeof *pt, cmp_pt); qsort(pt + n1, nf - n1, sizeof *pt, cmp_pt); qsort(pt + nf, np - nf, sizeof *pt, cmp_pt);
    no_t *no = malloc(NMAX * sizeof *no); int nn = 0, nc = 0;
    cam_t *cams = malloc(CPOOL * sizeof *cams); achados = 0;
    if (!no || !cams) { free(no); free(cams); free(pt); puts("err sem memória para os caminhos"); return; }
    // cada endereço entra uma vez só (pelo caminho mais curto): sem isso o mesmo ponteiro entrava por vários alvos vizinhos
    // e enchia o limite de passos nos primeiros níveis, e os caminhos mais fundos (os do Unity) nunca eram vistos
    uint64_t *visto = calloc(HCAP, sizeof *visto); uint32_t *vo = malloc(HCAP * sizeof *vo);
    if (!visto || !vo) { free(visto); free(vo); free(no); free(cams); free(pt); puts("err sem memória para os caminhos"); return; }
    // procura pelo menor custo (soma dos deslocamentos + PENA por nível), não por nível: campos de verdade têm deslocamento
    // pequeno. Objetos criados juntos ficam colados, e a procura por nível chegava neles por "atalhos" de deslocamento grande
    // (que quebram ao reabrir o jogo) e descartava o caminho de verdade, que tem mais níveis
    int *fila = malloc(NMAX * sizeof *fila), nf2 = 0;
    if (!fila) { free(visto); free(vo); free(no); free(cams); free(pt); puts("err sem memória para os caminhos"); return; }
    #define CUSTO(x) no[fila[x]].custo
    #define POR(x, i_) do { int _p = (x); fila[_p] = (i_); while (_p && CUSTO((_p - 1) / 2) > CUSTO(_p)) { int _q = (_p - 1) / 2, _t = fila[_q]; fila[_q] = fila[_p]; fila[_p] = _t; _p = _q; } } while (0)
    no[nn++] = (no_t){alvo, -1, 0, 0, 0}; fila[nf2++] = 0;
    int pornivel[9] = {0}, nvisto = 0;
    ligar(pt, n1, nf, alvo, 0, 0, 0, 1, no, cams, &nc);
    while (nf2) {
        int i = fila[0]; fila[0] = fila[--nf2];  // tira o mais barato
        for (int p = 0;;) { int l = 2 * p + 1, r = l + 1, m = p;
            if (l < nf2 && CUSTO(l) < CUSTO(m)) m = l;
            if (r < nf2 && CUSTO(r) < CUSTO(m)) m = r;
            if (m == p) break;
            int _t = fila[m]; fila[m] = fila[p]; fila[p] = _t; p = m;
        }
        int nivel = no[i].nivel + 1;  // nível dos que apontam para este
        if (nivel >= nmax) continue;  // o último nível vem do ligar(): só biblioteca
        uint64_t t = no[i].t, de = t >= DIST ? t - DIST : 0;
        if (i) {  // passo velho: o endereço já voltou por um caminho mais barato
            uint64_t h = (t >> 3) * 0x9E3779B97F4A7C15ULL >> (64 - 23);
            while (visto[h] && visto[h] != t) h = (h + 1) & (HCAP - 1);
            if (visto[h] && vo[h] < no[i].custo) continue;
        }
        size_t lo = nf, hi = np;  // no heap a janela é só DIST (antes 512 KB no heap inteiro: a procura levava 4 min)
        while (lo < hi) { size_t m = (lo + hi) / 2; if (pt[m].v < de) lo = m + 1; else hi = m; }
        size_t ub = lo; hi = np;  // 1º ponteiro com valor > t: anda para trás, do mais perto para o mais longe
        while (ub < hi) { size_t m = (ub + hi) / 2; if (pt[m].v <= t) ub = m + 1; else hi = m; }
        for (size_t j = ub; j-- > lo;) {
            uint32_t o = (uint32_t)(t - pt[j].v), c = no[i].custo + o + PENA;
            // tabela de vistos cheia (e os passos também): continua só ligando às bibliotecas. Antes a procura parava aqui, e
            // o passo de um campo longe (+d38) nunca era visto
            if (nvisto >= HCAP / 2) { ligar(pt, n1, nf, pt[j].onde, i, o, 1, nivel + 1, no, cams, &nc); continue; }
            uint64_t h = (pt[j].onde >> 3) * 0x9E3779B97F4A7C15ULL >> (64 - 23);
            while (visto[h] && visto[h] != pt[j].onde) h = (h + 1) & (HCAP - 1);
            if (visto[h] && vo[h] <= c) continue;  // já chegou aqui mais barato
            if (!visto[h]) nvisto++;
            visto[h] = pt[j].onde; vo[h] = c;
            ligar(pt, n1, nf, pt[j].onde, i, o, 1, nivel + 1, no, cams, &nc);  // uma biblioteca aponta para cá? vira caminho
            if (nn < NMAX) { no[nn] = (no_t){pt[j].onde, i, o, c, nivel}; POR(nf2, nn); nf2++; nn++; pornivel[nivel]++; }
        }
    }
    free(fila);
    #undef CUSTO
    #undef POR
    free(no); free(pt); free(visto); free(vo);
    qsort(cams, nc, sizeof *cams, cmp_cam);  // o mais confiável primeiro
    // saída: até CMAX, no máximo POR_LIB de cada biblioteca (variedade: se uma biblioteca for ruim, as outras ainda estão lá)
    int saiu = 0; char libs[64][64]; int quantos[64] = {0}, nl = 0;
    for (int i = 0; i < nc && saiu < CMAX; i++) {
        char lib[64]; snprintf(lib, sizeof lib, "%.*s", (int)strcspn(cams[i].txt, "+"), cams[i].txt);
        int k = 0; while (k < nl && strcmp(libs[k], lib)) k++;
        if (k == nl && nl < 64) { snprintf(libs[nl], 64, "%s", lib); nl++; }
        if (k < 64 && quantos[k] >= POR_LIB) continue;
        if (k < 64) quantos[k]++;
        puts(cams[i].txt); saiu++;
    }
    free(cams);
    // números da procura para o relatório (o app só usa as linhas com "+"): ponteiros lidos, se encheu, passos por nível
    printf("ok %d ponteiros=%zu/%zu%s fora=%" PRIu64 "MB pulou=%" PRIu64 "MB achados=%lu candidatos=%d passos=%d niveis=%d,%d,%d,%d,%d,%d,%d%s%s\n", saiu, np, PMAX, cheio ? "(cheio)" : "", fora >> 20, pulou >> 20, achados, nc, nn,
           pornivel[1], pornivel[2], pornivel[3], pornivel[4], pornivel[5], pornivel[6], pornivel[7], nn >= NMAX ? " (limite de passos)" : "", incluida);
}
// classe ENDEREÇO: de que objeto (classe do Unity) o valor faz parte. Todo objeto começa com o ponteiro para a ficha da classe:
// procura para trás (até 4 KB) o 1º que aponta para uma ficha. Linhas "texto OFF HEX" = os textos do objeto (a assinatura,
// para achar o mesmo objeto ao reabrir) -> ok CLASSE ESPAÇO CAMPO
static void cmd_classe(const char *addr) {
    uint64_t a = strtoull(addr, NULL, 16), base = a & ~7ULL; char nome[64], esp[64];
    ler_mapa();
    for (uint64_t off = 0; off <= 0x1000 && off <= base; off += 8) {
        uint64_t k; if (ler(base - off, &k, 8) || !nome_classe(k, nome, esp)) continue;
        if (!strcmp(nome, "String") && !strcmp(esp, "System")) break;  // caiu dentro de um texto: não é objeto do jogo
        uint64_t obj = base - off;
        for (uint64_t f = 0x10; f < 0x200; f += 8) {  // os campos que apontam para textos, até o começo do próximo objeto
            uint64_t p; char hex[130];
            if (ler(obj + f, &p, 8)) break;
            if (f > a - obj && eh_classe(p)) break;  // outro ponteiro para ficha depois do valor: é o próximo objeto
            if (texto_unity(p, hex, sizeof hex)) printf("texto %" PRIx64 " %s\n", f, hex);
        }
        printf("ok %s %s %" PRIx64 "\n", nome, esp, a - obj); return;
    }
    puts("err o valor não está dentro de um objeto do Unity");
}

// pelonome CLASSE ESPAÇO CAMPO [OFF=HEX,OFF=HEX...]: acha a ficha da classe pelas variáveis da libil2cpp (como os
// TypeInfo do il2cpp), depois todos os objetos dela (começam com o ponteiro para a ficha) e fica com os que têm os mesmos
// textos. Linhas: o endereço do valor (objeto + CAMPO) -> ok ACHADOS OBJETOS_DA_CLASSE
static void cmd_pelonome(const char *cls, const char *esp, const char *campo_ass) {
    char campo[32] = "", ass[1100] = ""; sscanf(campo_ass, "%31[^ ] %1099s", campo, ass);
    uint64_t campo_off = strtoull(campo, NULL, 16); char nome[64], e[64];
    ler_mapa(); if (!nrg) { puts("err não li o mapa de memória do jogo"); return; }
    enum { BLOCO = 1 << 20 }; uint64_t *buf = malloc(BLOCO); if (!buf) { puts("err sem memória"); return; }
    uint64_t klass = 0;
    for (int r = 0; r < nrg && !klass; r++) {  // 1) a ficha: alguma variável da biblioteca do jogo aponta para ela
        if (!rg[r].fixa) continue;
        for (uint64_t a = rg[r].ini; a < rg[r].fim && !klass; a += BLOCO) {
            size_t n = rg[r].fim - a < BLOCO ? rg[r].fim - a : BLOCO;
            if (ler(a, buf, n)) continue;
            for (size_t i = 0; i < n / 8 && !klass; i++) {
                uint64_t v = SEM_MARCA(buf[i]); if (v < 0x10000 || (v & 7)) continue;
                int k = achar_reg(v); if (k < 0 || !rg[k].rw) continue;
                if (nome_classe(v, nome, e) && !strcmp(nome, cls) && !strcmp(e, esp)) klass = v;
            }
        }
    }
    if (!klass) { free(buf); printf("err a classe %s não foi achada (o jogo ainda não usou ela?)\n", cls); return; }
    int achados = 0, objetos = 0;
    for (int r = 0; r < nrg; r++) {  // 2) os objetos: começam com o ponteiro para a ficha (com ou sem a marca 0xb4)
        if (!rg[r].usa || rg[r].fixa) continue;
        for (uint64_t a = rg[r].ini; a < rg[r].fim; a += BLOCO) {
            size_t n = rg[r].fim - a < BLOCO ? rg[r].fim - a : BLOCO;
            if (ler(a, buf, n)) continue;
            for (size_t i = 0; i < n / 8; i++) {
                if (SEM_MARCA(buf[i]) != klass) continue;
                uint64_t obj = a + i * 8; objetos++;
                int bate = 1; char tmp[1100]; snprintf(tmp, sizeof tmp, "%s", ass); char *sv = NULL;
                for (char *t = strtok_r(tmp, ",", &sv); t && bate; t = strtok_r(NULL, ",", &sv)) {  // 3) a assinatura
                    char *ig = strchr(t, '='); if (!ig) continue; *ig = 0;
                    uint64_t p; char hex[130];
                    bate = !ler(obj + strtoull(t, NULL, 16), &p, 8) && texto_unity(p, hex, sizeof hex) && !strcmp(hex, ig + 1);
                }
                if (bate) { printf("%" PRIx64 "\n", obj + campo_off); achados++; }
            }
        }
    }
    free(buf);
    printf("ok %d %d\n", achados, objetos);
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
        p = SEM_MARCA(p);
        // vazio (a estrutura ainda não existe, ex.: o jogo carregando) ou não é endereço (ex.: texto): não soma em cima
        if (p < 0x10000) { puts("err o caminho quebrou (vazio)"); return; }
        if (p > 0x0000ffffffffffffULL) { puts("err o caminho quebrou (não é endereço)"); return; }
        a = p + strtoull(x, NULL, 16);
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
    char line[2400], c[32], a1[1100], a2[128], a3[128], quarto[1100];  // a1 grande: o padrão do aob; quarto: a assinatura do pelonome
    while (fgets(line, sizeof line, stdin)) {
        c[0] = a1[0] = a2[0] = a3[0] = quarto[0] = 0;
        sscanf(line, "%31s %1099s %127s %127s %1099s", c, a1, a2, a3, quarto);
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
        else if (!strcmp(c, "classe")) cmd_classe(a1);
        else if (!strcmp(c, "pelonome")) { char ca[1200]; snprintf(ca, sizeof ca, "%s %s", a3, quarto); cmd_pelonome(a1, a2, ca); }
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
