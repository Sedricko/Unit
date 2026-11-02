package dev.unit.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Uma linha do registro: hora, tipo (busca, caminho, jogo, erro, outro), o texto e o detalhe (ex.: os caminhos que saíram e entraram). */
data class Evento(val hora: String, val tipo: String, val texto: String, val detalhe: List<String> = emptyList())

/** Um item do autoteste. */
data class Teste(val ok: Boolean, val nome: String, val como: String)

/**
 * LABORATÓRIO (teste): o que acontece dentro do Unit, para compartilhar. Registro (as últimas 500 linhas, guardadas num
 * arquivo do app), autoteste (8 conferências sem mexer no jogo) e um texto só para mandar na conversa.
 */
object Registro {
    private const val MAX = 500
    val linhas = mutableStateListOf<Evento>()  // a mais nova primeiro
    private var arquivo: File? = null
    private var pasta: File? = null
    private var versao = ""

    fun init(ctx: Context) {
        arquivo = File(ctx.filesDir, "registro.txt"); pasta = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        versao = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
        if (linhas.isEmpty()) runCatching { linhas += arquivo!!.readLines().mapNotNull(::deLinha) }
    }

    // uma linha por evento no arquivo: hora \t tipo \t texto \t detalhe1 | detalhe2
    private fun paraLinha(e: Evento) = listOf(e.hora, e.tipo, e.texto.replace('\t', ' ').replace('\n', ' '), e.detalhe.joinToString(" | ")).joinToString("\t")
    fun deLinha(l: String) = l.split('\t').takeIf { it.size >= 3 }?.let { Evento(it[0], it[1], it[2], it.getOrNull(3)?.split(" | ")?.filter(String::isNotBlank).orEmpty()) }

    fun add(tipo: String, texto: String, detalhe: List<String> = emptyList()) {
        linhas.add(0, Evento(SimpleDateFormat("dd/MM HH:mm:ss", Locale.ROOT).format(Date()), tipo, texto, detalhe))
        while (linhas.size > MAX) linhas.removeAt(linhas.lastIndex)
        runCatching { arquivo?.writeText(linhas.joinToString("\n", transform = ::paraLinha)) }
    }

    /** As 8 conferências. Só lê: não escreve nada no jogo. */
    suspend fun autoteste(ctx: Context): List<Teste> {
        val t = mutableListOf<Teste>()
        val ola = runCatching { Nucleo.cmd { it.ola } }
        t += Teste(ola.getOrNull()?.contains("uid=0") == true, "Root",
            ola.fold({ if ("uid=0" in it) "motor abriu pelo su (uid 0)" else "motor sem root: $it" }, { "não abriu: ${it.message}" }))
        val ms = runCatching { val c = System.nanoTime(); Nucleo.cmd { it.cmd("sleep 0") }; (System.nanoTime() - c) / 1_000_000 }
        t += Teste(ms.isSuccess, "Motor", ms.fold({ "respondeu em $it ms" }, { "não respondeu: ${it.message}" }))
        val j = Nucleo.jogo
        val info = if (j == null) null else runCatching { Nucleo.cmd { it.cmd("info") }.status.split(' ') }
        t += Teste(info?.isSuccess == true, "Jogo", when {
            j == null -> "nenhum jogo escolhido (Jogos → escolha o jogo)"
            info!!.isFailure -> "${j.nome}: ${info.exceptionOrNull()?.message}"
            else -> "${j.nome} aberto · ${info.getOrThrow()[0]} regiões · ${info.getOrThrow().getOrNull(1)} MB legíveis"
        })
        val lidos = Nucleo.salvos.count { s -> runCatching { Nucleo.cmd { it.cmd("read ${s.addr} ${s.tipo}") } }.isSuccess }
        t += Teste(lidos == Nucleo.salvos.size, "Leitura ao vivo", if (Nucleo.salvos.isEmpty()) "nenhum salvo para ler" else "$lidos de ${Nucleo.salvos.size} salvos lidos agora")
        val noMotor = runCatching { Nucleo.cmd { it.cmd("frozen") }.status.toInt() }.getOrDefault(-1)
        val noApp = Nucleo.salvos.count { it.congelado }
        t += Teste(noMotor == noApp, "Congelados", if (noMotor < 0) "o motor não respondeu" else "$noMotor no motor · $noApp no app")
        val cam = j?.let { Ponteiro.lista(it.pacote) }.orEmpty()
        t += Teste(true, "Caminhos", if (cam.isEmpty()) "nenhum guardado neste jogo" else cam.joinToString(" · ") { "${it.nome} ${it.caminhos.size} na reserva" })
        val sobrepor = Settings.canDrawOverlays(ctx)
        t += Teste(sobrepor, "Permissão de sobrepor", if (sobrepor) "liberada" else "falta liberar: Configurações → Apps → Unit → Sobrepor")
        t += Teste(Atualizador.nova == null, "Atualização", if (Atualizador.nova == null) "versão $versao" else "${Atualizador.nova} disponível" + if (versao.endsWith("-lab")) " (este é um APK de laboratório)" else "")
        add("outro", "Autoteste: ${t.count { it.ok }}/${t.size}", t.filterNot { it.ok }.map { "✗ ${it.nome}: ${it.como}" })
        return t
    }

