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
}
