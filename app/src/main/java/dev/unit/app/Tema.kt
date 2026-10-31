package dev.unit.app

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** As cores do DESIGN.md: azul-noite, cartões translúcidos, um destaque em degradê azul. Sem ícones coloridos. */
object Cor {
    val fundo = Color(0xFF071026)
    val brilho = Color(0xFF1D4FB8)
    val cartao = Color(0xFF0D1B3D)
    val cartao2 = Color(0xFF12234D)
    val linha = Color(0xFF22366B)
    val texto = Color(0xFFE7EEFC)
    val apagado = Color(0xFF93A4C8)
    val a1 = Color(0xFF3B8CFF)
    val a2 = Color(0xFF67C6FF)
    val tinta = Color(0xFF04122E)
    val ok = Color(0xFF3CCF7A)
    val ruim = Color(0xFFFF5D6C)
    val destaque = Brush.linearGradient(listOf(a1, a2))
}
