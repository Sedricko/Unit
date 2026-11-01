// Um "jogo" mínimo para testar o motor: guarda valores conhecidos e espera. Escreve o pid e os endereços numa linha.
#include <stdint.h>
#include <stdio.h>
#include <unistd.h>

volatile int32_t ouro = 123456789;
volatile float vida = 87.5f;
volatile int64_t xp = 9876543210LL;
volatile double tempo = 4321.25;
volatile float recarga = 0.0f;   // a busca por mudança mexe nela pelo motor

int main(void) {
    printf("%d %lx %lx %lx %lx %lx\n", getpid(), (unsigned long)&ouro, (unsigned long)&vida, (unsigned long)&xp, (unsigned long)&tempo,
           (unsigned long)&recarga);
    fflush(stdout);
    for (;;) sleep(1);
}
