// Faz o papel da libil2cpp de um jogo Unity: guarda na .bss o ponteiro para a "classe" (como os TypeInfo do il2cpp)
#include <stdlib.h>
#include <string.h>
void *volatile Recursos_TypeInfo;
char reserva[1 << 20];  // .bss grande: vira a região anônima colada na biblioteca, como no Android
static void *novo(size_t n) { void *p = calloc(1, n); return p; }
// classe +0xb8 → campos estáticos +0x10 → gerenciador +0x28 → lista +0x10 → itens +0x38 (item 3) → item +0x30 = cobre
float *montar(float valor, void **nos) {
    char *classe = novo(0x200), *estaticos = novo(0x40), *ger = novo(0x80), *lista = novo(0x40), *itens = novo(0x80), *item = novo(0x60);
    memcpy(classe + 0xb8, &estaticos, 8); memcpy(estaticos + 0x10, &ger, 8); memcpy(ger + 0x28, &lista, 8);
    memcpy(lista + 0x10, &itens, 8); memcpy(itens + 0x38, &item, 8);
    float *cobre = (float *)(item + 0x30); *cobre = valor;
    Recursos_TypeInfo = classe;
    nos[0] = classe; nos[1] = estaticos; nos[2] = ger; nos[3] = lista; nos[4] = itens; nos[5] = item;
    reserva[0] = 1;
    return cobre;
}
