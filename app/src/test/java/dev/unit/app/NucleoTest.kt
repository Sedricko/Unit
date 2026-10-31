package dev.unit.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NucleoTest {
    @Test
    fun floatComDwordIgualEhProvavelReal() {
        val f = Achado("1", "f32", "100.0"); val d = Achado("2", "i32", "100"); val g = Achado("3", "f32", "7.5")
        assertTrue(provavelReal(f, listOf(f, d)))
        assertFalse(provavelReal(d, listOf(f, d)))
        assertFalse(provavelReal(g, listOf(g, d)))
    }
}
