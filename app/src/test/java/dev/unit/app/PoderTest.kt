package dev.unit.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoderTest {
    @Test fun palpite() {
        assertEquals("i32" to "3", Poder.palpite(3))
        assertEquals("i32" to "0", Poder.palpite(0))
        assertEquals("f32" to "10", Poder.palpite(java.lang.Float.floatToIntBits(10f)))
        assertEquals("f32", Poder.palpite(java.lang.Float.floatToIntBits(4.2f)).first)
    }

    @Test fun exatoVoltaAosMesmosBytes() {
        for (f in listOf(4.2f, 0.1f, 12.7f, 1e-3f)) {
            val raw = java.lang.Float.floatToIntBits(f)
            assertEquals(raw, java.lang.Float.floatToIntBits(Poder.exato(raw).toDouble().toFloat()))
        }
        assertEquals("-5", Poder.exato(-5))
    }

    @Test fun lerVigia() {
        val v = Poder.lerVigia(listOf("pc 7a1a2f34", "lib libil2cpp.so+1a2f34", "ins b9001a60", "base 7a3c1ef0 x19"), "arm64")
        assertEquals("libil2cpp.so+1a2f34", v.lib); assertEquals(0x7a3c1ef0L, v.base); assertTrue(v.arm)
        val x = Poder.lerVigia(listOf("pc 5589fbbe323a", "lib alvo+123a"), "x86_64")
        assertEquals(null, x.base); assertFalse(x.arm)
    }

    @Test fun inicioDoBloco() {
        assertEquals(0x1000L, Poder.inicioBloco(0x1018, 0x1000))
        assertEquals(0x5000L - 32, Poder.inicioBloco(0x5000, 0x1000))  // longe demais do base
        assertEquals(0x5000L - 32, Poder.inicioBloco(0x5000, null))
    }
}
