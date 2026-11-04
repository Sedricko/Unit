package dev.unit.app

import org.junit.Assert.assertEquals
import org.junit.Test

class LaboratorioTest {
    @Test
    fun padraoTiraValorOQueMudouEPonteiros() {
        // 32 antes + 4 do valor + 32 depois; endereço do valor alinhado em 8
        val addr = 0x1000L
        val a = ByteArray(68) { (it + 1).toByte() }
        // um ponteiro 0x0000007a12345678 no byte 8 (alinhado: início = 0x1000-32 = 0xfe0)
        byteArrayOf(0x78, 0x56, 0x34, 0x12, 0x7a, 0, 0, 0).copyInto(a, 8)
        val b = a.copyOf(); b[60] = 99  // um byte que mudou entre as leituras
        val p = Laboratorio.montarPadrao(addr, 4, a, b).chunked(2)
        assertEquals(68, p.size)
        assertEquals("01", p[0])
        assertEquals((8 until 16).map { "??" }, p.subList(8, 16))       // ponteiro
        assertEquals((32 until 36).map { "??" }, p.subList(32, 36))     // o valor
        assertEquals("??", p[60])                                      // mudou
        assertEquals("%02x".format(37), p[36])                          // logo depois do valor fica
    }
}
