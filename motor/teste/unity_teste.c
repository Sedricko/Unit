// Faz o papel da libunity: na .bss, o gerenciador de memória do Unity guarda ponteiros para o heap todo (não são dados do jogo).
// No Orecraft, com salto longo saindo daqui, qualquer endereço virava "caminho" (1500 caminhos, todos quebraram ao reabrir)
static void *volatile memoria[1 << 18];
void unity_por(int k, void *p) { memoria[k] = p; }  // por função: uma variável exportada seria copiada para o programa
