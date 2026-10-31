# Unit

Ferramenta **universal** para modificar jogos offline no Android **por completo** (memória, scripts, regras, save, tempo),
feita **do zero** e **para aprender**: cada peça é pequena, tem
teste e só seguimos para a próxima quando ela funciona.

## Regras (fixas)
1. Só jogos **offline**, de um jogador.
2. Nada que é pago com **dinheiro de verdade** (compras, VIP, premium, tirar anúncio pago).
3. Nunca ganhar a recompensa de **anúncio** sem ver o anúncio.

Todo o resto é aberto: moedas e recursos do jogo, vida, XP, tempo, velocidade, cronômetros, save, regras (patches).

## Pastas
| Pasta | O que é |
|---|---|
| [`motor/`](motor/) | O motor em C que roda como root e lê/escreve a memória do jogo |
| [`app/`](app/) | O app Android do Unit (a tela) |
| [`mina/`](mina/) | A **Mina de Treino**, o nosso jogo de teste |
| [`docs/`](docs/) | Anotações: o que aprendemos em cada jogo real |

## Como trabalhamos
- Uma peça por vez, cada uma com teste.
- **Design: prévia antes de compilar.** Toda mudança de tela é mostrada como imagem e aprovada antes de virar código.

## Planos
- [PLANO.md](PLANO.md): as fases do Unit, peça por peça.
- [JOGO.md](JOGO.md): a Mina de Treino e os níveis (cada nível é uma prova do Unit).
- [DESIGN.md](DESIGN.md): o design e o layout (bolinha flutuante, uma coisa por tela, só aparece o que funciona).

## Baixar
Cada mudança na `main` gera o APK sozinho (GitHub Actions) na página **Releases**: `Unit-<versão>.apk`.
A versão segue o plano: `0.0.2` = Fase 0, peça 0.2.

## Onde estamos
**v0.2.0:** o **balão sobre o jogo** em 3 tamanhos (balão "U", mini painel com os valores ao vivo, painel de vidro com
blocos), **teclado próprio**, **achar** (buscar e refinar, tipo automático), **editar** (atalhos 999, 9.999, +100, ×2),
**congelar** e **pausar o jogo**. Precisa de root (Magisk) e da permissão "Sobrepor a outros apps".
