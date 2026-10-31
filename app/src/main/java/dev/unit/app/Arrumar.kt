package dev.unit.app

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
    var editando by mutableStateOf(false)
    var areaCima by mutableStateOf(Rect.Zero)   // onde as barras estão na tela (para saber onde o botão foi solto)
    var areaBaixo by mutableStateOf(Rect.Zero)

    fun carregar(p: SharedPreferences) {
        val c = p.getString("cima", null)?.split(',')?.filter { it in PADRAO } ?: PADRAO
        val b = p.getString("baixo", "")!!.split(',').filter { it in PADRAO && it !in c }
        cima.clear(); cima += c + PADRAO.filter { it !in c && it !in b }  // botão novo de versão futura entra em cima
        baixo.clear(); baixo += b
    }

    fun guardar(p: SharedPreferences) = p.edit().putString("cima", cima.joinToString(",")).putString("baixo", baixo.joinToString(",")).apply()

    /** O botão foi solto com o centro em [c]: vai para a barra onde caiu, na posição pelo x; fora das barras, volta. */
    fun soltar(id: String, c: Offset, passo: Float) {
        val (lista, area) = when {
            areaBaixo.inflate(passo / 2).contains(c) -> baixo to areaBaixo
            areaCima.inflate(passo / 2).contains(c) -> cima to areaCima
            else -> return
        }
        // a barra de cima é alinhada à direita (o título fica à esquerda): conta a posição a partir da direita
        val i = if (lista === cima) (lista.size - ((area.right - c.x) / passo).toInt()) else ((c.x - area.left) / passo).toInt()
        cima.remove(id); baixo.remove(id)
        lista.add(i.coerceIn(0, lista.size), id)
    }
}
