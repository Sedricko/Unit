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
import androidx.compose.foundation.horizontalScroll
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
    private var dados: File? = null
    private var versao = ""
    private var codigo = 0L
    private var app: Context? = null

    private var iniciou = false
    private var androidDesde = System.currentTimeMillis() / 1000.0  // o logcat é lido daqui em diante (sem repetir)

    /** O que o Android anotou (logcat, pelo motor com root) sobre o jogo e o Unit: ANR, falhas, morto por memória. */
    suspend fun lerAndroid() {
        if (Nucleo.jogo == null) return  // o motor só lê com um jogo ligado (sem jogo virava "erro" à toa no registro)
        val pac = Nucleo.jogo?.pacote ?: "dev.unit.app"
        val agora = System.currentTimeMillis() / 1000.0
        val l = runCatching { Nucleo.cmd { it.cmd("android ${"%.3f".format(Locale.ROOT, androidDesde)} $pac") }.linhas }.getOrNull() ?: return
        androidDesde = agora
        if (l.isEmpty()) return
        val nome = Nucleo.jogo?.nome ?: pac
        if (l.any { "ANR in $pac" in it || ("am_anr" in it && pac in it) }) add("aviso", "O Android marcou $nome como \"não está respondendo\" (ANR)")
        if (l.any { ("lowmemorykiller" in it || "am_kill" in it) && pac in it }) add("aviso", "O Android fechou $nome por falta de memória")
        if (l.any { ("FATAL EXCEPTION" in it || "Fatal signal" in it || "am_crash" in it) && pac in it }) add("aviso", "$nome fechou com erro (falha do próprio jogo)")
        if (l.any { "dev.unit.app" in it && ("ANR in" in it || "FATAL" in it || "am_crash" in it) }) add("aviso", "O Android anotou um problema do próprio Unit")
        add("android", "Android: ${l.size} mensagem(ns) sobre $nome/Unit", l.takeLast(40).map { it.trim().take(400) })  // 400: o motivo do ANR inteiro
    }
    private var base: Triple<Long, Long, Long>? = null  // memória (Unit, motor, jogo) no 1º pulso: a régua do vazamento
    private var engasgos = 0; private var maiorEngasgo = 0L
    /** A tela ficou parada mais de 100 ms (o Balao mede enquanto o painel está aberto). */
    @Synchronized fun engasgo(ms: Long) { engasgos++; if (ms > maiorEngasgo) maiorEngasgo = ms; if (ms > 700) add("aviso", "A tela travou $ms ms", listOf(painel)) }

    /** Ligou num jogo: autoteste rápido em segundo plano, só o resultado vai para o registro. */
    fun autotesteRapido() { val c = app ?: return; Nucleo.semAviso { autoteste(c) } }

    /** Pulso: uma linha de como estão o jogo e os valores (a cada 10 min, para ver o que houve entre um evento e outro). */
    suspend fun pulso() {
        val j = Nucleo.jogo ?: return
        val info = runCatching { Nucleo.cmd { it.cmd("info") }.status.split(' ') }.getOrNull()
        val rt = Runtime.getRuntime(); val unitMb = (rt.totalMemory() - rt.freeMemory()) shr 20
        val motorMb = info?.getOrNull(2)?.toLongOrNull()?.div(1024); val jogoMb = info?.getOrNull(3)?.toLongOrNull()?.div(1024)
        if (base == null) base = Triple(unitMb, motorMb ?: 0, jogoMb ?: 0)
        base?.let { (u, m, jj) ->
            fun cresceu(nome: String, de: Long, para: Long?, limite: Long) { if (para != null && para - de > limite) add("aviso", "Possível vazamento: $nome foi de $de MB para $para MB desde o começo", listOf("comparado com o 1º pulso desta sessão")) }
            cresceu("o Unit", u, unitMb, 80); cresceu("o motor", m, motorMb, 300); cresceu("o jogo", jj, jogoMb, 800)
        }
        lerAndroid()
        val (eng, maior) = synchronized(this) { val x = engasgos to maiorEngasgo; engasgos = 0; maiorEngasgo = 0; x }
        if (eng > 0) add("aviso", "A tela engasgou $eng vez(es) nos últimos 5 min (o maior: $maior ms)")
        val congelados = runCatching { Nucleo.cmd { it.cmd("frozen") }.status }.getOrDefault("?")
        add("outro", "Pulso: ${j.nome} " + (info?.let { "aberto · ${it.getOrNull(1)} MB legíveis" } ?: "não respondeu") +
            " · memória: Unit $unitMb MB, motor ${motorMb ?: "?"} MB, jogo ${jogoMb ?: "?"} MB" +
            " · ${Nucleo.salvos.size} salvos · $congelados congelados · ${if (Nucleo.pausado) "pausado" else "rodando"} · motor ${comandos} cmds/${erros} erros")
        // congelado que não segura: o jogo conseguiu mudar o valor mesmo congelado
        Nucleo.salvos.filter { it.congelado }.forEach { s ->
            val agora = runCatching { Nucleo.cmd { it.cmd("read ${s.addr} ${s.tipo}") }.status }.getOrNull()
            if (agora != null && agora.toDoubleOrNull() != s.valor.toDoubleOrNull()) add("aviso", "Congelado não segurou: ${s.nome} está $agora (congelado em ${s.valor})", listOf("${s.addr} ${s.tipo}"))
        }
        app?.let { c ->
            val m = android.app.ActivityManager.MemoryInfo(); c.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(m)
            if (m.lowMemory) add("aviso", "Aparelho com pouca memória: ${mb(m.availMem)} livres (o Android pode fechar o jogo ou o Unit)")
            if (!Settings.canDrawOverlays(c)) add("aviso", "A permissão de sobrepor sumiu: o balão pode não aparecer")
        }
    }

    /** O estado do painel (o Balao escreve): modo, tela cheia, tela aberta, pausa automática. */
    @Volatile var painel = ""

    // ---- o motor: cada comando com resposta e tempo (os repetitivos, como a leitura ao vivo, só entram na conta) ----
    private val rastro = ArrayDeque<String>()
    private var comandos = 0L; private var erros = 0L; private var somaMs = 0L; private var maxMs = 0L
    @Volatile var aberturas = 0  // quantas vezes o motor abriu (mais de 1 = ele caiu e voltou)
    private var ultimaChave = ""; private var repetidas = 0
    private val REPETITIVOS = setOf("read", "vivo", "bytes", "frozen", "sleep", "info", "ptr")
    @Synchronized fun comando(cmd: String, resposta: String, ms: Long, erro: Boolean) {
        comandos++; somaMs += ms; if (ms > maxMs) maxMs = ms; if (erro) erros++
        val verbo = cmd.substringBefore(' ')
        if (ms > 1500 && verbo != "ptrscan") add("aviso", "Comando lento: $verbo (${"%.1f".format(ms / 1000.0)} s)", listOf(cmd.take(120)))
        if (erro && verbo !in REPETITIVOS && verbo != "vivo") add("erro", "Motor: $verbo → ${resposta.removePrefix("err ").take(120)}", listOf(cmd.take(160)))
        if (erro || cmd.substringBefore(' ') !in REPETITIVOS) {
            // o mesmo comando com a mesma resposta seguidas (ex.: 1000 "ptr … → o caminho quebrou") viram uma linha com a conta
            val chave = cmd.substringBefore(' ') + " → " + resposta.take(120)
            if (chave == ultimaChave && rastro.isNotEmpty()) { repetidas++; rastro.removeFirst(); rastro.addFirst("${SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())} $chave ×$repetidas (o último: ${cmd.take(80)}, ${ms} ms)"); return }
            ultimaChave = chave; repetidas = 1
            rastro.addFirst("${SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())} ${cmd.take(120)} → ${resposta.take(120)} (${ms} ms)")
            while (rastro.size > 80) rastro.removeLast()
        }
    }

    fun init(ctx: Context) {
        app = ctx.applicationContext
        dados = ctx.filesDir
        arquivo = File(ctx.filesDir, "registro.txt"); pasta = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()?.let {
            versao = it.versionName.orEmpty(); codigo = if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
        }
        if (linhas.isEmpty()) runCatching { linhas += arquivo!!.readLines().mapNotNull(::deLinha) }
        // o Unit fechou com erro da última vez? (o pegador abaixo grava o erro técnico completo)
        val falha = File(ctx.filesDir, "falha.txt")
        if (falha.exists()) { add("erro", "O Unit fechou com erro na última vez (detalhes no relatório)", falha.readLines().take(4)); falha.renameTo(File(ctx.filesDir, "falha-vista.txt")) }
        if (!iniciou) { iniciou = true; add("outro", "Unit abriu · $versao · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}") }
        val antes = Thread.getDefaultUncaughtExceptionHandler()
        if (antes !is Pegador) Thread.setDefaultUncaughtExceptionHandler(Pegador(antes))
    }

    /** Grava o erro que ia fechar o Unit (hora, versão, linha do código) e deixa o Android seguir como sempre. */
    private class Pegador(val antes: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching {
                val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw))
                File(dados, "falha.txt").writeText("${SimpleDateFormat("dd/MM HH:mm:ss", Locale.ROOT).format(Date())} · Unit $versao · thread ${t.name}\n$sw")
            }
            antes?.uncaughtException(t, e)
        }
    }

    // uma linha por evento no arquivo: hora \t tipo \t texto \t detalhe1 | detalhe2
    private fun paraLinha(e: Evento) = listOf(e.hora, e.tipo, e.texto.replace('\t', ' ').replace('\n', ' '), e.detalhe.joinToString(" | ")).joinToString("\t")
    fun deLinha(l: String) = l.split('\t').takeIf { it.size >= 3 }?.let { Evento(it[0], it[1], it[2], it.getOrNull(3)?.split(" | ")?.filter(String::isNotBlank).orEmpty()) }

    fun add(tipo: String, texto: String, detalhe: List<String> = emptyList()) {
        linhas.add(0, Evento(SimpleDateFormat("dd/MM HH:mm:ss", Locale.ROOT).format(Date()), tipo, texto, detalhe))
        while (linhas.size > MAX) linhas.removeAt(linhas.lastIndex)
        runCatching { arquivo?.writeText(linhas.joinToString("\n", transform = ::paraLinha)) }
    }

    private fun mb(b: Long) = "${b / (1024 * 1024)} MB"
    private fun selinux() = runCatching { ProcessBuilder("getenforce").start().inputStream.bufferedReader().readText().trim() }.getOrNull()?.ifEmpty { null } ?: "?"
    private fun espacoLivre() = dados?.usableSpace ?: 0L

    /** As conferências. Só lê: não escreve nada no jogo. */
    suspend fun autoteste(ctx: Context): List<Teste> {
        val t = mutableListOf<Teste>()
        val ola = runCatching { Nucleo.cmd { it.ola } }
        t += Teste(ola.getOrNull()?.contains("uid=0") == true, "Root",
            ola.fold({ if ("uid=0" in it) "motor abriu pelo su (uid 0)" else "motor sem root: $it" }, { "não abriu: ${it.message}" }))
        val ms = runCatching { val c = System.nanoTime(); Nucleo.cmd { it.cmd("sleep 0") }; (System.nanoTime() - c) / 1_000_000 }
        t += Teste(ms.getOrNull()?.let { it < 200 } == true, "Motor", ms.fold({ "respondeu em $it ms" + if (it >= 200) " (lento)" else "" }, { "não respondeu: ${it.message}" }))
        t += Teste(aberturas <= 1, "Motor estável", if (aberturas <= 1) "abriu 1 vez nesta sessão" else "abriu $aberturas vezes: ele caiu e voltou ${aberturas - 1} vez(es)")
        val j = Nucleo.jogo
        val info = if (j == null) null else runCatching { Nucleo.cmd { it.cmd("info") }.status.split(' ') }
        t += Teste(info?.isSuccess == true, "Jogo", when {
            j == null -> "nenhum jogo escolhido (Jogos → escolha o jogo)"
            info!!.isFailure -> "${j.nome}: ${info.exceptionOrNull()?.message}"
            else -> "${j.nome} aberto · ${info.getOrThrow()[0]} regiões · ${info.getOrThrow().getOrNull(1)} MB legíveis"
        })
        val vivos = Nucleo.salvos.filterNot { it.perdido }; val perdidos = Nucleo.salvos.size - vivos.size
        val lidos = vivos.count { s -> runCatching { Nucleo.cmd { it.cmd("read ${s.addr} ${s.tipo}") } }.isSuccess }
        t += Teste(lidos == vivos.size, "Leitura ao vivo", (if (vivos.isEmpty()) "nenhum salvo para ler" else "$lidos de ${vivos.size} salvos lidos agora") + if (perdidos > 0) " · $perdidos de antes ainda sem endereço" else "")
        val noMotor = if (Nucleo.jogo == null) 0 else runCatching { Nucleo.cmd { it.cmd("frozen") }.status.toInt() }.getOrDefault(-1)
        val noApp = Nucleo.salvos.count { it.congelado }
        t += Teste(noMotor == noApp, "Congelados", if (noMotor < 0) "o motor não respondeu" else "$noMotor no motor · $noApp no app")
        val cam = j?.let { Ponteiro.lista(it.pacote) }.orEmpty()
        val semCaminho = cam.filter { c -> Nucleo.salvos.none { it.nome == c.nome && it.caminho } }
        t += Teste(semCaminho.isEmpty(), "Caminhos", if (cam.isEmpty()) "nenhum guardado neste jogo" else
            cam.joinToString(" · ") { c -> "${c.nome} ${c.caminhos.size} na reserva" + if (c in semCaminho) " (não achou agora)" else "" })
        val sobrepor = Settings.canDrawOverlays(ctx)
        t += Teste(sobrepor, "Permissão de sobrepor", if (sobrepor) "liberada" else "falta liberar: Configurações → Apps → Unit → Sobrepor")
        val livre = espacoLivre()
        t += Teste(livre > 1024L * 1024 * 1024, "Espaço livre", "${mb(livre)} (a foto da busca por mudança pode precisar de algumas centenas de MB)")
        val ultimoErro = linhas.firstOrNull { it.tipo == "erro" }
        val avisos = linhas.take(100).count { it.tipo == "aviso" }
        t += Teste(linhas.take(50).none { it.tipo == "erro" }, "Erros recentes", (ultimoErro?.let { "último: ${it.hora} · ${it.texto}" } ?: "nenhum erro registrado") + if (avisos > 0) " · $avisos aviso(s) recentes" else "")
        val falhou = File(dados, "falha-vista.txt").exists()
        t += Teste(!falhou, "Fechou com erro", if (falhou) "o Unit já fechou com erro (o erro técnico vai no relatório)" else "nunca fechou com erro")
        t += Teste(Atualizador.nova == null, "Atualização", if (Atualizador.nova == null) "versão $versao" else "${Atualizador.nova} disponível" + if (versao.endsWith("-lab")) " (este é um APK de laboratório)" else "")
        add("outro", "Autoteste: ${t.count { it.ok }}/${t.size}", t.filterNot { it.ok }.map { "✗ ${it.nome}: ${it.como}" })
        return t
    }

    /** O relatório completo, em partes, com tudo o que ajuda a achar um defeito sem print. */
    suspend fun texto(testes: List<Teste>?): String {
        val ctx = app
        lerAndroid()  // o que o Android anotou até agora entra no registro antes de montar o relatório
        val se = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { selinux() }  // roda um programa: fora da tela
        val j = Nucleo.jogo
        val jogoInfo = if (j != null) runCatching { Nucleo.cmd { it.cmd("info") }.status }.getOrElse { "erro: ${it.message}" } else ""
        val congeladosMotor = if (j == null) emptyList() else runCatching { Nucleo.cmd { it.cmd("frozen") }.linhas }.getOrDefault(emptyList())
        return buildString {
            fun parte(n: Int, t: String) { appendLine(); appendLine("== $n. $t ==") }
            appendLine("RELATÓRIO DO UNIT · ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ROOT).format(Date())}")

            parte(1, "APARELHO")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Processador: ${Build.SUPPORTED_ABIS.joinToString()} · SELinux: $se")
            ctx?.resources?.displayMetrics?.let { d -> appendLine("Tela: ${d.widthPixels}x${d.heightPixels} px · ${d.densityDpi} dpi · ${if (d.widthPixels > d.heightPixels) "deitado" else "em pé"}") }
            ctx?.getSystemService(android.app.ActivityManager::class.java)?.let { am ->
                val m = android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(m)
                appendLine("RAM: ${mb(m.availMem)} livres de ${mb(m.totalMem)}" + if (m.lowMemory) " (pouca memória!)" else "")
            }
            appendLine("Espaço livre: ${mb(espacoLivre())}")

            parte(2, "APP")
            appendLine("Unit $versao (código $codigo)" + if (versao.endsWith("-lab")) " · laboratório" else " · normal")
            appendLine("Sobrepor: ${ctx?.let { if (Settings.canDrawOverlays(it)) "liberado" else "NÃO liberado" } ?: "?"}")
            appendLine("Painel: $painel")
            val rt = Runtime.getRuntime(); appendLine("Memória do Unit: ${mb(rt.totalMemory() - rt.freeMemory())} de ${mb(rt.maxMemory())}")
            if (Nucleo.aviso.isNotBlank()) appendLine("Aviso na tela: ${Nucleo.aviso}")

            parte(3, "MOTOR")
            appendLine("Abertura: ${runCatching { Nucleo.cmd { it.ola } }.getOrElse { "não abriu: ${it.message}" }} · abriu $aberturas vez(es)")
            synchronized(this@Registro) {
                appendLine("Comandos: $comandos · erros: $erros · tempo médio ${if (comandos > 0) somaMs / comandos else 0} ms · maior ${maxMs} ms")
                appendLine("Últimos comandos (o mais novo primeiro; leituras repetitivas ficam só na conta):")
                rastro.forEach { appendLine("  $it") }
            }

            parte(4, "JOGO")
            if (j == null) appendLine("nenhum jogo escolhido") else {
                val vj = ctx?.let { c -> runCatching { c.packageManager.getPackageInfo(j.pacote, 0).versionName }.getOrNull() }
                appendLine("${j.nome} · ${j.pacote} · versão ${vj ?: "?"} · pid ${j.pid}")
                appendLine("Memória legível: $jogoInfo (regiões MB) · pausado: ${Nucleo.pausado} · fechou: ${Nucleo.jogoFechou}")
            }

            parte(5, "VALORES")
            if (Nucleo.salvos.isEmpty()) appendLine("nenhum salvo")
            Nucleo.salvos.forEach { s -> appendLine("${s.nome} · ${s.addr} · ${NOME_TIPO[s.tipo] ?: s.tipo} · ${s.valor}" + (if (s.congelado) " · congelado" else "") + if (s.caminho) " · voltou sozinho" else if (s.perdido) " · endereço antigo" else "") }
            appendLine("Congelados no motor (${congeladosMotor.size}): ${congeladosMotor.joinToString()}")

            parte(6, "BUSCA")
            appendLine("Resultados: ${Nucleo.total ?: "nenhuma busca"} · na tela: ${Nucleo.achados.size} · tipo aprendido neste jogo: ${Nucleo.aprendido ?: "nenhum"}")
            Nucleo.achados.take(10).forEach { appendLine("  ${it.addr} ${it.tipo} ${it.valor}") }

            parte(7, "CAMINHOS")
            val cam = j?.let { Ponteiro.lista(it.pacote) }.orEmpty()
            if (cam.isEmpty()) appendLine("nenhum guardado neste jogo")
            cam.forEach { c -> appendLine("${c.nome} (${NOME_TIPO[c.tipo] ?: c.tipo}) · ${c.caminhos.size} na reserva" + (if (!c.conferido) " · a conferir" else "") + if (c.congelar) " · volta congelado em ${c.valor}" else ""); c.caminhos.take(3).forEach { appendLine("  $it") } }
            Ponteiro.pendente?.let { appendLine("Conferindo agora: ${it.nome} · ${it.caminhos.size} caminhos") }

            parte(8, "AUTOTESTE")
            if (testes == null) appendLine("(não rodado: abra Autoteste e toque em Rodar)") else {
                appendLine("${testes.count { it.ok }}/${testes.size}")
                testes.forEach { appendLine("${if (it.ok) "✓" else "✗"} ${it.nome}: ${it.como}") }
            }

            parte(9, "ÚLTIMA FALHA (o Unit fechou com erro)")
            val falha = dados?.let { File(it, "falha-vista.txt") }?.takeIf { it.exists() }
            if (falha == null) appendLine("nenhuma") else falha.readLines().take(40).forEach { appendLine(it) }

            parte(10, "REGISTRO (${linhas.size} linhas, a mais nova primeiro)")
            linhas.forEach { e -> appendLine("${e.hora} [${e.tipo}] ${e.texto}"); e.detalhe.forEach { appendLine("    $it") } }
        }
    }

    private var ultimosTestes: List<Teste>? = null

    /** Abre a lista do Android (WhatsApp, e-mail, copiar…) e guarda uma cópia em relatorio-unit.txt. */
    fun compartilhar(ctx: Context, testes: List<Teste>?) = Nucleo.rodar {
        val txt = texto(testes ?: ultimosTestes)
        runCatching { File(pasta, "relatorio-unit.txt").writeText(txt) }
        val envio = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Relatório do Unit").putExtra(Intent.EXTRA_TEXT, txt)
        ctx.startActivity(Intent.createChooser(envio, "Compartilhar relatório do Unit").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun cor(tipo: String) = when (tipo) { "erro" -> Cor.ruim; "aviso" -> Color(0xFFFFB86B); "android" -> Color(0xFF8FE3B0); "jogo" -> Color(0xFFB8C4FF); "caminho" -> Cor.a2; "decisao" -> Color(0xFFC9A7FF); "tela" -> Cor.apagado; else -> Cor.texto }

    @Composable
    fun TelaAutoteste(ctx: Context, verRegistro: () -> Unit) = Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var testes by remember { mutableStateOf<List<Teste>?>(null) }
        fun rodar() = Nucleo.rodar { testes = autoteste(ctx).also { ultimosTestes = it } }
        Botao("Ver o registro (${linhas.size})", false, Modifier.fillMaxWidth(), clique = verRegistro)
        val l = testes
        if (l == null) {
            Text("Confere root, motor, jogo, leitura, congelados, caminhos, permissão, espaço, erros e atualização. Só lê: não mexe no jogo.", color = Cor.apagado, fontSize = 12.sp)
            Botao(if (Nucleo.ocupado) "Conferindo…" else "Rodar autoteste", true, Modifier.fillMaxWidth(), !Nucleo.ocupado) { rodar() }
        } else {
            Linha {
                Column(Modifier.weight(1f)) {
                    Text(Nucleo.jogo?.nome ?: "Sem jogo", color = Cor.texto, fontWeight = FontWeight.Bold)
                    Text("Unit $versao · Android ${Build.VERSION.RELEASE}", color = Cor.apagado, fontSize = 11.sp)
                }
                Text("${l.count { it.ok }}/${l.size}", color = if (l.all { it.ok }) Cor.ok else Cor.a2, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Botao("Rodar de novo", false, Modifier.weight(1f), !Nucleo.ocupado) { rodar() }
                Botao("Compartilhar", true, Modifier.weight(1f)) { compartilhar(ctx, l) }
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
        }
    }

    @Composable
    fun TelaRegistro(ctx: Context) = Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var filtro by remember { mutableStateOf("tudo") }
        val abertos = remember { mutableStateListOf<Evento>() }
        // no topo: o usuário pediu para não ter que rolar até o fim para compartilhar
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Botao("Limpar", false, Modifier.weight(1f)) { linhas.clear(); runCatching { arquivo?.writeText("") } }
            Botao("Compartilhar", true, Modifier.weight(1f)) { compartilhar(ctx, null) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("tudo" to "Tudo", "busca" to "Busca", "caminho" to "Caminhos", "decisao" to "Decisões", "android" to "Android", "erro" to "Erros").forEach { (f, n) -> Selo(n, filtro == f) { filtro = f } }
        }
        val vistos = linhas.filter { filtro == "tudo" || it.tipo == filtro || (filtro == "busca" && it.tipo == "jogo") || (filtro == "erro" && it.tipo == "aviso") }
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
    }
}
