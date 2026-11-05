---
name: unit
description: Continua o Unit (repositório Sedricko/Unit), editor de memória para Android feito do zero (motor root em C + app Kotlin/Compose com balão sobre o jogo). Use SEMPRE que o usuário falar de Unit, motor unitd, balão/painel sobre o jogo, Achar/Refinar, Salvos, congelar, caminhos (ponteiros), achar pelo nome (Unity/IL2CPP), busca por mudança, relatório do Unit, Orecraft, Obsidian Knight, Vampire's Fall 2, Grim Soul, ou mandar prints/relatórios do app.
---

# Unit — passagem de bastão (estado 0.4.15, 05/11/2026)

## Quem é o usuário e como trabalhar com ele
- Português do Brasil, frases curtas, sem jargão sem explicar. Ele quer **aprender**: explique o porquê.
- Testa no **Pixel 6 Pro com vPhoneOS** (Android 12, root) e manda **prints** e o **relatório do Unit**
  (no app: Opções → Relatório → Compartilhar; chega como `Relatório do Unit.txt`).
- Depois de cada versão publicada, mande o **link do APK** (ele pede "me envie a última versão").
- **Antes de mudar qualquer tela, mande uma prévia** e espere o ok. Visual: vidro azul-noite, simples, **sem ícones
  coloridos** (`DESIGN.md`).
- Planejamento em `PLANO.md` (fases e "Onde estamos"). Regras do projeto: as de `PLANO.md` → "Regras do projeto (fixas)".
- Seja honesto: diga o que não testou; não invente confirmação.
- Commits terminam com as linhas de atribuição que a sessão informar; nada de ids de modelo no código.

## Onde está tudo
- Repositório `Sedricko/Unit` (clone em `/home/user/unit`). **Cada push em `main` roda o CI** (`.github/workflows/build.yml`):
  testes do motor (`sudo motor/teste/motor_teste.sh`, ~8 min no GitHub), `./gradlew testDebugUnitTest assembleRelease` e
  publica a release `v<versionName>` com `Unit-<versão>.apk`.
  Link: `https://github.com/Sedricko/Unit/releases/download/vX.Y.Z/Unit-X.Y.Z.apk`.
- Versão em `app/build.gradle.kts` (`versionCode` / `versionName`). Último: **0.4.15 (código 124)**. Subir os dois a cada entrega.
- Dois pushes seguidos: o CI cancela o anterior (só o último publica).
- Nesta máquina não há Android SDK: o Kotlin só compila no CI. O motor compila e testa aqui (`bash motor/teste/motor_teste.sh`).
- Acompanhar a release sem `gh`: `curl -s https://api.github.com/repos/Sedricko/Unit/releases/tags/vX.Y.Z`
  (e `.../actions/runs?branch=main&per_page=1` para status/conclusion).

## Arquitetura
| Parte | Arquivo | O que faz |
|---|---|---|
| Motor root | `motor/unitd.c` | Protocolo de linhas pelo `su` (lista de comandos no topo do arquivo) |
| Testes do motor | `motor/teste/` | `alvo.c` (jogo mínimo), `alvo_grande.c` + `il2cpp_teste.c` (Unity falso, cenários A–I), `unity_teste.c`, `motor_teste.sh` (**133 OK**) |
| Ponte | `Motor.kt` | `cmd()` sincronizado; recusa `\n`/`\r` no comando |
| Núcleo | `Nucleo.kt` | jogo escolhido, salvos, busca (`buscarJa`), editar, congelar, pausa, reabrir, `rodar`/`semAviso` |
| Caminhos e nome | `Ponteiro.kt` | guardar/seguir caminhos, achar pelo nome (Unity), dúvida, `aplicar` (trava Mutex), importar/exportar |
| Tela | `Balao.kt` (balão/mini/painel: Início, Achar, Editar, Salvos, Jogos, Opções), `Pecas.kt` (componentes), `Mudanca.kt` (busca por mudança), `EmVolta.kt`, `Laboratorio.kt`, `Arrumar.kt` |
| Relatório | `Registro.kt` | registro (500 linhas), autoteste, relatório para compartilhar |
| Testes do app | `app/src/test/java/dev/unit/app/` | `PonteiroTest`, `TelaTest`, `EmVoltaTest`, `ArrumarTest`, `AtualizadorTest` |

### Motor: pontos importantes
- Tipos: `i32 f32 i64 f64` (Auto) e `i16 i8` (Word/Byte, **só quando pedidos**: `search N i16,i8`).
- Busca com número inteiro casa também o valor que a tela arredonda ou corta (425,8 aparece 425): `[n−0,5, n+1)`.
- Regiões (`busca_aqui`): todas as graváveis (faixas Jh, J, Ch, Ca, Cd, Cb, A, S, As, O); fora: só leitura, placa de vídeo
  (kgsl, mali, dri, dmabuf), `[vvar]`, fontes, Zygote/imagens do sistema. Página ilegível perde só 4 KB; `search` devolve
  `ok N[ cortado] naoleu=KB`. Teto de 16 milhões de resultados.
