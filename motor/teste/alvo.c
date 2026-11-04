// Um "jogo" mínimo para testar o motor: guarda valores conhecidos e espera. Escreve o pid e os endereços numa linha.
#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include <unistd.h>

volatile int32_t ouro = 123456789;
volatile float vida = 87.5f;
volatile int64_t xp = 9876543210LL;
volatile double tempo = 4321.25;
volatile float recarga = 0.0f;   // a busca por mudança mexe nela pelo motor
// como num jogo: o valor mora no heap, dentro de estruturas, e uma variável fixa (global) aponta para o começo de tudo
struct recursos { char antes[0x8]; float cobre; };
struct inventario { char antes[0x40]; struct recursos *rec; };
struct inventario *volatile jogo;
// como no Unity: a biblioteca guarda uma tabela grande; o item 700 aponta para o objeto; o valor fica em +0x30 dele
struct objeto { char antes[0x30]; float ferro; };
void **volatile tabela;

int main(void) {
    jogo = calloc(1, sizeof *jogo); jogo->rec = calloc(1, sizeof *jogo->rec); jogo->rec->cobre = 777.25f;
    tabela = calloc(1000, sizeof *tabela); struct objeto *ob = calloc(1, sizeof *ob); ob->ferro = 4321.5f; tabela[700] = ob;
    printf("%d %lx %lx %lx %lx %lx %lx %lx\n", getpid(), (unsigned long)&ouro, (unsigned long)&vida, (unsigned long)&xp, (unsigned long)&tempo,
           (unsigned long)&recarga, (unsigned long)&jogo->rec->cobre, (unsigned long)&ob->ferro);
    fflush(stdout);
    for (;;) sleep(1);
}
