package dev.unit.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class ArrumarTest {
    @Test
    fun soltarOOlhoNaBarraDeBaixo() {
        Arrumar.cima.clear(); Arrumar.cima += Arrumar.PADRAO; Arrumar.baixo.clear()
        Arrumar.areaCima = Rect(100f, 0f, 340f, 48f); Arrumar.areaBaixo = Rect(0f, 600f, 340f, 650f)
        Arrumar.soltar("olho", Offset(20f, 620f), 48f)
        assertEquals(listOf("pausa", "expandir", "minimizar", "fechar"), Arrumar.cima.toList())
        assertEquals(listOf("olho"), Arrumar.baixo.toList())
        Arrumar.soltar("pausa", Offset(20f, 900f), 48f)  // fora das barras: volta para onde estava
        assertEquals(listOf("pausa", "expandir", "minimizar", "fechar"), Arrumar.cima.toList())
    }
}
