package dev.unit.app

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * LABORATÓRIO (teste): arrumar os botões do painel. Segura um botão de controle → modo arrumar (os botões tremem); arrasta
 * para a barra de cima ou para a de baixo (perto do teclado) e muda a ordem; "Pronto" guarda. Sai junto com este arquivo.
 */
object Arrumar {
    val PADRAO = listOf("olho", "pausa", "expandir", "minimizar", "fechar")
    val cima = mutableStateListOf<String>()
    val baixo = mutableStateListOf<String>()
    val livre = mutableStateMapOf<String, Offset>()  // soltos em qualquer ponto do painel (posição dentro dele)
    var areaPainel by mutableStateOf(Rect.Zero)
    var editando by mutableStateOf(false)
    var areaCima by mutableStateOf(Rect.Zero)   // onde as barras estão na tela (para saber onde o botão foi solto)
    var areaBaixo by mutableStateOf(Rect.Zero)

    fun carregar(p: SharedPreferences) {
        // "olho@120,340": solto no painel
        val l = p.getString("livre", "")!!.split(';').mapNotNull { e ->
            val id = e.substringBefore('@'); val xy = e.substringAfter('@', "").split(',').mapNotNull { it.toFloatOrNull() }
            if (id in PADRAO && xy.size == 2) id to Offset(xy[0], xy[1]) else null
        }.toMap()
        val c = (p.getString("cima", null)?.split(',')?.filter { it in PADRAO } ?: PADRAO).filter { it !in l }
        val b = p.getString("baixo", "")!!.split(',').filter { it in PADRAO && it !in c && it !in l }
        cima.clear(); cima += c + PADRAO.filter { it !in c && it !in b && it !in l }  // botão novo de versão futura entra em cima
        baixo.clear(); baixo += b
        livre.clear(); livre.putAll(l)
    }

    fun guardar(p: SharedPreferences) = p.edit().putString("cima", cima.joinToString(",")).putString("baixo", baixo.joinToString(","))
        .putString("livre", livre.entries.joinToString(";") { "${it.key}@${it.value.x},${it.value.y}" }).apply()

    /** O botão foi solto com o centro em [c]: numa barra, entra nela pela posição; em outro ponto do painel, fica solto ali. */
    fun soltar(id: String, c: Offset, passo: Float, tam: Float = passo) {
        val (lista, area) = when {
            areaBaixo.inflate(passo / 2).contains(c) -> baixo to areaBaixo
            areaCima.inflate(passo / 2).contains(c) -> cima to areaCima
            areaPainel.contains(c) -> {
                cima.remove(id); baixo.remove(id)
                livre[id] = Offset((c.x - areaPainel.left - tam / 2).coerceIn(0f, (areaPainel.width - tam).coerceAtLeast(0f)),
                    (c.y - areaPainel.top - tam / 2).coerceIn(0f, (areaPainel.height - tam).coerceAtLeast(0f)))
                return
            }
            else -> return
        }
        livre.remove(id)
        // a barra de cima é alinhada à direita (o título fica à esquerda): conta a posição a partir da direita
        val i = if (lista === cima) (lista.size - ((area.right - c.x) / passo).toInt()) else ((c.x - area.left) / passo).toInt()
        cima.remove(id); baixo.remove(id)
        lista.add(i.coerceIn(0, lista.size), id)
    }
}
