# Plano do jogo de teste: "Mina de Treino"

## Para que serve
Um jogo pequeno, nosso, feito para **treinar o Unit fase por fase**. Cada coisa que aprendemos no plano (busca, refino,
ponteiro, valor escondido, texto, cronômetro, velocidade, save, regra) tem um lugar no jogo onde dá para testar com
certeza, antes de tentar num jogo de verdade. Offline, sem anúncios, sem loja de dinheiro real.

## A ideia do jogo (simples)
Você é um minerador. Toca na rocha para ganhar **ouro**, compra **picaretas** melhores com ouro, espera a **forja** terminar
uma barra, e tem **vida** que cai quando um morcego aparece. Tudo cabe numa tela só, com números grandes e fáceis de ler.

```
┌─────────────────────────────┐
│ 💰 Ouro 120   ❤️ Vida 87.5   │
│ 💎 Gemas 3    ⛏️ Nível 2     │
│                             │
│        [ ROCHA ]  ← toque    │
│                             │
│ 🔥 Forja: 00:45 restante     │
│ 🛒 Picareta nova: 50 ouro    │
│ ⏩ Tempo do jogo: 1x          │
└─────────────────────────────┘
```

## Cada parte do jogo treina uma fase

| No jogo | Como fica guardado na memória | Treina | Fase |
|---|---|---|---|
| 💰 **Ouro** | Número inteiro simples (Dword) | Busca, refino, escrever, congelar | 1–2 |
| ❤️ **Vida** | Número com vírgula (Float) | Tipos de número, busca por faixa | 1–2 |
| ⛏️ **Nível** | Dentro de um objeto que muda de lugar a cada abertura | Ponteiros e assinatura | 3 |
| 💎 **Gemas** | Escondido: guardado com XOR | Valor cifrado | 3 |
| 🪨 **Minério** | Guardado como **texto** ("80") | Valor em texto (como o Orecraft) | 3 |
| 🔥 **Forja** | Hora de terminar (milissegundos) | Cronômetros | 6 |
| ⏩ **Tempo** | Um multiplicador de velocidade (1.0) | Velocidade do jogo | 6 |
| 🛒 **Preço da picareta** | Uma função `preco()` que devolve 50 | Regras (patch: preço 0) | 5 |
| 💾 **Save** | Arquivo JSON na pasta do app | Editar save com backup | 6 |

Opcional, mais para frente: uma versão do jogo em **Unity** (tela parecida), para treinar IL2CPP e o relógio do Unity com o
mesmo cenário.

## Níveis do jogo = provas do Unit
Cada nível só "passa" quando o Unit consegue fazer aquilo. O jogo mostra ✅ quando percebe a mudança:

| Nível | Desafio | O Unit precisa |
|---|---|---|
| 1 | Ter 999 de ouro | Buscar, refinar e escrever |
| 2 | Vida não cair por 30 s | Congelar um Float |
| 3 | Reabrir o jogo e ainda achar o nível | Ponteiro ou assinatura |
| 4 | Ter 50 gemas | Achar o valor cifrado |
| 5 | Ter 999 de minério | Mudar um número guardado como texto |
| 6 | Terminar a forja agora | Achar o cronômetro |
| 7 | Rocha em 2x | Achar a velocidade |
| 8 | Picareta de graça | Patch na função de preço |
| 9 | Começar com 5000 de ouro | Editar o save |

## Como construímos o jogo (peças)
| Peça | O que faz | Pronto quando |
|---|---|---|
| J1 | Tela com ouro e a rocha (tocar soma ouro) | Instala e o ouro sobe ao tocar |
| J2 | Vida (Float) e o morcego que tira vida | A vida cai com o tempo |
| J3 | Nível dentro de um objeto novo a cada abertura | O endereço muda ao reabrir |
| J4 | Gemas com XOR | Busca normal não acha as gemas |
| J5 | Minério como texto | Busca de número não acha o minério |
| J6 | Forja com hora de terminar | Conta regressiva na tela |
| J7 | Velocidade (multiplicador) | Mudar o valor acelera tudo |
| J8 | Função de preço da picareta | Loja usa `preco()` |
| J9 | Save em JSON | Fecha e abre e continua de onde parou |
| J10 | Tela de níveis com ✅ | Mostra quais provas o editor já passou |

Cada peça do jogo vem **logo antes** da fase do editor que ela treina (J1 e J2 antes da Fase 1, J3–J5 antes da Fase 3, e
assim por diante). Assim o jogo e o editor crescem juntos.

## E os jogos reais
A Mina de Treino não substitui os jogos reais: ela é o **primeiro** teste de cada peça. Depois, a mesma peça é provada num
**jogo offline de verdade** (Orecraft, DYSMANTLE e outros que você escolher). Ordem de cada peça:
1. funciona no teste automático (sem celular);
2. funciona na Mina de Treino (o nível mostra ✅);
3. funciona no jogo real (a prova da fase no PLANO.md).

## Ordem combinada com o plano principal
1. Fase 0 do editor (repositório, compilação) **+ J1**
2. Fase 1 e 2 (motor, busca) **+ J2** → níveis 1 e 2
3. **J3–J5** → Fase 3 → níveis 3, 4 e 5
4. Fase 4 (scripts Lua) usando os níveis 1–5
5. **J8** → Fase 5 → nível 8
6. **J6, J7, J9** → Fase 6 → níveis 6, 7 e 9
7. **J10** fecha: todos os níveis com ✅
