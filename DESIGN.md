# Unit: design e layout

## O que aprendemos com o Tinker (e não repetimos)
- **Muitas abas e botões** (5 abas, dezenas de ferramentas): quem usa não sabe por onde começar.
- **Coisas pela metade na tela**: botão que existe mas não funciona tira a confiança no resto.
- **Texto demais** explicando cada botão.
- **O app cobria o jogo**: para mudar um número era preciso sair do jogo.

## A pegada do GameGuardian, mais organizada
Quem já usou o GG se sente em casa: **ícone flutuante** sobre o jogo, **barra de ícones** no topo do painel, **lista de
resultados** com caixinhas, **lista salva** e **memória** (endereços próximos). O que muda é a organização:

| No GameGuardian | No Unit |
|---|---|
| Barra com muitos ícones e menus escondidos | **4 ícones fixos**: 🎮 Jogo · 🔍 Busca · 📋 Salvos · 🧠 Memória (e ⚙️ no canto) |
| Escolher o tipo antes de buscar (Dword, Float...) | **Tipo automático** (dá para fixar se quiser) |
| Busca, refino e "busca nova" em botões separados | **Um botão** que busca e depois refina |
| Ferramentas espalhadas pelos menus | 🧰 **Ferramentas** dentro de 🔍 Busca, só as que servem para o jogo |
| Scripts num menu à parte | ▶️ **Scripts** no ícone de cada jogo (🎮) |
| Muitas opções por resultado | Toque longo no resultado: **Mudar · Congelar · Salvar · Ver na memória** |

```
 (U) ← ícone flutuante sobre o jogo
┌──────────────────────────────────┐
│ 🎮  🔍  📋  🧠              ⚙️ ✕ │  ← barra fixa (como no GG)
├──────────────────────────────────┤
│ Orecraft · Unity                 │
│ [ 250                         ]  │
│ [            Refinar           ] │
│ 2.797 resultados                 │
│ ☐ 250   Dword  0x7f5a…e05c   A   │
│ ☐ 250   Dword  0x7f5b…3c10   A   │
│ ☐ 250   Float  0x7f5b…91a4   Ca  │
│ [Mudar] [Congelar] [Salvar]      │  ← só com algo marcado
└──────────────────────────────────┘
```

## Princípios do Unit
1. **O jogo primeiro.** O Unit vive numa **bolinha flutuante** sobre o jogo; a tela cheia é só para configurar.
2. **Uma coisa por tela, um campo por tela.** Cada tela tem um objetivo, no máximo um campo para digitar e um botão
   principal.
3. **Só aparece o que funciona.** Uma função entra na tela quando a peça dela passou nas 3 provas.
4. **O Unit se adapta ao jogo.** Ao escolher o jogo, ele reconhece o motor (Unity, Unreal, Lua, nativo) e mostra só as
   ferramentas que servem para ele.
5. **Números grandes, pouco texto.** O que importa (o valor, quantos resultados) é o maior da tela.
6. **Escuro e calmo.** Fundo escuro (não briga com o jogo), uma cor de destaque, verde = deu certo, vermelho = falhou.

## Cores e tipografia
| Uso | Cor |
|---|---|
| Fundo | `#0E1220` (azul quase preto) |
| Cartão | `#171D30` |
| Texto | `#E8ECF7` · secundário `#8E98B3` |
| Destaque (ação principal) | `#4FD1C5` (verde-água) |
| Deu certo | `#3CCF7A` · Atenção `#FFC24A` · Falhou `#FF5D6C` |

Fonte do sistema (Roboto). Tamanhos: título 22, número em destaque 28, texto 15, detalhe 13.

## Estrutura
```
Ícone flutuante (sobre o jogo)
   └─ toque → Painel (como o do GG), barra fixa com 4 ícones:
         ├─ 🎮 Jogo     escolher o jogo, ver o motor; ▶️ Scripts do jogo
         ├─ 🔍 Busca    buscar/refinar, resultados; 🧰 Ferramentas do motor
         ├─ 📋 Salvos   a lista salva (mudar, congelar, nomes)
         └─ 🧠 Memória  os endereços em volta (como o editor de memória do GG)
       ⚙️ Ajustes no canto
```

## Telas

### 1. Escolher o jogo (tela cheia)
```
┌──────────────────────────────┐
│ Unit                    ⚙️    │
│                              │
│ Jogos abertos                │
│ ┌──────────────────────────┐ │
│ │ 🟢 Orecraft      Unity    │ │
│ │ 🟢 Mina de Treino Nativo  │ │
│ └──────────────────────────┘ │
│ Outros instalados  ▸         │
│                              │
│ [ Abrir a bolinha ]          │
└──────────────────────────────┘
```
O motor aparece ao lado do nome (Unity, Unreal, Lua, Nativo): o Unit descobre sozinho.

### 2. Buscar (painel flutuante)
**Um campo e um botão.** O tipo (inteiro, com vírgula, texto...) o Unit descobre sozinho. O mesmo botão faz **Buscar** na
primeira vez e vira **Refinar** depois; "começar de novo" é um link pequeno.
```
┌──────────────────────────────┐
│ Orecraft               ─  ✕  │
├──────────────────────────────┤
│ Número na tela               │
│ ┌──────────────────────────┐ │
│ │ 250                      │ │
│ └──────────────────────────┘ │
│ [         Refinar          ] │
│                              │
│        2.797 resultados       │
│  mude o valor no jogo e       │
│  digite o novo                │
│                começar de novo │
└──────────────────────────────┘
```
Com 1 resultado, a tela vira **o valor grande** com **Mudar** e **Congelar**.

### 3. Salvos
```
┌──────────────────────────────┐
│ 📌 Salvos (3)                 │
│ ┌──────────────────────────┐ │
│ │ Ouro          1.250  ❄️  │ │
│ │ Vida           87,5      │ │
│ │ Joias (texto)    80      │ │
│ └──────────────────────────┘ │
│ toque = mudar · ❄️ = congelado │
└──────────────────────────────┘
```
Cada salvo tem nome; o Unit acha o endereço de novo sozinho ao reabrir o jogo (Fase 3).

### 4. Ferramentas (só as do motor do jogo)
| Motor | Ferramentas |
|---|---|
| Todos | Cronômetros, Velocidade |
| Unity | Regras pelo nome (patches), Texto JSON |
| Unreal | Objetos pela tabela |
| Lua | Tabelas do Lua |

Cada ferramenta é um cartão com um botão principal; o resultado aparece no próprio cartão.

### 5. Scripts (Fase 4)
Lista de scripts `.lua`, **Rodar**, e as perguntas do script aparecem no próprio painel, como no GameGuardian.

## Componentes (iguais em todo o app)
- **Cartão**: título, uma linha de explicação curta, um botão principal.
- **Botão principal** (verde-água) e **secundário** (contorno). No máximo 2 por cartão.
- **Linha de valor**: nome à esquerda, número grande à direita, ícone de estado (❄️ congelado, ✅ provado).
- **Aviso**: barra fina em cima do painel (verde, amarelo ou vermelho), some sozinha.

## Quando cada tela nasce
| Tela | Peça |
|---|---|
| Escolher o jogo | 1.2 |
| Buscar | 2.1 – 2.4 |
| Salvos | 2.5 |
| Ícone flutuante + painel com a barra | 2.6 |
| Memória (endereços em volta) | 2.6 |
| Ferramentas | 5.x e 6.x |
| Scripts | 4.x |
