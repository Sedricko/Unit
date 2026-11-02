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
        assertEquals("b+9:8", r.first { it.nome == "Cobre" }.caminhos[0])  // mesmo nome: o novo troca o velho
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

    @Test fun bateComOArredondamentoDaTela() {
        assertTrue(Ponteiro.bate("f32", "100.37", 100.0))
        assertFalse(Ponteiro.bate("f32", "101", 100.0))
        assertTrue(Ponteiro.bate("i32", "57", 57.0))
        assertFalse(Ponteiro.bate("i32", "58", 57.0))
        assertFalse(Ponteiro.bate("i32", "?", 57.0))
    }
}
