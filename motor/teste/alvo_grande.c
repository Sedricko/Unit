// "Jogo" grande, calibrado pelo Orecraft: as bibliotecas depois do heap no mapa (como no Android), objetos de 256 bytes com
// 2 ponteiros (o dobro da densidade do Orecraft: 10,4 milhões de ponteiros em 2,4 GB), outros objetos apontando para cada
// passo do caminho, ponteiros para o meio dos objetos grandes e uma libunity apontando para o heap todo.
// Uso: alvo_grande CENÁRIO(a|b) SEMENTE [mexe]   (mexe: uma linha de execução troca ponteiros do heap sem parar, como o jogo)
// Escreve: pid endereço-do-cobre, os passos e as regiões arena, pilha falsa, linker falso e reserva
#define _GNU_SOURCE
#include <dlfcn.h>
#include <link.h>
#include <pthread.h>
#include <string.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <unistd.h>
// a libil2cpp é carregada com dlopen (no cenário C, numa área reservada, para a arena ficar logo depois da .bss dela)
static float *(*montar)(char cenario, unsigned semente, float valor, void **nos);
static void (*lixo_raiz)(void *p);
static void *(*arena_base)(void);
static void (*usar_arena)(char *p);
void unity_por(int k, void *p);
static uintptr_t fim_bss;  // fim da libil2cpp na memória (último LOAD), em páginas
static int achar_fim(struct dl_phdr_info *i, size_t t, void *nada) {
    (void)t; (void)nada;
    if (!strstr(i->dlpi_name, "libil2cpp.so")) return 0;
    for (int k = 0; k < i->dlpi_phnum; k++) if (i->dlpi_phdr[k].p_type == PT_LOAD) {
        uintptr_t f = i->dlpi_addr + i->dlpi_phdr[k].p_vaddr + i->dlpi_phdr[k].p_memsz; if (f > fim_bss) fim_bss = f;
    }
    fim_bss = (fim_bss + 4095) & ~(uintptr_t)4095; return 1;
}
static void carregar(char cen) {
    // cenário C: a arena tem que ficar logo depois da .bss. Se o lugar depois dela estiver ocupado, bloqueia esse lugar e
    // carrega a biblioteca de novo (o carregador escolhe outro), até 50 vezes
    char *bloqueio[50]; size_t tbloq[50]; int nb = 0; char *a = NULL; void *h = NULL;
    for (int tentativa = 0; tentativa < 50; tentativa++) {
        h = dlopen("libil2cpp.so", RTLD_NOW); if (!h) { fprintf(stderr, "%s\n", dlerror()); exit(3); }
        if (cen != 'c') break;
        fim_bss = 0; dl_iterate_phdr(achar_fim, NULL);
        a = mmap((void *)fim_bss, (1 << 18) + 4096, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
        if (a == (char *)fim_bss) break;
        if (a != MAP_FAILED) munmap(a, (1 << 18) + 4096);
        a = NULL;
        // o carregador põe a biblioteca encostada logo abaixo do bloqueio anterior: o que vem depois da .bss é um bloqueio
        // nosso. Troca o começo dele pela arena (MAP_FIXED numa área que é nossa)
        int k = 0; while (k < nb && (uintptr_t)bloqueio[k] != fim_bss) k++;
        if (k < nb && tbloq[k] > (1 << 18) + 4096) {
            a = mmap((void *)fim_bss, (1 << 18) + 4096, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0);
            bloqueio[k] += (1 << 18) + 4096; tbloq[k] -= (1 << 18) + 4096;  // o resto do bloqueio sai no fim
            break;
        }
        Dl_info di; void *sym = dlsym(h, "montar"); dladdr(sym, &di);  // a base da biblioteca, para bloquear o lugar dela
        char *base = di.dli_fbase; size_t span = fim_bss - (uintptr_t)base;
        dlclose(h);
        bloqueio[nb] = mmap(base, span, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE | MAP_NORESERVE, -1, 0);
        tbloq[nb] = span; if (bloqueio[nb] != MAP_FAILED) nb++;
    }
    for (int k = 0; k < nb; k++) munmap(bloqueio[k], tbloq[k]);
    if (cen == 'c' && !a) { fprintf(stderr, "não deu para pôr a arena depois da .bss\n"); exit(4); }
    montar = (float *(*)(char, unsigned, float, void **))dlsym(h, "montar");
    lixo_raiz = (void (*)(void *))dlsym(h, "lixo_raiz"); arena_base = (void *(*)(void))dlsym(h, "arena_base");
    usar_arena = (void (*)(char *))dlsym(h, "usar_arena");
    if (a) { mprotect(a + (1 << 18), 4096, PROT_NONE); usar_arena(a); }
}
// região separada das vizinhas (1 página sem acesso no fim): o teste dá a ela um nome do Android
static char *regiao(size_t n, int noreserve) {
    char *p = mmap(NULL, n + 4096, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | (noreserve ? MAP_NORESERVE : 0), -1, 0);
    if (p == MAP_FAILED) exit(2);
    mprotect(p + n, 4096, PROT_NONE); return p;
}
enum { NOBJ = 8 << 20, VIZ = 32 };
static uint64_t x;
static uint64_t sorte(void) { x ^= x << 13; x ^= x >> 7; x ^= x << 17; return x; }
static uint64_t (*obj)[32];
static void *mexer(void *nada) {  // o jogo rodando: troca ponteiros do heap o tempo todo (o motor pausa o jogo enquanto lê)
    uint64_t y = 0x9E3779B97F4A7C15ULL;
    for (;;) for (int k = 0; k < 100000; k++) { y ^= y << 13; y ^= y >> 7; y ^= y << 17; obj[y % NOBJ][y >> 63] = (uint64_t)obj[(y >> 20) % NOBJ]; }
    return nada;
}
int main(int argc, char **argv) {
    char cen = argc > 1 ? argv[1][0] : 'a'; unsigned sem = argc > 2 ? (unsigned)atoi(argv[2]) : 1;
    x = 88172645463325252ULL ^ ((uint64_t)sem * 0x9E3779B97F4A7C15ULL);
    carregar(cen);
    void *nos[6]; float *cobre = montar(cen, sem, 51.0f, nos);
    obj = mmap(NULL, (size_t)NOBJ * 256, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (obj == MAP_FAILED) return 1;
    for (size_t i = 0; i < NOBJ; i++) { obj[i][0] = (uint64_t)obj[sorte() % NOBJ]; obj[i][1] = (uint64_t)obj[sorte() % NOBJ]; obj[i][2] = sorte() >> 40; obj[i][3] = 0x42c80000; }  // 2 ponteiros, o resto números
    // outros objetos que apontam para cada passo do caminho (no começo do objeto, como o il2cpp)
    for (int n = 0; n < 6; n++) for (int k = 0; k < VIZ; k++) obj[sorte() % NOBJ][sorte() % 2] = (uint64_t)nos[n];
    // cenário D: o gerenciador do caminho é um objeto popular, apontado por 5000 objetos do heap (como um gerenciador de
    // verdade, que todo mundo usa). O ponteiro verdadeiro (dos campos estáticos) é só um entre 5001
    if (cen == 'd') for (int k = 0; k < 5000; k++) obj[sorte() % NOBJ][sorte() % 2] = (uint64_t)nos[2];
    // cenário E: 200 mil objetos da classe do item (o cabeçalho de cada um aponta para a ficha), como num jogo cheio de itens.
    // No Orecraft isso afogou a procura (8 minutos): o motor tem que pular esses cabeçalhos
    if (cen == 'e') { uint64_t cls; memcpy(&cls, nos[5], 8); for (int k = 0; k < 200000; k++) obj[k * 37 % NOBJ][4] = cls; }
    // ponteiros para o meio do objeto grande (listas de memória livre, partes de vetores). No Orecraft, com o limite de 8
    // objetos vizinhos, isso fez a procura parar cedo e achar 0
    for (int k = 1; k < 32; k++) obj[sorte() % NOBJ][sorte() % 2] = (uint64_t)nos[2] + k * 0x60;
    // a "libunity": ponteiros do gerenciador de memória para o heap todo, alguns desalinhados e alguns perto do cobre
    for (int k = 0; k < (1 << 18); k++) unity_por(k, (char *)obj[sorte() % NOBJ] + (k % 7 == 0 ? 3 : 0));
    for (int k = 0; k < 64; k++) unity_por(k, (char *)cobre - 0x40 * k - (k & 1));
    // regiões que o GameGuardian chama de "lentas" e o motor deve ignorar: pilha de thread e linker, cheias de ponteiros para
    // o cobre e para os passos do caminho, e uma raiz na libil2cpp apontando para a "pilha" (um atalho de 2 níveis, se lida)
    uint64_t *tls = (uint64_t *)regiao(64 << 20, 0), *lnk = (uint64_t *)regiao(16 << 20, 0);
    // como uma pilha de verdade: quase tudo números, um ponteiro a cada 512 bytes
    for (size_t k = 0; k < (64 << 20) / 8; k++) tls[k] = k % 64 ? k * 3 : (uint64_t)nos[sorte() % 5] + (sorte() % 8) * 8;  // o item só pelo atalho abaixo
    for (size_t k = 0; k < (16 << 20) / 8; k++) lnk[k] = (uint64_t)nos[sorte() % 6];
    tls[8] = (uint64_t)nos[5];  // pilha +0x40 → item; +0x30 (A) ou +0x34 (B) = cobre
    lixo_raiz(tls);
    // reserva enorme nunca usada (como as do Android e do Unity): só zeros, o motor deve pular sem ler (pagemap)
    char *res = regiao((size_t)16 << 30, 1);
    if (argc > 3) { pthread_t t; pthread_create(&t, NULL, mexer, NULL); }
    printf("%d %lx", getpid(), (unsigned long)cobre); for (int n = 0; n < 6; n++) printf(" %lx", (unsigned long)nos[n]);  // os passos, para depurar
    printf(" %lx %lx %lx %lx\n", (unsigned long)arena_base(), (unsigned long)tls, (unsigned long)lnk, (unsigned long)res);  // regiões que o teste renomeia
    fflush(stdout);
    for (;;) sleep(1);
}
