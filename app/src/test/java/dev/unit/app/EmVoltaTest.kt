package dev.unit.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EmVoltaTest {
    // memória de mentira: Float 100 no centro (o Cobre), Float 200 em +0x8 (o Ferro), Dword 7 em -0x10
    private val base = 0x7000_0000L
    private val mem = ByteBuffer.allocate(EmVolta.ANTES * 2 + 8).order(ByteOrder.LITTLE_ENDIAN).apply {
        putFloat(EmVolta.ANTES, 100f); putFloat(EmVolta.ANTES + 8, 200f); putInt(EmVolta.ANTES - 16, 7)
    }.array()

    @Test
    fun autoMostraCadaCampoNoTipoQueFazSentido() {
        val l = EmVolta.ler(mem, base, "auto", null)
        assertEquals(201, l.size)
        assertEquals(Vizinho(0, base, "f32", "100"), l.first { it.desloc == 0 })
        assertEquals(Vizinho(8, base + 8, "f32", "200"), l.first { it.desloc == 8 })
        assertEquals(Vizinho(-16, base - 16, "i32", "7"), l.first { it.desloc == -16 })
    }

    @Test
    fun procurarAchaOFerroAoLado() {
        val l = EmVolta.ler(mem, base, "auto", 200.0)
        assertTrue(l.any { it.desloc == 8 && it.tipo == "f32" })
        assertTrue(EmVolta.ler(mem, base, "i32", 200.0).none { it.desloc == 8 })  // trocou para Dword: o Float não aparece
    }
}