    /** Um texto só: cabeçalho, autoteste e o registro (com os caminhos que saíram e entraram). */
    fun texto(testes: List<Teste>?): String = buildString {
        appendLine("RELATÓRIO DO UNIT · ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ROOT).format(Date())}")
        appendLine("Unit $versao · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Jogo: ${Nucleo.jogo?.let { "${it.nome} (${it.pacote}, pid ${it.pid})" } ?: "nenhum"}")
        testes?.let { l ->
            appendLine(); appendLine("AUTOTESTE ${l.count { it.ok }}/${l.size}")
            l.forEach { appendLine("${if (it.ok) "✓" else "✗"} ${it.nome}: ${it.como}") }
        }
        appendLine(); appendLine("REGISTRO (${linhas.size} linhas, a mais nova primeiro)")
        linhas.forEach { e -> appendLine("${e.hora} [${e.tipo}] ${e.texto}"); e.detalhe.forEach { appendLine("    $it") } }
    }

    /** Abre a lista do Android (WhatsApp, e-mail, copiar…) e guarda uma cópia em relatorio-unit.txt. */
    fun compartilhar(ctx: Context, testes: List<Teste>?) {
        val txt = texto(testes)
        runCatching { File(pasta, "relatorio-unit.txt").writeText(txt) }
        val envio = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Relatório do Unit").putExtra(Intent.EXTRA_TEXT, txt)
        ctx.startActivity(Intent.createChooser(envio, "Compartilhar relatório do Unit").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun cor(tipo: String) = when (tipo) { "erro" -> Cor.ruim; "jogo" -> Color(0xFFFFB86B); "caminho" -> Cor.a2; else -> Cor.texto }

    @Composable
    fun TelaAutoteste(ctx: Context, verRegistro: () -> Unit) = Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var testes by remember { mutableStateOf<List<Teste>?>(null) }
        fun rodar() = Nucleo.rodar { testes = autoteste(ctx) }
        val l = testes
        if (l == null) {
            Text("Confere root, motor, jogo, leitura, congelados, caminhos, permissão e atualização. Só lê: não mexe no jogo.", color = Cor.apagado, fontSize = 12.sp)
            Botao(if (Nucleo.ocupado) "Conferindo…" else "Rodar autoteste", true, Modifier.fillMaxWidth(), !Nucleo.ocupado) { rodar() }
        } else {
            Linha {
                Column(Modifier.weight(1f)) {
                    Text(Nucleo.jogo?.nome ?: "Sem jogo", color = Cor.texto, fontWeight = FontWeight.Bold)
                    Text("Unit $versao · Android ${Build.VERSION.RELEASE}", color = Cor.apagado, fontSize = 11.sp)
                }
                Text("${l.count { it.ok }}/${l.size}", color = if (l.all { it.ok }) Cor.ok else Cor.a2, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            l.forEach { t ->
                Row {
                    Text(if (t.ok) "✓" else "✗", color = if (t.ok) Cor.ok else Cor.ruim, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.nome, color = Cor.texto, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(t.como, color = Cor.apagado, fontSize = 12.sp)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Botao("Rodar de novo", false, Modifier.weight(1f), !Nucleo.ocupado) { rodar() }
                Botao("Compartilhar", true, Modifier.weight(1f)) { compartilhar(ctx, l) }
            }
        }
        Botao("Ver o registro (${linhas.size})", false, Modifier.fillMaxWidth(), clique = verRegistro)
    }

    @Composable
    fun TelaRegistro(ctx: Context) = Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var filtro by remember { mutableStateOf("tudo") }
        val abertos = remember { mutableStateListOf<Evento>() }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("tudo" to "Tudo", "busca" to "Busca", "caminho" to "Caminhos", "erro" to "Erros").forEach { (f, n) -> Selo(n, filtro == f) { filtro = f } }
        }
        val vistos = linhas.filter { filtro == "tudo" || it.tipo == filtro || (filtro == "busca" && it.tipo == "jogo") }
        if (vistos.isEmpty()) Text("Nada registrado ainda.", color = Cor.apagado, fontSize = 12.sp)
        vistos.take(200).forEach { e ->
            Column(Modifier.fillMaxWidth().toque(e.detalhe.isNotEmpty()) { if (e in abertos) abertos.remove(e) else abertos += e }.padding(vertical = 3.dp)) {
                Row {
                    Text(e.hora.substringAfter(' '), color = Cor.apagado, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(62.dp))
                    Text(e.texto + if (e.detalhe.isNotEmpty()) (if (e in abertos) "  ▾" else "  ▸") else "", color = cor(e.tipo), fontSize = 12.sp, modifier = Modifier.weight(1f))
                }
                if (e in abertos) e.detalhe.forEach { d ->
                    Text(d, color = if (d.startsWith("–")) Cor.ruim else if (d.startsWith("+")) Cor.ok else Cor.apagado, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 62.dp))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Botao("Limpar", false, Modifier.weight(1f)) { linhas.clear(); runCatching { arquivo?.writeText("") } }
            Botao("Compartilhar", true, Modifier.weight(1f)) { compartilhar(ctx, null) }
        }
    }
}
