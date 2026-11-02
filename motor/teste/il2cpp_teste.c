// Faz o papel da libil2cpp de um jogo Unity. Na .bss: o ponteiro direto para uma "classe" (cenário A, como os TypeInfo) e o
// ponteiro para a tabela de classes (cenário B, como a s_TypeInfoTable: salto longo tabela[índice])
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
void *volatile Recursos_TypeInfo;
void *volatile s_TypeInfoTable;
void *volatile ItemRecurso_TypeInfo;  // como no il2cpp: a variável da biblioteca que aponta para a ficha da classe
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
        // 16 MB: cabem as 8000 "outras classes" do cenário B (~5 MB). Com 256 KB elas passavam do fim e a tabela apontava
        // para memória que mudava a cada execução (o teste falhava às vezes)
        arena = mmap(NULL, (1 << 24) + 4096, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        mprotect(arena + (1 << 24), 4096, PROT_NONE);
    }
    void *p = arena + usado; usado += (n + 15) & ~(size_t)15;
    if (usado > (1 << 24) - 0x1000) abort();  // passou da arena: o teste está errado
    if (sx) { sx ^= sx << 13; sx ^= sx >> 7; sx ^= sx << 17; usado += 16 * (sx % 24); }  // outros objetos criados junto
    return p;
}
static void liga(char *de, size_t campo, void *para, int marca) {  // marca: o Android põe 0xb4 no byte de cima dos ponteiros
    uint64_t v = (uint64_t)para | (marca ? 0xb4ULL << 56 : 0); memcpy(de + campo, &v, 8);
}
// objetos no formato do Unity: a ficha da classe (nome em +0x10, espaço de nomes em +0x18), textos System.String
// (cabeçalho, tamanho em +0x10, UTF-16 em +0x14) e itens que começam com o ponteiro para a ficha deles
static char *ficha(const char *nome, const char *esp, int marca) {
    char *c = novo(0x130); liga(c, 0x10, (void *)nome, marca); liga(c, 0x18, (void *)esp, marca); return c;
}
static char *cls_texto;
static char *texto(const char *t, int marca) {
    int n = (int)strlen(t); char *o = novo(0x14 + 2 * (size_t)n + 2);
    liga(o, 0, cls_texto, marca); memcpy(o + 0x10, &n, 4);
    for (int i = 0; i < n; i++) { uint16_t u = (uint8_t)t[i]; memcpy(o + 0x14 + 2 * i, &u, 2); }
    return o;
}
// o item do cobre e dois outros da mesma classe com O MESMO valor (51): só a assinatura (o nome) separa um do outro
// sem_texto 1 (cenário E): nenhum texto; só números fixos: o código do item em +0x10 (3, 7, 11) e um igual em +0x14.
// sem_texto 2 (cenário F, como o "Item" do Orecraft: só ponteiros e o valor): +0x10 aponta para a definição com o nome
static char *itens_unity(size_t tam, size_t campo, float valor, int marca, int sem_texto) {
    cls_texto = ficha("String", "System", marca);
    // sem_texto (como o Orecraft): o item também não tem variável na biblioteca; só dá para achar a ficha pelo texto do nome
    char *cls_item = ficha("ItemRecurso", "Jogo", marca); if (!sem_texto) ItemRecurso_TypeInfo = cls_item;
    char *cls_def = ficha("DefItem", "Jogo", marca);
    const char *nomes[3] = {"Ferro", "Cobre", "Ouro"}; char *cobre = NULL;
    for (int k = 0; k < 3; k++) {
        char *it = novo(tam); liga(it, 0, cls_item, marca);
        if (sem_texto == 1) { int id = 3 + 4 * k, um = 1; memcpy(it + 0x10, &id, 4); memcpy(it + 0x14, &um, 4); }
        else if (sem_texto == 2) {  // F: só um ponteiro para a definição do item (outra classe), que tem o nome
            char *def = novo(0x30); liga(def, 0, cls_def, marca); liga(def, 0x10, texto(nomes[k], marca), marca);
            liga(it, 0x10, def, marca);
        }
        else liga(it, 0x10, texto(nomes[k], marca), marca);
        memcpy(it + campo, &valor, 4); if (k == 1) cobre = it;
    }
    return cobre;
}
// A: classe +b8 → estáticos +10 → gerenciador +d38 (objeto grande) → lista +10 → itens +38 → item +30 = cobre (6 níveis)
// B: tabela +8ca0 (item 4500) → classe +b8 → estáticos +18 → singleton +40 → lista +10 → vetor +48 → item +34 = cobre
//    (7 níveis, ponteiros com marca 0xb4, campo +34 que não é múltiplo de 8)
void *arena_base(void) { return arena; }
float *montar(char cenario, unsigned semente, float valor, void **nos) {
    sx = semente; usado = 16 * (semente % 64);
    if (cenario == 'a' || cenario == 'c' || cenario == 'd' || cenario == 'e' || cenario == 'f') {
        char *classe = novo(0x200), *estaticos = novo(0x40), *ger = novo(0x1000), *lista = novo(0x40), *itens = novo(0x80), *item = cenario == 'e' ? itens_unity(0x40, 0x18, valor, 0, 1) : cenario == 'f' ? itens_unity(0x40, 0x18, valor, 0, 2) : itens_unity(0x60, 0x30, valor, 0, 0);
        liga(classe, 0xb8, estaticos, 0); liga(estaticos, 0x10, ger, 0); liga(ger, 0xd38, lista, 0);
        liga(lista, 0x10, itens, 0); liga(itens, 0x38, item, 0);
        liga(classe, 0x10, (void *)"Recursos", 0); liga(classe, 0x18, (void *)"", 0);  // a ficha da classe: nome e espaço de nomes
        Recursos_TypeInfo = classe;
        nos[0] = classe; nos[1] = estaticos; nos[2] = ger; nos[3] = lista; nos[4] = itens; nos[5] = item;
        float *cobre = (float *)(item + (cenario == 'e' || cenario == 'f' ? 0x18 : 0x30)); *cobre = valor; reserva[0] = 1; return cobre;
    }
    char *tabela = calloc(8000, 8), *classe = novo(0x130), *estaticos = novo(0x60), *sing = novo(0x80), *lista = novo(0x40), *vetor = novo(0x100), *item = itens_unity(0x50, 0x34, valor, 1, 0);
    // as outras classes da tabela: fichas de tamanho real (0x130) com nome. Um quarto delas tem campos estáticos (+0xb8)
    // que apontam para objetos do jogo; os objetos apontam uns para os outros (+0x8) e para os do caminho (+0x20).
    // Um em cada dez lugares da tabela fica vazio (classe ainda não usada)
    char *outras[8000], *objs[4000];
    for (int k = 0; k < 4000; k++) objs[k] = novo(0x30);
    for (int k = 0; k < 8000; k++) {
        outras[k] = ficha("Outra", "Jogo", 1);
        if (k % 4 == 0) { char *est = novo(0x20); liga(outras[k], 0xb8, est, 1); liga(est, 0x8, objs[(k / 4) % 4000], 1); }
        if (k != 4500 && k % 10) liga(tabela, k * 8, outras[k], 1);
    }
    liga(tabela, 4500 * 8, classe, 1); liga(classe, 0xb8, estaticos, 1); liga(estaticos, 0x18, sing, 1);
    liga(sing, 0x40, lista, 1); liga(lista, 0x10, vetor, 1); liga(vetor, 0x48, item, 1);
    liga(classe, 0x10, (void *)"Inventario", 1); liga(classe, 0x18, (void *)"Jogo", 1);  // a ficha da classe: nome e espaço de nomes
    void *passos[6] = {classe, estaticos, sing, lista, vetor, item};
    for (int k = 0; k < 4000; k++) {
        sx ^= sx << 13; sx ^= sx >> 7; sx ^= sx << 17;
        // 1 em cada 50 objetos aponta para um objeto do caminho (como num jogo: poucos usam o gerenciador); o resto, entre si
        liga(objs[k], 0x8, objs[sx % 4000], 1); liga(objs[k], 0x20, (sx >> 40) % 50 ? (void *)objs[(sx >> 20) % 4000] : passos[(sx >> 20) % 6], 1);
    }
    s_TypeInfoTable = tabela;
    nos[0] = classe; nos[1] = estaticos; nos[2] = sing; nos[3] = lista; nos[4] = vetor; nos[5] = item;
    float *cobre = (float *)(item + 0x34); *cobre = valor; reserva[0] = 1; return cobre;
}
