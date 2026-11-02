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
        assertTrue(Ponteiro.bate("i32", "57", 57.0))
        assertFalse(Ponteiro.bate("i32", "58", 57.0))
        assertFalse(Ponteiro.bate("i32", "?", 57.0))
    }
}
