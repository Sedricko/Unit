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

    @Test fun bateComOArredondamentoDaTela() {
        assertTrue(Ponteiro.bate("f32", "100.37", 100.0))
        assertFalse(Ponteiro.bate("f32", "101", 100.0))
        assertTrue(Ponteiro.bate("i32", "57", 57.0))
        assertFalse(Ponteiro.bate("i32", "58", 57.0))
        assertFalse(Ponteiro.bate("i32", "?", 57.0))
    }
}
