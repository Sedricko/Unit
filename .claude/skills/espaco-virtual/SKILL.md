---
name: espaco-virtual
description: Começa e continua o projeto "espaço virtual" do usuário - um app Android hospedeiro que roda o jogo dentro dele (como Parallel Space / VirtualXposed / BlackBox) para o Unit ler a memória do jogo SEM root e sem o vPhoneOS. Use SEMPRE que o usuário falar de espaço virtual, rodar jogo dentro do app, BlackBox, VirtualApp, Twoyi, emulador de Android no celular, Unit sem root, ou pedir para começar esse projeto.
---

# Espaço virtual (projeto separado do Unit)

## Quem é o usuário e como trabalhar com ele
- Fala português (Brasil). Responda em português, frases curtas, sem jargão sem explicar.
- O objetivo dele é **aprender**: explique o porquê de cada passo, com exemplos do Orecraft.
- Ele testa no celular dentro do **vPhoneOS** (Android 16 com root). Nunca mexa no celular de verdade dele (S25) sem ele pedir.
- **Antes de mudar qualquer tela, mande uma prévia** (imagem) e espere o ok. Ele gosta de visual simples, profissional,
  vidro azul-noite, sem ícones coloridos (o mesmo DESIGN.md do Unit).
- Planejamento primeiro, peça por peça, com cronograma. Ideia fora de ordem vai para um ramo "laboratório" separado.
- Seja honesto: diga o que não testou e o que não sabe; não invente confirmação.

## O que é o projeto
- Hoje o **Unit** (repositório `Sedricko/Unit`) precisa de root, que vem do vPhoneOS. O motor é `motor/unitd.c`
  (process_vm_readv / /proc/PID/mem), e o app é Kotlin + Compose com um balão sobre o jogo.
- **Espaço virtual**: um app hospedeiro instala e roda o jogo *dentro* dele. Os processos do jogo nascem do hospedeiro,
  com o mesmo usuário Android, e assim o hospedeiro lê a memória **sem root** (é o modo sem root do GameGuardian).
- Ganhos: Unit sem root e sem vPhone; o jogo usa a placa de vídeo normal do celular.

## O que já foi pesquisado (31/10) - ver também `docs/ideias/espaco-virtual.md`
| Projeto | O que é | Situação |
|---|---|---|
| Twoyi | Android 8.1 inteiro num app | descontinuado; forks; o ROM não compila do código. Não recomendado |
| BlackBox (FBlackBox) | espaço virtual de apps | aberto, mais novo: **melhor candidato** (conferir licença, último commit, Android 16) |
| VirtualApp | espaço virtual de apps | licença confusa (GPL numa cópia, "não é livre" em outra): evitar |

## Primeiro passo (sem programar)
1. O usuário instala o APK do BlackBox (releases do GitHub dele) e põe o **Orecraft** dentro; joga uns minutos.
2. Pergunte: abriu? roda liso? salva o progresso? (peça prints)
3. Só se rodar bem: confirmar a licença e planejar o cronograma (fases pequenas, com testes), por exemplo:
   - F1: compilar o BlackBox do código e rodar o Orecraft dentro.
   - F2: o hospedeiro lê a memória do jogo (process_vm_readv, mesmo uid) - provar com o Cobre do Orecraft.
   - F3: juntar o motor/tela do Unit ao hospedeiro (ou o Unit falar com o hospedeiro).
   - F4: prova em jogo real (Orecraft, DYSMANTLE) e no celular de verdade, quando o usuário autorizar.

## Regras do projeto
- As do `PLANO.md` do Unit ("Regras do projeto (fixas)").
- Commits terminam com as linhas de atribuição que a sessão informar; nada de ids de modelo no código.

## Onde guardar
- Se o usuário criar um repositório novo para isso, trabalhe nele e copie esta skill para `.claude/skills/` de lá.
- Anote o andamento num PLANO.md próprio (fases, "Onde estamos"), como no Unit.
