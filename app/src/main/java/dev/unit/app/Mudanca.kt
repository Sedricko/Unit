package dev.unit.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/** Um resultado da busca por mudança: o valor de agora e o da foto. */
data class Mudou(val addr: String, val tipo: String, val valor: String, val foto: String)

/**
 * LABORATÓRIO (teste): busca por mudança, para valores que o jogo muda sozinho (o "valor desconhecido" do GameGuardian).
 * Foto da memória; depois mudou / não mudou / aumentou / diminuiu / voltou ao da foto. Tocar num resultado abre o Editar.
 * Some junto com a tela "mudanca" do Balao.kt se não der certo.
 */
object Mudanca {
    private var pid by mutableStateOf(0)
    private var foto by mutableStateOf(false)
    private var total by mutableStateOf<Long?>(null)
    private val lista = mutableStateListOf<Mudou>()

    private fun zerar() { foto = false; total = null; lista.clear() }

    private suspend fun comparar(modo: String) {
        total = Nucleo.cmd { it.cmd("diff $modo") }.status.substringBefore(' ').toLongOrNull()
        lista.clear()
        if ((total ?: 0) in 1..50) lista += Nucleo.cmd { it.cmd("list 50") }.linhas.map { it.split(' ') }.filter { it.size == 4 }.map { Mudou(it[0], it[1], it[2], it[3]) }
    }

    @Composable
    fun Tela(ctx: Context, abrir: (Achado) -> Unit) = Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        (Nucleo.jogo?.pid ?: 0).let { if (it != pid) { pid = it; zerar() } }  // trocou de jogo: do zero
        Text("Tire a foto. Depois, cada vez que o número mudar (ou não) no jogo, toque no filtro que combina. Repita até sobrar pouco.",
            color = Cor.apagado, fontSize = 12.sp)
        if (!foto) {
            Botao("Tirar a foto", true, Modifier.fillMaxWidth(), !Nucleo.ocupado) {
                Nucleo.zerar(); Nucleo.rodar { Nucleo.cmd { it.cmd("snap ${File(ctx.cacheDir, "foto.bin").absolutePath}") }; foto = true; total = null; lista.clear() }  // o motor tem uma lista só: a busca do Achar recomeça
            }
            return@Column
        }
        Linha {
            Text(total?.let { "$it resultado(s)" } ?: "Foto tirada", color = Cor.texto, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            if (Nucleo.ocupado) Text("olhando…", color = Cor.a2, fontSize = 12.sp)
        }
        // na 1ª comparação só dá para "mudou", "aumentou" ou "diminuiu" (comparar "igual" com a memória inteira seria tudo)
        listOf(listOf("mudou" to "Mudou", "igual" to "Não mudou", "foto" to "Igual à foto"), listOf("aumentou" to "Aumentou", "diminuiu" to "Diminuiu")).forEach { linha ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                linha.forEach { (m, n) -> Botao(n, m == "mudou", Modifier.weight(1f), !Nucleo.ocupado && (total != null || m !in setOf("igual", "foto"))) { Nucleo.rodar { comparar(m) } } }
            }
        }
        // como no Achar: o que já foi salvo sai daqui (fica em Salvos); cada um tem o ⊕ e há o "Salvar todos"
        val novos = lista.filter { m -> Nucleo.salvos.none { it.addr == m.addr } }
        novos.forEach { m ->
            val a = Achado(m.addr, m.tipo, m.valor)
            Linha(Modifier.toque { abrir(a) }) {
                Column(Modifier.weight(1f)) {
                    Text("${m.foto} → ${m.valor}", color = Cor.texto, fontSize = 14.sp, maxLines = 1)
                    Text("${m.addr} · ${NOME_TIPO[m.tipo] ?: m.tipo}", color = Cor.apagado, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                }
                Box(Modifier.padding(start = 10.dp).toque { Nucleo.salvar(a) }) { Icone(Ic.SALVAR, 20.dp) }
                // congelar no valor de agora (vai para os Salvos já congelado)
                Box(Modifier.padding(start = 10.dp).toque { Nucleo.editar(a.addr, a.tipo, a.valor, true) }) { Icone(Ic.CONGELAR, 20.dp) }
            }
        }
        if (novos.size >= 2) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Botao("Salvar todos (${novos.size})", false, Modifier.weight(1f)) { novos.forEach { Nucleo.salvar(Achado(it.addr, it.tipo, it.valor)) } }
            Botao("Congelar todos", false, Modifier.weight(1f)) { novos.forEach { Nucleo.editar(it.addr, it.tipo, it.valor, true) } }
        }
        if ((total ?: 0) > 50) Text("Ainda são muitos: espere o número mudar de novo e filtre outra vez.", color = Cor.apagado, fontSize = 12.sp)
        Botao("Recomeçar", false, Modifier.fillMaxWidth()) { zerar(); File(ctx.cacheDir, "foto.bin").delete() }
    }
}
