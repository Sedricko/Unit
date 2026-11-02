// "Jogo" grande, parecido com o Orecraft: mais ponteiros no heap que o limite do motor (12 milhões), as bibliotecas
// depois do heap no mapa (como no Android), e o valor 6 níveis abaixo da libil2cpp. O heap é feito de objetos de 256 bytes
// (2 ponteiros e números, como num jogo) que apontam uns para os outros, e cada passo do caminho também é apontado por outros objetos (como num jogo de verdade).
// Escreve: pid endereço-do-cobre
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <unistd.h>
float *montar(float valor, void **nos);
void unity_por(int k, void *p);
enum { NOBJ = 8 << 20, VIZ = 32 };  // 8 milhões de objetos de 256 bytes (2 GB) × 2 ponteiros = 16 milhões: 1 a cada 128 bytes,
// o dobro da densidade do Orecraft (relatório: 10,4 milhões de ponteiros em 2,4 GB, 1 a cada 230 bytes)
static uint64_t x = 88172645463325252ULL;
static uint64_t sorte(void) { x ^= x << 13; x ^= x >> 7; x ^= x << 17; return x; }
int main(void) {
    void *nos[6]; float *cobre = montar(51.0f, nos);
    uint64_t (*obj)[32] = mmap(NULL, (size_t)NOBJ * 256, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (obj == MAP_FAILED) return 1;
    for (size_t i = 0; i < NOBJ; i++) { obj[i][0] = (uint64_t)obj[sorte() % NOBJ]; obj[i][1] = (uint64_t)obj[sorte() % NOBJ]; obj[i][2] = sorte() >> 40; obj[i][3] = 0x42c80000; }  // 2 ponteiros, o resto números
    // outros objetos que apontam para cada passo do caminho e para o item do cobre (sempre no começo do objeto)
    for (int n = 0; n < 6; n++) for (int k = 0; k < VIZ; k++) obj[sorte() % NOBJ][sorte() % 2] = (uint64_t)nos[n];  // no começo do objeto, como o il2cpp
    // ponteiros para o meio do gerenciador (listas de memória livre, partes de vetores): muitos "objetos" entre o começo
    // dele e o campo +d38. No Orecraft isso fez a procura parar cedo (só seguia os 8 objetos mais perto) e achar 0
    for (int k = 1; k < 32; k++) obj[sorte() % NOBJ][sorte() % 2] = (uint64_t)nos[2] + k * 0x60;
    // a "libunity": ponteiros do gerenciador de memória para o heap todo, alguns desalinhados e alguns perto do cobre
    for (int k = 0; k < (1 << 18); k++) unity_por(k, (char *)obj[sorte() % NOBJ] + (k % 7 == 0 ? 3 : 0));
    for (int k = 0; k < 64; k++) unity_por(k, (char *)cobre - 0x40 * k - (k & 1));
    printf("%d %lx", getpid(), (unsigned long)cobre); for (int n = 0; n < 6; n++) printf(" %lx", (unsigned long)nos[n]);  // os passos, para depurar
    printf("\n"); fflush(stdout);
    for (;;) sleep(1);
}
