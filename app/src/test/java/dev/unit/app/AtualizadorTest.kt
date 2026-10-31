package dev.unit.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtualizadorTest {
    @Test
    fun comparaVersoes() {
        assertTrue(Atualizador.maior("0.3.0", "0.2.1"))
        assertTrue(Atualizador.maior("0.10.0", "0.9.9"))
        assertTrue(Atualizador.maior("1.0", "0.9.9"))
        assertFalse(Atualizador.maior("0.2.1", "0.2.1"))
        assertFalse(Atualizador.maior("0.2.0", "0.2.1"))
    }
}
