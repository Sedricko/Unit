package dev.unit.app

import org.junit.Assert.assertEquals
import org.junit.Test

class NucleoTest {
    private val d = Achado("1", "i32", "100"); private val f = Achado("2", "f32", "100.0"); private val q = Achado("3", "i64", "100")

    @Test
    fun semAprendidoAOrdemFicaIgual() = assertEquals(listOf(d, f, q), ordenar(listOf(d, f, q), null))

    @Test
    fun oTipoQueFuncionouVemPrimeiro() {
        assertEquals(listOf(f, d, q), ordenar(listOf(d, f, q), "f32"))
        assertEquals(listOf(d, f, q), ordenar(listOf(d, f, q), "i32"))
    }

    @Test
    fun dwordEQwordIguaisNoMesmoEnderecoViramUm() {
        val dw = Achado("6f306fc0c8", "i32", "67"); val qw = Achado("6f306fc0c8", "i64", "67"); val outro = Achado("6f307019a8", "i64", "67")
        assertEquals(listOf(dw, outro), juntar(listOf(dw, qw, outro)))
        val qwDiferente = Achado("6f306fc0c8", "i64", "4294967363")
        assertEquals(listOf(dw, qwDiferente), juntar(listOf(dw, qwDiferente)))
    }

    @Test
    fun limiteDoDword() {
        org.junit.Assert.assertTrue(passaLimite("i32", "5000000000"))
        org.junit.Assert.assertFalse(passaLimite("i32", "999999999"))
        org.junit.Assert.assertFalse(passaLimite("i32", "2147483647"))
        org.junit.Assert.assertFalse(passaLimite("i64", "5000000000"))
        org.junit.Assert.assertFalse(passaLimite("f32", "5000000000"))
    }
}