- `refine` sem nenhum que bata **mantém os de antes**: `ok 0 manteve N`.
- `info`: `ok REGIÕES MB KBMOTOR KBJOGO fora: so_leitura=MB video_zygote=MB`.
- `classe`: recusa objeto do espaço `System`/`System.*` e campo além de 0x400 (Grim Soul: "Int32" a +0x8a0).
- `pelonome`: fichas pela variável da libil2cpp e pelo texto do nome (até 16 fichas, cache); assinatura de textos,
  números e um nível de ponteiro (`F>G=HEX`).
- Congelador limpa tudo se o jogo fechou; sem memória, solta o jogo (SIGCONT) antes de sair.

### App: comportamento atual
- Achar: Buscar/Refinar; 1ª busca sem nada → procura sozinha em Word/Byte → depois procura perto (n−1~n+1) e diz o porquê.
  Refino que zera mostra os de antes com o número de agora. Até 20 resultados: mostra a **classe** de cada um, marca
  "cópia da tela" (`daTela`: UnityEngine.UI, TMPro, DG.Tweening, nomes com Slider/Bar/Tween/Text/Label/Image/View/
  Display/Animator/UI) e põe os do jogo primeiro. Atalho para a **Busca por mudança** (só 4 bytes: Dword/Float).
- Editar: depois de escrever, relê em 1,5 s; se o jogo escreveu por cima, avisa (cópia) e procura o número de agora em Word/Byte.
- Ao reabrir o jogo: procura os salvos em segundo plano (não apaga os botões), uma vez ao abrir e outra aos 20 s; o que
  não achar fica parado com "Não achei: … [Procurar de novo] [Digitar o número]".
- Trocar o objeto de um salvo solta o congelado antigo (`trocarSalvo`).
- Identificação Unity guardada antes da 0.4.1 com `System` ou campo > 0x400 é descartada ao ler (`unityBom`).

## Testes em jogos reais (o que se sabe)
| Jogo | Resultado |
|---|---|
| Orecraft (`com.evrika.miner.camp`, Unity) | recursos Float (Cobre, Ferro, Mithril, Saronita, Ouro) voltam sozinhos pelo nome (`Item`, campo +0x18, assinatura pela definição). Depois de reiniciar a partida, itens ainda não liberados não existem (pelonome acha 0). Recursos têm vírgula escondida. Ouro: 3000 caminhos que nunca chegam (limpar é pendência) |
| Obsidian Knight RPG (`com.actfirstgames.seven`, Unity) | `BattleCharacter` +0x290 (i32) editado para 1000000 e voltou sozinho pelo nome. `ResourcePoolGenerator` +0x20: 14 objetos parecidos, precisou da dúvida |
| Vampire's Fall 2 (`com.earlymorningstudio.trident`, Unity) | a vida (100) aparece em 10 lugares: Slider, CharacterBar, TweenerCore (tela) e CharacterStats +0x10 (provável vida máxima). Congelar os 10 segurou na memória, mas a vida do jogo seguiu mudando: a vida atual está guardada de outro jeito (não achada ainda; próximo teste: Busca por mudança) |
| Grim Soul (`fantasy.survival.game.rpg`, Unity) | a quantidade do item achada (i32, 16→14) é a cópia da tela: escrever e congelar não mudam o item. Um script do GameGuardian para esse jogo muda instruções ARM64 do código (erro "mvn W0, #1") |

## Armadilhas já resolvidas (não repetir)
- Comentário `//` no meio da linha engoliu código Kotlin duas vezes: comentário sempre em linha própria.
- `?: 0` com `Long?` vira `Number`: usar `0L`.
- `return` não local dentro de lambda não-inline do `cmd {}`: usar rótulo (`return@rodar`).
- Spacer com weight no `ComTeclado` dividia a altura 50/50: o conteúdo vai numa Column `weight(1f)`.
- Teste de tempo do motor no GitHub: limite de 45 s (o runner é mais lento).
- Python de edição: conferir a contagem da âncora antes de trocar (`assert s.count(old) == 1`).

## Pendências
- `PLANO.md` → "Próximo, em ordem": 2.3 editar todos → 2.5 lista que continua → 2.7 DYSMANTLE → 0.3 jogo de teste → Unit 1.0.
- Limpar caminhos que nunca chegam depois de algumas aberturas (Ouro do Orecraft: 3000, ~4500 erros de motor por relatório).
- Vampire's Fall 2: achar a vida atual (busca por mudança; ver como se relaciona com a da tela).
- Busca por mudança: hoje só Dword/Float.
- Aviso "não achei pelo número" para números pequenos é pouco útil (sempre há milhares perto).
- `PLANO.md` "Próxima atualização": busca de texto (Orecraft guarda JSON), editar todos de uma vez.
