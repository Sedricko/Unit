# Unit: plano de aprendizado

**Unit** é o nosso editor de memória para Android, feito do zero.

## Objetivo (uma frase)
Aprender, construindo peça por peça, como funciona um editor de memória de jogos (como o GameGuardian): da leitura da
memória de um processo até scripts, regras do jogo e save.

## Visão: aonde queremos chegar
O Unit é uma **ferramenta universal**: escolhe qualquer jogo offline e **modifica o jogo por completo**, em todas as camadas.

| Camada | O que dá para mudar | Fase |
|---|---|---|
| Memória | Qualquer número ao vivo: recursos, vida, XP, nível; congelar | 1–3 |
| Valores escondidos | Cifrados, em texto, em objetos que mudam de lugar | 3 |
| Scripts | Qualquer script do GameGuardian, e os nossos | 4 |
| Regras (código) | Preços em moeda do jogo, custos, esperas, dano, chances: pelo nome do método | 5 |
| Save | O progresso guardado, com backup | 6 |
| Tempo | Velocidade do jogo, cronômetros | 6 |

**Universal** quer dizer que o Unit reconhece o motor do jogo e usa o caminho certo para cada um:
- **Unity IL2CPP / Mono:** nomes de classes e métodos;
- **Unreal:** a tabela de objetos;
- **jogos com Lua:** as tabelas do Lua;
- **nativo:** símbolos e assinaturas.

Começamos pelo que é comum a todos (memória) e somamos um motor por vez.

O caminho é o mesmo do Tinker, mas **peça por peça e com prova em cada uma**, para chegar lá sem as metades que não
funcionavam.

## Regras do projeto (fixas)
1. Só jogos **offline**, de um jogador.
2. Nada que é pago com **dinheiro de verdade** (compras, VIP, premium, tirar anúncio pago).
3. Nunca ganhar a recompensa de **anúncio** sem ver o anúncio.

Todo o resto é aberto: moedas e recursos do jogo, vida, XP, tempo, velocidade, cronômetros, save, regras (patches).

## Como trabalhamos
- **Uma peça por vez.** Cada peça tem: o que você aprende, o que ela faz, o teste, e quando está "pronta".
- **Teste primeiro aqui (sem celular), depois no celular.** Só passa para a próxima peça quando a atual funciona.
- **Cada peça na sua pasta/arquivo.** Uma peça quebrada não derruba as outras.
- **Explicação junto do código.** Em cada peça eu explico o porquê; você pergunta o que não ficou claro.
- **Um jogo de teste nosso** (pequeno, feito por nós) para testar sem depender de jogo real; o jogo real só no fim de cada fase.
- **Versão** no nome de cada entrega (v0.1, v0.2…): você sempre sabe em qual está.

## Jogos: de teste e reais
- **Mina de Treino (nosso jogo de teste):** cada peça é provada primeiro nele, onde sabemos exatamente onde está cada valor.
- **Jogos reais (offline):** toda fase termina com uma **prova num jogo real**. É o que mostra se o Unit funciona de verdade.
  - **Orecraft** (Unity IL2CPP): recursos guardados como texto JSON, roleta (regra já conhecida), relógio do Unity.
  - **DYSMANTLE** (motor próprio, não Unity): bom para testar busca, ponteiros e save fora do Unity.
  - Outros jogos offline que você escolher entram na lista a qualquer momento.
- O que aprendemos em cada jogo real vira anotação no projeto (onde fica cada valor, o que funcionou, o que não).

## Ferramentas
- Celular ou emulador **com root** (Magisk).
- **Android Studio** (Kotlin) e o **NDK** (para a parte em C).
- **GitHub** com o repositório novo; o GitHub Actions compila o APK a cada mudança.

---

## Onde estamos (31/10)

- Fase 0: 0.1 ✅ · 0.2 ✅ · **0.3 jogo de teste ❌ (pulada)**
- Fase 1: 1.1 a 1.6 ✅ (prova no Orecraft)
- Fase 2: 2.1 ✅ · 2.2 ✅ · **2.3 ✅ um / ❌ editar todos** · 2.4 ✅ · **2.5 ✅ nome / ❌ continuar ao reabrir o app** · 2.6 ✅ · **2.7 ✅ Orecraft / ❌ DYSMANTLE**
- Fase 3: 3.2 assinatura adiantada **só no ramo `lab-assinatura`** (Laboratório), fora da versão normal até chegar a vez dela
- **Próximo, em ordem:** 2.3 editar todos → 2.5 lista que continua → 2.7 DYSMANTLE → 0.3 jogo de teste → **Unit 1.0**
- Regra: peça fora de ordem só entra como Laboratório, num ramo separado

