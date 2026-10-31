# Unit

Editor de memória para Android (como o GameGuardian), feito **do zero** e **para aprender**: cada peça é pequena, tem
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

## Planos
- [PLANO.md](PLANO.md): as fases do Unit, peça por peça.
- [JOGO.md](JOGO.md): a Mina de Treino e os níveis (cada nível é uma prova do Unit).

## Baixar
Cada mudança na `main` gera o APK sozinho (GitHub Actions) na página **Releases**: `Unit-<versão>.apk`.
A versão segue o plano: `0.0.2` = Fase 0, peça 0.2.

## Onde estamos
**Fase 0, peça 0.2:** compilação automática. O app ainda só mostra o nome e a versão.
