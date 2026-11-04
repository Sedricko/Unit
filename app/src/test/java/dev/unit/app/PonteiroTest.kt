package dev.unit.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PonteiroTest {
    private fun c(nome: String, cam: String) = Caminho(nome, "f32", listOf(cam))

    @Test fun juntarNaoPerdeOsOutros() {
        val velha = listOf(c("Cobre", "a+1:8"), c("Ferro", "a+2:8"))
        val r = Ponteiro.juntar(velha, listOf(c("Cobre", "b+9:8"), c("Ouro", "a+3:8")))
        assertEquals(listOf("Ferro", "Cobre", "Ouro"), r.map { it.nome })
        // mesmo nome: funde - os caminhos dos dois ficam, o já conferido primeiro
        assertEquals(listOf("a+1:8", "b+9:8"), r.first { it.nome == "Cobre" }.caminhos)
    }

    @Test fun vivosFicaComAMaioriaEApagaOsMortos() {
        // 3 chegam em "a1", 1 em "b2" (errado), 1 quebrou
        val r = Ponteiro.vivos(listOf("c1" to "a1", "c2" to "b2", "c3" to null, "c4" to "a1", "c5" to "a1"))
        assertEquals(listOf("c1", "c4", "c5"), r)
        // empate com 2 a favor de cada: fica o do caminho mais confiável (o 1º da lista)
        assertEquals(listOf("c1", "c3"), Ponteiro.vivos(listOf("c1" to "x", "c2" to "y", "c3" to "x", "c4" to "y")))
        // só 1 a favor de cada endereço: pouca prova, não apaga nada
        assertEquals(listOf("c1", "c2"), Ponteiro.vivos(listOf("c1" to "x", "c2" to "y")))
        // nenhum funcionou: não apaga nada
        assertEquals(listOf("c1", "c2"), Ponteiro.vivos(listOf("c1" to null, "c2" to null)))
    }

    @Test fun alimentarPoeOsProvadosPrimeiroSemRepetir() {
        assertEquals(listOf("v1", "n1", "n2"), Ponteiro.alimentar(listOf("v1"), listOf("n1", "v1", "n2")))
        assertEquals(Ponteiro.RESERVA, Ponteiro.alimentar(emptyList(), (1..50).map { "n$it" }).size)
    }

    @Test fun fundirAConferirComConferido() {
        val velho = Caminho("Cobre", "f32", listOf("v1", "v2"), fixo = true)
        val novo = Caminho("Cobre", "f32", listOf("n1", "v1"), conferido = false)
        val f = Ponteiro.fundir(velho, novo)
        assertEquals(listOf("v1", "v2", "n1"), f.caminhos)  // sem repetir, os provados primeiro
        assertTrue(f.conferido && f.fixo)
        assertEquals(listOf("n1", "x"), Ponteiro.fundir(Caminho("Cobre", "f32", listOf("x"), conferido = false), Caminho("Cobre", "f32", listOf("n1"), conferido = false)).caminhos)
    }

    @Test fun cruzarDuasAberturas() {
        val a = Caminho("Cobre", "f32", listOf("bom", "falso1", "falso2"), conferido = false, pid = 100)
        val b = Caminho("Cobre", "f32", listOf("falso3", "bom"), conferido = false, pid = 200)
        val f = Ponteiro.fundir(a, b)
        assertEquals(listOf("bom"), f.caminhos)  // só o que aparece nas duas aberturas
        assertTrue(f.conferido)
        // mesma abertura (mesmo pid): não cruza, junta
        assertEquals(listOf("falso3", "bom", "falso1", "falso2"), Ponteiro.fundir(a, b.copy(pid = 100)).caminhos)  // os 4, sem repetir; nenhum conferido: a procura mais nova primeiro
    }

    @Test fun fixadoSoTiraNaTerceiraFalha() {
        val c = Caminho("Cobre", "f32", listOf("bom", "ruim"), fixo = true, falhas = mapOf("ruim" to 2))
        // "bom" e um 2º caminho chegam em "x" (maioria); "ruim" quebrou de novo: 3ª falha, sai
        val r = Ponteiro.protegido(c, listOf("bom" to "x", "ruim" to null, "outro" to "x"))
        assertEquals(listOf("bom"), r.caminhos)
        val c2 = Caminho("Cobre", "f32", listOf("bom", "ruim"), fixo = true)
        val r2 = Ponteiro.protegido(c2, listOf("bom" to "x", "ruim" to null, "outro" to "x"))
        assertEquals(listOf("bom", "ruim"), r2.caminhos)  // 1ª falha: fica
        assertEquals(1, r2.falhas["ruim"])
    }

    @Test fun bateComOArredondamentoDaTela() {
        assertTrue(Ponteiro.bate("f32", "100.37", 100.0))
        assertFalse(Ponteiro.bate("f32", "101", 100.0))
        assertTrue(Ponteiro.bate("f32", "100.8", 100.0))  // a tela corta (425,8 aparece 425)
        assertFalse(Ponteiro.bate("f32", "99.4", 100.0))
        assertFalse(Ponteiro.bate("f32", "88.3", 87.5))  // com vírgula na tela: só o arredondamento
        assertTrue(Ponteiro.bate("i32", "57", 57.0))
        assertFalse(Ponteiro.bate("i32", "58", 57.0))
        assertFalse(Ponteiro.bate("i32", "?", 57.0))
    }

    @Test fun deClasseMontaOPedidoDoPelonome() {
        // a resposta do motor: os textos do objeto (a assinatura) e a classe, o espaço de nomes e o campo
        assertEquals("ItemRecurso Jogo 30 10=436f627265,20=31", Ponteiro.deClasse(listOf("texto 10 436f627265", "texto 20 31"), "ItemRecurso Jogo 30"))
        assertEquals("Moedas - 18", Ponteiro.deClasse(emptyList(), "Moedas - 18"))  // sem textos: só a classe
        // sem texto, com números fixos (o "Item" do Orecraft): vão na assinatura com o "n" na frente
        assertEquals("Item - 18 n10=00000007,n14=00000001", Ponteiro.deClasse(listOf("numeros n10=00000007,n14=00000001"), "Item - 18"))
        // texto num objeto apontado (a definição do item tem o nome) e a estrutura, que só vai para o registro
        assertEquals("Item - 18 10>10=436f627265", Ponteiro.deClasse(listOf("texto 10>10 436f627265", "estrutura 10:->DefItem 18:valor"), "Item - 18"))
        assertEquals(null, Ponteiro.deClasse(emptyList(), ""))  // não é objeto do Unity
        assertEquals("ItemRecurso, campo +0x30", Ponteiro.rotuloUnity("ItemRecurso Jogo 30 10=436f627265"))
    }

    @Test fun fundirNaoPerdeOPeloNome() {
        val velho = Caminho("Cobre", "f32", listOf("v1"), unity = "ItemRecurso Jogo 30 10=436f627265")
        assertEquals(velho.unity, Ponteiro.fundir(velho, Caminho("Cobre", "f32", listOf("n1"), conferido = false)).unity)
        val a = Caminho("Cobre", "f32", listOf("a"), conferido = false, pid = 1, unity = "X - 8")
        assertEquals("X - 8", Ponteiro.fundir(a, Caminho("Cobre", "f32", listOf("b"), conferido = false, pid = 2)).unity)  // sem caminho em comum
        assertEquals("X - 8", Ponteiro.fundir(a, Caminho("Cobre", "f32", listOf("a"), conferido = false, pid = 2)).unity)  // cruzadas
    }

    @Test fun plausivelComparaComOUltimo() {
        assertTrue(Ponteiro.plausivel("51", "58"))
        assertFalse(Ponteiro.plausivel("524", "0"))       // o falso "conferido" com 0 do relatório
        assertTrue(Ponteiro.plausivel("0", "40"))         // começou em 0 e subiu
        assertFalse(Ponteiro.plausivel("51", "NaN"))
    }

    @Test fun escolherEntreVariosObjetos() {
        val cands = listOf("a1" to "211", "a2" to "99")  // 2 "Item" da Barra de Cobre: o do inventário (211) e outro
        assertEquals("a2", Ponteiro.escolher(cands, "211", "f32", setOf("a2")))  // um caminho chega no a2: ele ganha
        assertEquals("a1", Ponteiro.escolher(cands, "211", "f32", emptySet()))   // o único com o último valor
        assertEquals("a1", Ponteiro.escolher(listOf("a1" to "211.4", "a2" to "99"), "211", "f32", emptySet()))  // Float arredondado
        assertEquals(null, Ponteiro.escolher(listOf("a1" to "211", "a2" to "211"), "211", "f32", emptySet()))  // empate: pede o número
        assertEquals(null, Ponteiro.escolher(cands, "", "f32", emptySet()))      // sem último valor
        // o relatório: o jogo mudou de 235 para 236 com o Unit esperando; o outro objeto tinha 1 → fica o 236
        assertEquals("a1", Ponteiro.escolher(listOf("a1" to "236", "a2" to "1"), "235", "f32", emptySet()))
        // dois perto do último valor (235 e 234): não chuta, pede o número
        assertEquals(null, Ponteiro.escolher(listOf("a1" to "236", "a2" to "234"), "235", "f32", emptySet()))
        // longe demais (o último valor era de outra abertura): não chuta
        assertEquals(null, Ponteiro.escolher(listOf("a1" to "300", "a2" to "1"), "235", "f32", emptySet()))
    }

    @Test fun mesclarNaoDesfazOQueMudouDuranteAProcura() {
        val foto = listOf(Caminho("Cobre", "f32", listOf("a"), valor = "198", conferido = false), Caminho("Ferro", "f32", listOf("f")))
        // durante a procura: Ferro foi apagado, Cobre ganhou um caminho novo, foi escolhido na tela e guardou 236 ao fechar
        val agora = listOf(Caminho("Cobre", "f32", listOf("a", "novo"), valor = "236", conferido = true))
        val calc = listOf(Caminho("Cobre", "f32", listOf("a"), valor = "198", conferido = false), Caminho("Ferro", "f32", listOf("f")))
        val r = Ponteiro.mesclar(foto, agora, calc)
        assertEquals(listOf("Cobre"), r.map { it.nome })            // o apagado não volta
        assertEquals(listOf("a", "novo"), r[0].caminhos)             // o caminho que entrou no meio fica
        assertTrue(r[0].conferido)                                   // conferido não volta para trás
        assertEquals("236", r[0].valor)                              // o último valor guardado no meio ganha
        // a procura achou um valor novo (pelo nome): esse ganha
        assertEquals("240", Ponteiro.mesclar(foto, agora, listOf(calc[0].copy(valor = "240")))[0].valor)
    }

    @Test fun nomeLivreNaoRepete() {
        assertEquals("Valor 1", Ponteiro.nomeLivre(emptyList()))
        assertEquals("Valor 3", Ponteiro.nomeLivre(listOf("Valor 1", "Valor 2", "Cobre")))
        assertEquals("Valor 1", Ponteiro.nomeLivre(listOf("Valor 2")))  // apagou o 1: o 2 não se repete
    }

    @Test fun arquivoComQuebraDeLinhaNaoEntra() {
        assertTrue(Ponteiro.valido(Caminho("Cobre", "f32", listOf("libil2cpp.so+44b11d0:77a88:10"), unity = "Item - 18 10>20=74")))
        assertFalse(Ponteiro.valido(Caminho("Cobre", "f32", listOf("lib+1:8\nattach 1"))))   // viraria outro comando do motor
        assertFalse(Ponteiro.valido(Caminho("Cobre", "f32", listOf("lib+1:8"), unity = "Item - 18\nwrite 1 i32 0")))
        assertFalse(Ponteiro.valido(Caminho("Cobre", "x; rm", listOf("lib+1:8"))))
    }
}
