// "Jogo" grande, parecido com o Orecraft: mais ponteiros no heap que o limite do motor (12 milhões), as bibliotecas
// depois do heap no mapa (como no Android), e o valor 6 níveis abaixo da libil2cpp. O heap é feito de objetos de 64 bytes
// (2 ponteiros e números, como num jogo) que apontam uns para os outros, e cada passo do caminho também é apontado por outros objetos (como num jogo de verdade).
// Escreve: pid endereço-do-cobre
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <unistd.h>
float *montar(float valor, void **nos);
enum { NOBJ = 16 << 20, VIZ = 32 };  // 16 milhões de objetos de 64 bytes (1 GB) × 2 ponteiros = 32 milhões de ponteiros
static uint64_t x = 88172645463325252ULL;
static uint64_t sorte(void) { x ^= x << 13; x ^= x >> 7; x ^= x << 17; return x; }
int main(void) {
    void *nos[6]; float *cobre = montar(51.0f, nos);
    uint64_t (*obj)[8] = mmap(NULL, (size_t)NOBJ * 64, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (obj == MAP_FAILED) return 1;
    for (size_t i = 0; i < NOBJ; i++) { obj[i][0] = (uint64_t)obj[sorte() % NOBJ]; obj[i][1] = (uint64_t)obj[sorte() % NOBJ]; obj[i][2] = sorte() >> 40; obj[i][3] = 0x42c80000; }  // 2 ponteiros, o resto números
    // outros objetos que apontam para cada passo do caminho e para o item do cobre (sempre no começo do objeto)
    for (int n = 0; n < 6; n++) for (int k = 0; k < VIZ; k++) obj[sorte() % NOBJ][sorte() % 2] = (uint64_t)nos[n];  // no começo do objeto, como o il2cpp
    printf("%d %lx", getpid(), (unsigned long)cobre); for (int n = 0; n < 6; n++) printf(" %lx", (unsigned long)nos[n]);  // os passos, para depurar
    printf("\n"); fflush(stdout);
    for (;;) sleep(1);
}
