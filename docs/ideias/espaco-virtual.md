# Ideia: espaço virtual (rodar o jogo dentro de um app, sem root)

Projeto separado do Unit (outra conversa). Este arquivo é o ponto de partida.

## Por quê
Hoje o Unit precisa de root, que vem do vPhoneOS. Um **espaço virtual** roda o jogo *dentro* de um app hospedeiro; o
hospedeiro fica com o mesmo usuário do jogo e lê a memória dele **sem root** (é o modo sem root do GameGuardian, via
Parallel Space / VirtualXposed). Vantagens: sem vPhone, jogo com a placa de vídeo normal do celular.

## Caminhos olhados (31/10)
| Projeto | O que é | Situação |
|---|---|---|
| Twoyi | Android inteiro (8.1) num app | descontinuado; forks da comunidade; o ROM ainda não compila do código. **Não recomendado** |
| BlackBox (FBlackBox) | espaço virtual de apps | aberto, mais novo. **Melhor candidato** (conferir licença, último commit, Android 16) |
| VirtualApp | espaço virtual de apps | licença confusa (GPL numa cópia, "não é livre" em outra). Evitar |

Links: github.com/twoyi/twoyi · github.com/althafvly/twoyi · github.com/android-notes/VirtualApp

## Primeiro passo (sem programar)
1. Instalar o APK do BlackBox (página de releases do GitHub dele) no celular.
2. Colocar o **Orecraft** dentro dele e jogar uns minutos: abre? roda liso? salva?
3. Só se rodar bem: planejar juntar o motor do Unit (`unitd`) com o hospedeiro.

## Perguntas para responder
- O jogo dentro do BlackBox fica "dumpable"/acessível ao hospedeiro (process_vm_readv sem root)?
- Funciona no Android 16 (vPhone) e no Android do celular de verdade?
- A licença permite juntar com o Unit?

## Regras (as mesmas do Unit)
- Só jogos offline single-player; nada pago com dinheiro real; nada de pular anúncio com recompensa.
- Nada para jogos online nem para passar por proteção anti-trapaça.
- Não mexer no celular de verdade do usuário sem ele pedir.
