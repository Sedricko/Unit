package dev.unit.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegistroTest {
    @Test fun leLinhaDoArquivoComDetalhe() {
        val e = Registro.deLinha("02/11 22:15:02\tcaminho\tCobre: +2 caminhos novos\t+ entrou a+1:8 | + entrou b+2:8")!!
        assertEquals("caminho", e.tipo)
        assertEquals(listOf("+ entrou a+1:8", "+ entrou b+2:8"), e.detalhe)
        assertEquals(emptyList<String>(), Registro.deLinha("02/11 22:15:02\tbusca\tBuscar 137 → 84\t")!!.detalhe)
        assertNull(Registro.deLinha("linha estragada"))
    }

    @Test fun detalheCortaEmTrinta() {
        val l = Ponteiro.lista5((1..35).map { "c$it" }, "– saiu")
        assertEquals(31, l.size)
        assertEquals("– saiu c1", l.first())
        assertEquals("… e mais 5", l.last())
    }
}