## Próxima atualização (pedidos do usuário, anotados)

- [x] (0.3.5) Abrir o Unit sem jogo escolhido também liga o balão "U" (hoje o balão só aparece depois de tocar num jogo).
      Pelo balão dá para escolher o jogo (tela "Jogos" do painel).
- [ ] Busca de **texto** (o Orecraft guarda os recursos como texto JSON): opção Número / Texto no Achar. Prévia antes.
- [ ] Editar todos os resultados de uma vez quando sobram poucos.
- [ ] Depois de editar, avisar se o número da tela mudou.
- [x] (0.3.11) Lista de jogos sem processos do sistema (android.process.acore, com.google.process.gapps, networkstack...):
      são nomes de processo, não de app; com QUERY_ALL_PACKAGES o pacote de verdade sempre é achado, então "sem app" = ignorar.
      Também esconder ferramentas (Magisk, o próprio Unit).
- [x] (0.3.11) Resultado Dword e Float com o mesmo número: mostrar o Vírgula primeiro como "provável valor real" (Orecraft).
- [ ] (lab 0.3.36) Tocar no ícone já abre o balão (com a permissão de sobrepor); sem ela, pede a permissão e vira balão ao voltar.
- [ ] (lab 0.3.35) Resultados da busca e o "agora" do Editar ao vivo; tecla de ponto; teclado no painel estreito; minimizar vira balão;
      o salvo sai da pesquisa (fica só em Salvos, como no GameGuardian).
- [ ] **Busca por mudança**: aumentou / diminuiu / mudou / não mudou / igual à foto (valores que mudam sozinhos). Amostra no Laboratório (0.3.31-lab).
- [ ] **Ajuda** (prévia aprovada em 01/11): botão "?" no painel abre a lista de tópicos (busca exata, refinar, faixa ~,
      grupo ;, auto e tipos, em volta, ver como/limite, salvar e congelar, pausa e escudo) e uma colinha.
      Cada tópico tem: para que serve, como digitar, um exemplo no Orecraft e o botão "Experimentar",
      que abre o Achar já com o exemplo digitado. Texto dentro do app, sem internet. Entra depois da busca por mudança.

Já feito na 0.3.1 (31/10): ícones dos jogos, painel arrastável, olho (segurar some), expandir (tela cheia),
minimizar/fechar no app, lembra tamanho e lugar, trocar de jogo pelo painel, atualização automática (0.3.0).

## Fase 0: preparar o terreno (v0.0)
| Peça | Você aprende | Pronto quando |
|---|---|---|
| 0.1 Repositório e estrutura de pastas | Organizar um projeto Android em módulos | Pastas criadas, `README` explica cada uma |
| 0.2 Compilação automática | GitHub Actions gerando o APK | Todo push gera um APK para baixar |
| 0.3 Jogo de teste | Um app mínimo com números na tela (ouro, vida) | Instala no celular e os números mudam ao tocar |

## Fase 1: o motor de memória (v0.1 – v0.5)
O coração do projeto: um programa em C que roda como root e lê/escreve a memória de outro app.

| Peça | Você aprende | Pronto quando |
|---|---|---|
| 1.1 Rodar como root | Como um app chama um programa nativo com `su` | O app mostra "root ok" |
| 1.2 Listar processos | `/proc`: como achar o jogo pelo nome do pacote | Lista os apps abertos e o PID de cada um |
| 1.3 Mapa de memória | `/proc/PID/maps`: regiões (heap, anônima, código) | Mostra as regiões do jogo de teste |
| 1.4 Ler e escrever um endereço | `process_vm_readv` / `/proc/PID/mem` | Lê e muda o ouro do jogo de teste por endereço |
| 1.5 Tipos de número | Dword, Float, Qword, Double (como ficam na memória) | Lê o mesmo endereço nos 4 tipos |
| 1.6 **Prova no jogo real** | Ler a memória de um jogo de verdade | Lista as regiões e lê um endereço do Orecraft |

