package dev.unit.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** As cores do DESIGN.md: vidro azul-noite, uma cor só nos ícones, um azul de destaque. Sem ícones coloridos. */
object Cor {
    val vidro = Brush.verticalGradient(listOf(Color(0xE6283A6E), Color(0xF00E162C)))
    val fundo = Color(0xFF071026)
    val brilho = Color(0xFF1D4FB8)
    val bloco = Color(0x14FFFFFF)
    val linha = Color(0x24FFFFFF)
    val texto = Color(0xFFEEF3FF)
    val apagado = Color(0xA8E1E9FF)
    val icone = Color(0xFFCFDAF5)
    val a1 = Color(0xFF3B8CFF)
    val a2 = Color(0xFF67C6FF)
    val tinta = Color(0xFF04122E)
    val ok = Color(0xFF3CCF7A)
    val ruim = Color(0xFFFF5D6C)
    val destaque = Brush.linearGradient(listOf(a1, a2))
}

/** Ícones de linha desenhados (uma cor só), como na prévia. */
enum class Ic { ACHAR, EDITAR, CONGELAR, SALVOS, PAUSAR, CONTINUAR, MINIMIZAR, EXPANDIR, FECHAR, APAGAR, JOGO }

@Composable
fun Icone(i: Ic, tam: Dp = 22.dp, cor: Color = Cor.icone) = Canvas(Modifier.size(tam)) {
    val s = size.minDimension; val w = s * 0.075f
    val st = Stroke(w, cap = StrokeCap.Round)
    fun l(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(cor, Offset(x1 * s, y1 * s), Offset(x2 * s, y2 * s), w, StrokeCap.Round)
    when (i) {
        Ic.ACHAR -> { drawCircle(cor, s * 0.28f, Offset(s * 0.44f, s * 0.44f), style = st); l(.65f, .65f, .88f, .88f) }
        Ic.EDITAR -> { l(.2f, .8f, .2f, .64f); l(.2f, .64f, .66f, .18f); l(.66f, .18f, .82f, .34f); l(.82f, .34f, .36f, .8f); l(.36f, .8f, .2f, .8f) }
        Ic.CONGELAR -> { l(.5f, .1f, .5f, .9f); l(.15f, .3f, .85f, .7f); l(.15f, .7f, .85f, .3f); l(.4f, .16f, .5f, .26f); l(.6f, .16f, .5f, .26f); l(.4f, .84f, .5f, .74f); l(.6f, .84f, .5f, .74f) }
        Ic.SALVOS -> { listOf(.28f, .5f, .72f).forEach { y -> l(.34f, y, .86f, y); l(.15f, y, .17f, y) } }
        Ic.PAUSAR -> { l(.36f, .24f, .36f, .76f); l(.64f, .24f, .64f, .76f) }
        Ic.CONTINUAR -> { l(.32f, .2f, .32f, .8f); l(.32f, .2f, .8f, .5f); l(.32f, .8f, .8f, .5f) }
        Ic.MINIMIZAR -> l(.22f, .5f, .78f, .5f)
        Ic.EXPANDIR -> drawRoundRect(cor, Offset(s * .22f, s * .22f), androidx.compose.ui.geometry.Size(s * .56f, s * .56f), androidx.compose.ui.geometry.CornerRadius(s * .1f), style = st)
        Ic.FECHAR -> { l(.26f, .26f, .74f, .74f); l(.74f, .26f, .26f, .74f) }
        Ic.APAGAR -> { l(.38f, .25f, .88f, .25f); l(.88f, .25f, .88f, .75f); l(.88f, .75f, .38f, .75f); l(.38f, .75f, .12f, .5f); l(.12f, .5f, .38f, .25f); l(.5f, .38f, .74f, .62f); l(.74f, .38f, .5f, .62f) }
        Ic.JOGO -> { drawRoundRect(cor, Offset(s * .12f, s * .3f), androidx.compose.ui.geometry.Size(s * .76f, s * .42f), androidx.compose.ui.geometry.CornerRadius(s * .18f), style = st); l(.3f, .43f, .3f, .59f); l(.22f, .51f, .38f, .51f) }
    }
}

@Composable
fun Rotulo(t: String) = Text(t.uppercase(), color = Cor.apagado, fontSize = 11.sp, letterSpacing = 1.5.sp)

/** Botão quadrado de ícone (minimizar, fechar, pausar). */
@Composable
fun BotaoIcone(i: Ic, clique: () -> Unit) = Box(
    Modifier.size(40.dp).background(Cor.bloco, RoundedCornerShape(12.dp)).border(1.dp, Cor.linha, RoundedCornerShape(12.dp)).clickable(onClick = clique),
    contentAlignment = Alignment.Center
) { Icone(i, 18.dp) }

/** Bloco da grade: ícone de linha e um nome curto. Desligado = ainda não funciona (fica apagado). */
@Composable
fun Bloco(i: Ic, nome: String, ligado: Boolean = true, modifier: Modifier = Modifier, clique: () -> Unit) = Column(
    modifier.alpha(if (ligado) 1f else .35f).background(Cor.bloco, RoundedCornerShape(18.dp)).border(1.dp, Cor.linha, RoundedCornerShape(18.dp))
        .clickable(enabled = ligado, onClick = clique).padding(vertical = 14.dp),
    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)
) { Icone(i); Text(nome, color = Cor.texto, fontSize = 13.sp) }

