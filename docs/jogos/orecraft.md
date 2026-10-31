# Orecraft (`com.evrika.miner.camp`)

Jogo offline em **Unity IL2CPP** (arm64). O que já sabemos, do projeto anterior (Tinker), para não começar do zero:

- **Recursos (joias, barras)** ficam como **texto JSON** (`JSONString.m_Data = "80"`), não como número: a busca de
  número acha cópias que a tela não usa. Mudar o texto (mesmo tamanho) foi o caminho que apareceu.
- **Barras** têm a mesma estrutura entre si: a assinatura de vizinhos acha muitos lugares iguais; o que separa o certo
  é um campo próprio dele (o id).
- **Roleta**: `ShopRouletteController.GetSpinGemsPrice` = `mov w0,#25; ret` (preço do giro em joias). Patch para 0:
  bytes `00 00 80 52 c0 03 5f d6`. Confirmado na tela (o botão mostra 💎 0). Nunca tocar em ASSISTIR (anúncio).
- **Velocidade**: o SpeedHackFinder do GameGuardian acha no celular (relógio do Unity: `3F7FFFFEh` e dois tempos Qword
  logo depois). No emulador, o mesmo padrão **não** apareceu nas posições de sempre: ainda a investigar.
- **Janela da nuvem** (Google Play Games) ao abrir: escolher sempre carregar da nuvem; manter o do emulador pode mandar
  progresso velho para a nuvem.
- O tutorial ("Construa a Fundição", "Tenha 250 de Ferro") trava a loja até ser concluído.
