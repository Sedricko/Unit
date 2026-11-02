// Faz o papel da libil2cpp de um jogo Unity. Na .bss: o ponteiro direto para uma "classe" (cenário A, como os TypeInfo) e o
// ponteiro para a tabela de classes (cenário B, como a s_TypeInfoTable: salto longo tabela[índice])
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
void *volatile Recursos_TypeInfo;
void *volatile s_TypeInfoTable;
void *volatile Raiz_Lixo;  // aponta para uma região que o motor deve ignorar (pilha de thread falsa)
void lixo_raiz(void *p) { Raiz_Lixo = p; }
char reserva[1 << 20];  // .bss grande: vira a região anônima colada na biblioteca, como no Android
// os objetos do caminho moram numa região própria, criada antes do heap grande: fica depois dele no mapa (o pior caso: é lida
// por último). Entre eles, outros objetos de tamanho que muda a cada abertura (semente): os "atalhos" de deslocamento grande
// mudam ao reabrir, os campos de verdade não
static char *arena; static size_t usado; static uint64_t sx;
// cenário C: o "jogo" põe a arena logo depois da .bss desta biblioteca (usar_arena). Sem nome, o Linux junta as duas
// numa linha só do mapa
void usar_arena(char *p) { arena = p; }
static void *novo(size_t n) {
    if (!arena) {  // + 1 página sem acesso no fim: a região fica separada das vizinhas (o teste dá nome a ela)
        // 4 MB: cabem as 8000 "outras classes" do cenário B (~1,7 MB). Com 256 KB elas passavam do fim e a tabela apontava
        // para memória que mudava a cada execução (o teste falhava às vezes)
        arena = mmap(NULL, (1 << 22) + 4096, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        mprotect(arena + (1 << 22), 4096, PROT_NONE);
    }
    void *p = arena + usado; usado += (n + 15) & ~(size_t)15;
    if (usado > (1 << 22) - 0x1000) abort();  // passou da arena: o teste está errado
    if (sx) { sx ^= sx << 13; sx ^= sx >> 7; sx ^= sx << 17; usado += 16 * (sx % 24); }  // outros objetos criados junto
    return p;
}
static void liga(char *de, size_t campo, void *para, int marca) {  // marca: o Android põe 0xb4 no byte de cima dos ponteiros
    uint64_t v = (uint64_t)para | (marca ? 0xb4ULL << 56 : 0); memcpy(de + campo, &v, 8);
}
// A: classe +b8 → estáticos +10 → gerenciador +d38 (objeto grande) → lista +10 → itens +38 → item +30 = cobre (6 níveis)
// B: tabela +8ca0 (item 4500) → classe +b8 → estáticos +18 → singleton +40 → lista +10 → vetor +48 → item +34 = cobre
//    (7 níveis, ponteiros com marca 0xb4, campo +34 que não é múltiplo de 8)
void *arena_base(void) { return arena; }
float *montar(char cenario, unsigned semente, float valor, void **nos) {
    sx = semente; usado = 16 * (semente % 64);
    if (cenario == 'a' || cenario == 'c' || cenario == 'd') {
        char *classe = novo(0x200), *estaticos = novo(0x40), *ger = novo(0x1000), *lista = novo(0x40), *itens = novo(0x80), *item = novo(0x60);
        liga(classe, 0xb8, estaticos, 0); liga(estaticos, 0x10, ger, 0); liga(ger, 0xd38, lista, 0);
        liga(lista, 0x10, itens, 0); liga(itens, 0x38, item, 0);
        liga(classe, 0x10, (void *)"Recursos", 0); liga(classe, 0x18, (void *)"", 0);  // a ficha da classe: nome e espaço de nomes
        Recursos_TypeInfo = classe;
        nos[0] = classe; nos[1] = estaticos; nos[2] = ger; nos[3] = lista; nos[4] = itens; nos[5] = item;
        float *cobre = (float *)(item + 0x30); *cobre = valor; reserva[0] = 1; return cobre;
    }
    char *tabela = calloc(8000, 8), *classe = novo(0x130), *estaticos = novo(0x60), *sing = novo(0x80), *lista = novo(0x40), *vetor = novo(0x100), *item = novo(0x50);
    // as outras classes da tabela: fichas com nome em +0x10 (como as de verdade), que apontam umas para as outras (+0x8) e
    // para os objetos do caminho (+0x20). Um em cada dez lugares da tabela fica vazio (classe ainda não usada)
    char *outras[8000];
    for (int k = 0; k < 8000; k++) {
        outras[k] = novo(0x30); liga(outras[k], 0x10, (void *)"Outra", 1);
        if (k != 4500 && k % 10) liga(tabela, k * 8, outras[k], 1);
    }
    liga(tabela, 4500 * 8, classe, 1); liga(classe, 0xb8, estaticos, 1); liga(estaticos, 0x18, sing, 1);
    liga(sing, 0x40, lista, 1); liga(lista, 0x10, vetor, 1); liga(vetor, 0x48, item, 1);
    liga(classe, 0x10, (void *)"Inventario", 1); liga(classe, 0x18, (void *)"Jogo", 1);  // a ficha da classe: nome e espaço de nomes
    void *passos[6] = {classe, estaticos, sing, lista, vetor, item};
    for (int k = 0; k < 8000; k++) {
        sx ^= sx << 13; sx ^= sx >> 7; sx ^= sx << 17;
        liga(outras[k], 0x8, outras[sx % 8000], 1); liga(outras[k], 0x20, passos[(sx >> 20) % 6], 1);
    }
    s_TypeInfoTable = tabela;
    nos[0] = classe; nos[1] = estaticos; nos[2] = sing; nos[3] = lista; nos[4] = vetor; nos[5] = item;
    float *cobre = (float *)(item + 0x34); *cobre = valor; reserva[0] = 1; return cobre;
}