## Fase 2: buscar como o GameGuardian (v0.6 – v1.0)
| Peça | Você aprende | Pronto quando |
|---|---|---|
| 2.1 Busca exata | Varrer as regiões atrás de um valor | Acha o ouro (dezenas de resultados) |
| 2.2 Refinar | Muda no jogo, filtra os que mudaram junto | Sobra 1 resultado |
| 2.3 Escrever nos resultados | Editar um ou todos | O ouro muda na tela do jogo de teste |
| 2.4 Congelar | Uma thread que reescreve o valor | O ouro não desce mais |
| 2.5 Lista salva | Guardar endereços com nome | Reabre o app e a lista continua |
| 2.6 Tela do app | Interface simples (Compose) para 2.1–2.5 | Dá para fazer tudo sem comandos |
| 2.7 **Prova no jogo real** | Diferença entre jogo de teste e jogo de verdade | Muda um recurso no Orecraft e no DYSMANTLE |

**Unit v1.0 = um mini GameGuardian que busca, refina, escreve e congela.**

## Fase 3: achar de novo depois de reabrir (v1.1 – v1.4)
| Peça | Você aprende | Pronto quando |
|---|---|---|
| 3.1 Por que o endereço muda | ASLR, heap, objetos novos | Explicado com o jogo de teste |
| 3.2 Assinatura (vizinhos) | Os bytes em volta do valor | Acha o ouro de novo após reabrir |
| 3.3 Ponteiros | Caminho lib+offset → valor | Acha pelo ponteiro após reabrir |
| 3.4 Valor escondido | Cifrado (XOR), dobrado, guardado como texto | Acha um valor que a busca normal não acha |
| 3.5 **Prova no jogo real** | Valores escondidos de verdade | Muda as joias do Orecraft (texto JSON) e acha de novo ao reabrir |

## Fase 4: scripts Lua compatíveis com o GameGuardian (v1.5 – v2.0)
| Peça | Você aprende | Pronto quando |
|---|---|---|
| 4.1 Lua dentro do app | LuaJ (o mesmo Lua 5.2 do GG) | Roda `print("oi")` |
| 4.2 gg.* básico | searchNumber, refineNumber, getResults, getValues, setValues | Um script simples de busca roda igual ao GG |
| 4.3 Menus | alert, choice, prompt, multiChoice, toast | O menu de um script aparece no app |
| 4.4 Lista e congelar | addListItems, getListItems, loadResults, editAll | Um script de menu completo funciona |
| 4.5 **Prova com scripts reais** | Compatibilidade de verdade | O SpeedHackFinder roda igual ao GG no Orecraft |

## Fase 5: regras do jogo (patches) em Unity (v2.1 – v2.5)
| Peça | Você aprende | Pronto quando |
|---|---|---|
| 5.1 IL2CPP | Como o Unity guarda nomes de classes e métodos | Lista as classes de um jogo Unity |
| 5.2 Achar um método pelo nome | O endereço do código de `GetPrice()` | Mostra o endereço e as instruções |
| 5.3 Patch "sempre devolve X" | Instruções ARM64 (`mov w0,#N; ret`) | Um preço em moeda do jogo vira 0 |
| 5.4 Ligar e desligar | Guardar os bytes originais | Liga, desliga, sobrevive a atualização |
| 5.5 **Prova no jogo real** | Uma regra num jogo de verdade | Roleta do Orecraft de graça (preço em joias = 0) |

## Fase 6: save e velocidade (v2.6 – v3.0)
| Peça | Você aprende | Pronto quando |
|---|---|---|
| 6.1 Onde fica o save | Pastas do app, PlayerPrefs, JSON | Lista os arquivos de save |
| 6.2 Editar com backup | Mudar um campo com segurança | Muda um recurso e volta o backup se der errado |
| 6.3 Velocidade do jogo | O relógio do Unity (timeScale) | 2x funciona e é conferido |
| 6.4 **Prova no jogo real** | Save e velocidade de verdade | Save do Orecraft editado com backup; Orecraft em 2x |

## Depois (só se fizer sentido)
Espaço virtual (rodar o jogo dentro de um app, sem root): projeto separado; ver `docs/ideias/espaco-virtual.md`.
Bot de testes automáticos, painel no PC, assistente de IA. Cada um como peça separada, com o mesmo cuidado.

---

## Primeira tarefa
1. Crie o repositório novo e vazio no GitHub.
2. Me diga o nome (dono/repositório).
3. Eu começo pela **peça 0.1** e te mostro antes de seguir.
