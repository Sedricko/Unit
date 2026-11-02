// "Jogo" grande, calibrado pelo Orecraft: as bibliotecas depois do heap no mapa (como no Android), objetos de 256 bytes com
// 2 ponteiros (o dobro da densidade do Orecraft: 10,4 milhões de ponteiros em 2,4 GB), outros objetos apontando para cada
// passo do caminho, ponteiros para o meio dos objetos grandes e uma libunity apontando para o heap todo.
// Uso: alvo_grande CENÁRIO(a|b) SEMENTE [mexe]   (mexe: uma linha de execução troca ponteiros do heap sem parar, como o jogo)
// Escreve: pid endereço-do-cobre e os passos
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <unistd.h>
float *montar(char cenario, unsigned semente, float valor, void **nos);
void unity_por(int k, void *p);
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
    void *nos[6]; float *cobre = montar(cen, sem, 51.0f, nos);
    obj = mmap(NULL, (size_t)NOBJ * 256, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (obj == MAP_FAILED) return 1;
    for (size_t i = 0; i < NOBJ; i++) { obj[i][0] = (uint64_t)obj[sorte() % NOBJ]; obj[i][1] = (uint64_t)obj[sorte() % NOBJ]; obj[i][2] = sorte() >> 40; obj[i][3] = 0x42c80000; }  // 2 ponteiros, o resto números
    // outros objetos que apontam para cada passo do caminho (no começo do objeto, como o il2cpp)
    for (int n = 0; n < 6; n++) for (int k = 0; k < VIZ; k++) obj[sorte() % NOBJ][sorte() % 2] = (uint64_t)nos[n];
    // ponteiros para o meio do objeto grande (listas de memória livre, partes de vetores). No Orecraft, com o limite de 8
    // objetos vizinhos, isso fez a procura parar cedo e achar 0
    for (int k = 1; k < 32; k++) obj[sorte() % NOBJ][sorte() % 2] = (uint64_t)nos[2] + k * 0x60;
    // a "libunity": ponteiros do gerenciador de memória para o heap todo, alguns desalinhados e alguns perto do cobre
    for (int k = 0; k < (1 << 18); k++) unity_por(k, (char *)obj[sorte() % NOBJ] + (k % 7 == 0 ? 3 : 0));
    for (int k = 0; k < 64; k++) unity_por(k, (char *)cobre - 0x40 * k - (k & 1));
    if (argc > 3) { pthread_t t; pthread_create(&t, NULL, mexer, NULL); }
    printf("%d %lx", getpid(), (unsigned long)cobre); for (int n = 0; n < 6; n++) printf(" %lx", (unsigned long)nos[n]);  // os passos, para depurar
    printf("\n"); fflush(stdout);
    for (;;) sleep(1);
}