@Composable
fun Linha(modifier: Modifier = Modifier, conteudo: @Composable RowScope.() -> Unit) = Row(
    modifier.fillMaxWidth().background(Cor.bloco, RoundedCornerShape(14.dp)).border(1.dp, Cor.linha, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically, content = conteudo
)

@Composable
fun Botao(t: String, principal: Boolean, modifier: Modifier = Modifier, ligado: Boolean = true, clique: () -> Unit) = Box(
    modifier.alpha(if (ligado) 1f else .45f)
        .then(if (principal) Modifier.background(Cor.destaque, RoundedCornerShape(14.dp)) else Modifier.background(Cor.bloco, RoundedCornerShape(14.dp)).border(1.dp, Cor.linha, RoundedCornerShape(14.dp)))
        .clickable(enabled = ligado, onClick = clique).padding(vertical = 12.dp, horizontal = 14.dp),
    contentAlignment = Alignment.Center
) { Text(t, color = if (principal) Cor.tinta else Cor.texto, fontWeight = FontWeight.Bold, fontSize = 15.sp) }

@Composable
fun Selo(t: String, ligado: Boolean, clique: (() -> Unit)? = null) = Row(
    Modifier.border(1.dp, if (ligado) Color(0xCC67C6FF) else Cor.linha, RoundedCornerShape(50)).then(if (clique != null) Modifier.clickable(onClick = clique) else Modifier)
        .padding(horizontal = 12.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically
) { Text(t, color = if (ligado) Cor.texto else Cor.apagado, fontSize = 13.sp) }

/** O campo do teclado próprio: o número grande e um cursor. */
@Composable
fun CampoGrande(valor: String, dica: String) = Row(
    Modifier.fillMaxWidth().background(Color(0x40000000), RoundedCornerShape(16.dp)).border(1.dp, Color(0x9967C6FF), RoundedCornerShape(16.dp))
        .padding(horizontal = 16.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically
) {
    Text(valor.ifEmpty { " " }, color = Cor.texto, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    Box(Modifier.padding(start = 4.dp).size(2.dp, 26.dp).background(Cor.a2))
    Box(Modifier.weight(1f))
    Text(dica, color = Cor.apagado, fontSize = 12.sp)
}

/** O teclado próprio do Unit (vidro, sempre no mesmo lugar): números, vírgula, negativo, apagar e o botão de ação. */
@Composable
fun Teclado(acao: String, ligado: Boolean, digitou: (String) -> Unit, apagou: () -> Unit, confirmou: () -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    @Composable fun Tecla(t: String, mod: Modifier, f: () -> Unit) = Box(
        mod.background(Cor.bloco, RoundedCornerShape(14.dp)).border(1.dp, Cor.linha, RoundedCornerShape(14.dp)).clickable(onClick = f).padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) { Text(t, color = Cor.texto, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
    listOf(listOf("7", "8", "9"), listOf("4", "5", "6"), listOf("1", "2", "3")).forEachIndexed { r, linha ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            linha.forEach { d -> Tecla(d, Modifier.weight(1f)) { digitou(d) } }
            when (r) {
                0 -> Box(Modifier.weight(1f).background(Cor.bloco, RoundedCornerShape(14.dp)).border(1.dp, Cor.linha, RoundedCornerShape(14.dp)).clickable(onClick = apagou).padding(vertical = 13.dp), contentAlignment = Alignment.Center) { Icone(Ic.APAGAR, 20.dp) }
                1 -> Tecla(",", Modifier.weight(1f)) { digitou(",") }
                else -> Tecla("−", Modifier.weight(1f)) { digitou("-") }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.weight(1f))
        Tecla("0", Modifier.weight(1f)) { digitou("0") }
        Botao(acao, true, Modifier.weight(2f), ligado, confirmou)
    }
}
