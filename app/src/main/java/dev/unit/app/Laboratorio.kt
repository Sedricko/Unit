package dev.unit.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * LABORATÓRIO (teste): assinaturas. Grava o "desenho" de bytes em volta de um valor salvo e, com o jogo reaberto, procura
 * esse desenho para achar o endereço novo sozinho. Tudo aqui fica neste arquivo: se não funcionar, apaga o arquivo e a
 * entrada do painel, e o resto do Unit não muda.
 */
data class Assinatura(val nome: String, val tipo: String, val padrao: String, val desloc: Int, val valor: String, val congelado: Boolean)

object Laboratorio {
    private const val ANTES = 32
    private const val DEPOIS = 32
    val log = mutableStateListOf<String>()

    /** Monta o padrão: lê duas vezes com 1,5 s de intervalo; o que mudou, o próprio valor e o que parece ponteiro viram "??". */
    fun montarPadrao(addr: Long, tam: Int, a: ByteArray, b: ByteArray): String {
        val ini = addr - ANTES
        val livre = BooleanArray(a.size) { a[it] != b[it] }
        for (i in ANTES until ANTES + tam) livre[i] = true
        // palavras de 8 bytes alinhadas que parecem endereço (0x0000007x..., 0x00007f..., ou com etiqueta 0xb4 no topo)
        var i = ((8 - (ini % 8)) % 8).toInt()
        while (i + 8 <= a.size) {
            var w = 0L; for (k in 7 downTo 0) w = (w shl 8) or (a[i + k].toLong() and 0xff)
            val alto = w ushr 32  // 0x0000007x… (arm64), 0x00007x… (x86_64), 0xb4… (etiqueta do Android)
            if (alto in 0x70L..0x7fL || alto in 0x7000L..0x7fffL || (alto ushr 24) == 0xb4L) for (k in 0 until 8) livre[i + k] = true
            i += 8
        }
        return a.indices.joinToString("") { if (livre[it]) "??" else "%02x".format(a[it]) }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("lab-assinaturas", Context.MODE_PRIVATE)

    fun lista(ctx: Context): List<Assinatura> {
        val j = JSONArray(prefs(ctx).getString(Nucleo.jogo?.pacote ?: return emptyList(), "[]"))
        return (0 until j.length()).map { j.getJSONObject(it) }.map {
            Assinatura(it.getString("nome"), it.getString("tipo"), it.getString("padrao"), it.getInt("desloc"), it.getString("valor"), it.getBoolean("congelado"))
        }
    }

    private fun guardar(ctx: Context, l: List<Assinatura>) {
        val j = JSONArray(); l.forEach { a ->
            j.put(JSONObject().put("nome", a.nome).put("tipo", a.tipo).put("padrao", a.padrao).put("desloc", a.desloc).put("valor", a.valor).put("congelado", a.congelado))
        }
        prefs(ctx).edit().putString(Nucleo.jogo?.pacote ?: return, j.toString()).apply()
    }

    fun gravar(ctx: Context, s: Salvo) = Nucleo.rodar {
        val addr = s.addr.toLong(16); val tam = if (s.tipo.endsWith("64")) 8 else 4
        val n = ANTES + tam + DEPOIS
        suspend fun ler() = hex(Nucleo.cmd { it.cmd("bytes ${(addr - ANTES).toString(16)} $n") }.status)
        val a = ler(); delay(1500); val b = ler()
        val p = montarPadrao(addr, tam, a, b)
        val fixos = p.chunked(2).count { it != "??" }
        if (fixos < 8) { log.add(0, "${s.nome}: em volta tudo muda (só $fixos bytes fixos); não dá assinatura"); return@rodar }
        guardar(ctx, lista(ctx).filter { it.nome != s.nome } + Assinatura(s.nome, s.tipo, p, ANTES, s.valor, s.congelado))
        // confere já: o desenho precisa achar um lugar só
        val r = Nucleo.cmd { it.cmd("aob $p") }
        log.add(0, "${s.nome}: assinatura gravada ($fixos bytes fixos), hoje acha ${r.status} lugar(es)" + if (r.status == "1") " ✓" else " — pode confundir")
    }

    /** Com o jogo reaberto: procura cada desenho; achou um lugar só, o valor volta para os salvos (e congela de novo). */
    fun procurar(ctx: Context) = Nucleo.rodar {
        val l = lista(ctx)
        if (l.isEmpty()) { log.add(0, "nenhuma assinatura gravada para este jogo"); return@rodar }
        for (a in l) {
            val r = Nucleo.cmd { it.cmd("aob ${a.padrao}") }
            if (r.status != "1") { log.add(0, "${a.nome}: ${if (r.status == "0") "não achei" else "${r.status} lugares, não sei qual"}"); continue }
            val addr = (r.linhas.first().toLong(16) + a.desloc).toString(16)
            val agora = Nucleo.cmd { it.cmd("read $addr ${a.tipo}") }.status
            Nucleo.salvos.removeAll { it.nome == a.nome }
            Nucleo.salvos += Salvo(addr, a.tipo, a.nome, agora, false)
            if (a.congelado) Nucleo.editar(addr, a.tipo, a.valor, true)
            log.add(0, "${a.nome}: achei em 0x$addr (agora $agora)" + if (a.congelado) ", congelei em ${a.valor}" else "")
        }
    }

    fun apagar(ctx: Context) { guardar(ctx, emptyList()); log.add(0, "assinaturas deste jogo apagadas") }

    @Composable
    fun Tela(ctx: Context) = Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Teste: o Unit guarda o desenho da memória em volta de cada salvo e, com o jogo reaberto, acha de novo sozinho.",
            color = Cor.apagado, fontSize = 12.sp)
        Rotulo("1. Gravar (com o jogo aberto)")
        if (Nucleo.salvos.isEmpty()) Text("Salve um valor primeiro (Achar → ⊕).", color = Cor.apagado, fontSize = 13.sp)
        Nucleo.salvos.forEach { s -> Botao("Gravar assinatura: ${s.nome}", false, Modifier.fillMaxWidth(), !Nucleo.ocupado) { gravar(ctx, s) } }
        Rotulo("2. Depois de reabrir o jogo")
        Text("Reabra o jogo, toque em Trocar e escolha ele de novo (o processo é outro); depois, Procurar agora.", color = Cor.apagado, fontSize = 12.sp)
        val gravadas = lista(ctx)
        Text(if (gravadas.isEmpty()) "Nenhuma gravada." else "Gravadas: " + gravadas.joinToString { it.nome }, color = Cor.texto, fontSize = 13.sp)
        Botao("Procurar agora", true, Modifier.fillMaxWidth(), gravadas.isNotEmpty() && !Nucleo.ocupado) { procurar(ctx) }
        if (gravadas.isNotEmpty()) Botao("Apagar assinaturas", false, Modifier.fillMaxWidth()) { apagar(ctx) }
        if (log.isNotEmpty()) {
            Rotulo("Resultado")
            log.take(12).forEach { Text(it, color = Cor.texto, fontSize = 12.sp, fontWeight = FontWeight.Normal, modifier = Modifier.padding(vertical = 2.dp)) }
        }
    }
}
