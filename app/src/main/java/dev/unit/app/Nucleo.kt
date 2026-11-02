package dev.unit.app

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Um lugar achado na memória: endereço (hex), tipo (i32/f32/i64/f64) e o valor de agora. */
data class Achado(val addr: String, val tipo: String, val valor: String)
data class Salvo(val addr: String, val tipo: String, val nome: String, val valor: String, val congelado: Boolean, val caminho: Boolean = false)
data class Jogo(val pid: Int, val pacote: String, val nome: String)

// os nomes do GameGuardian (o usuário pediu: servem para pesquisar e aprender)
/** Apps que não são jogo (o root, o próprio GameGuardian, o Tinker…): fora da lista de jogos. */
val FERRAMENTAS = setOf("com.topjohnwu.magisk", "dev.tinker.app", "moe.shizuku.privileged.api", "catch_.me_.if_.you_.can_", "io.github.vvb2060.magisk")

/** Mesmo endereço como Dword e Qword com o mesmo número (os 4 bytes de cima são zero): é um valor só; fica o Dword. */
fun juntar(l: List<Achado>) = l.filterNot { q ->
    q.tipo == "i64" && l.any { it.addr == q.addr && it.tipo == "i32" && it.valor.toDoubleOrNull() == q.valor.toDoubleOrNull() }
}

/** O tipo que já funcionou neste jogo vem primeiro; sem nada aprendido, a ordem do motor fica como está. */
fun ordenar(l: List<Achado>, aprendido: String?) = if (aprendido == null) l else l.sortedBy { if (it.tipo == aprendido) 0 else 1 }

val TIPOS = listOf("i32", "f32", "i64", "f64")
val TAMANHO = mapOf("i32" to 4, "f32" to 4, "i64" to 8, "f64" to 8)

/** O máximo do Dword (o jogo cortaria um número maior); Qword e Float aguentam muito mais. */
const val MAX_DWORD = 2147483647L
fun passaLimite(tipo: String, texto: String): Boolean {
    val v = texto.replace(',', '.').toDoubleOrNull() ?: return false
    return tipo == "i32" && (v > MAX_DWORD || v < -MAX_DWORD - 1) || tipo == "i64" && kotlin.math.abs(v) > 9.2e18
}

val NOME_TIPO = mapOf("i32" to "Dword", "f32" to "Float", "i64" to "Qword", "f64" to "Double")

/**
 * O estado do Unit, um só para a tela do app e o balão sobre o jogo: o jogo escolhido, a busca, os salvos. Toda conversa
 * com o motor passa por aqui.
 */
object Nucleo {
    private var motor: Motor? = null
    private var lib = ""
    private var prefs: android.content.SharedPreferences? = null
    /** Histórico por jogo: cada edição soma 1 ao tipo, cada congelar soma 2. O tipo com mais pontos vem primeiro nas buscas
     *  daquele jogo (um erro aqui e ali perde para o tipo que você usa de verdade). */
    val aprendido get() = jogo?.let { j -> TIPOS.maxByOrNull { prefs?.getInt("pontos-${j.pacote}-$it", 0) ?: 0 }
        ?.takeIf { (prefs?.getInt("pontos-${j.pacote}-$it", 0) ?: 0) > 0 } }
    private fun aprender(tipo: String, pontos: Int) {
        val j = jogo ?: return; val k = "pontos-${j.pacote}-$tipo"
        prefs?.edit()?.putInt(k, (prefs?.getInt(k, 0) ?: 0) + pontos)?.apply()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var jogo by mutableStateOf<Jogo?>(null)
    val jogos = mutableStateListOf<Jogo>()
    var pausado by mutableStateOf(false)
    var total by mutableStateOf<Long?>(null)
    val achados = mutableStateListOf<Achado>()
    var escolhido by mutableStateOf<Achado?>(null)
    val salvos = mutableStateListOf<Salvo>()
    var aviso by mutableStateOf("")
    var ocupado by mutableStateOf(false)
    var jogoFechou by mutableStateOf(false)  // o motor disse "o jogo fechou": o painel pergunta se quer reabrir

    fun init(ctx: Context) { lib = ctx.applicationInfo.nativeLibraryDir + "/libunitd.so"; prefs = ctx.getSharedPreferences("aprendido", Context.MODE_PRIVATE); Ponteiro.init(ctx); java.io.File(ctx.cacheDir, "foto.bin").delete() }

    private val abrindo = Any()
    // um motor só: dois pedidos ao mesmo tempo (o app e o balão abrindo juntos) não podem abrir dois pedidos de root
    internal suspend fun <T> cmd(f: (Motor) -> T): T = withContext(Dispatchers.IO) {
        // motor novo (o primeiro, ou depois de um que caiu/fechou): já se liga ao jogo escolhido
        val m = synchronized(abrindo) { motor ?: Motor(lib).also { n -> motor = n; jogo?.let { j -> runCatching { n.cmd("attach ${j.pid}") } } } }
        try { f(m) } catch (e: java.io.IOException) {
            // o motor caiu (falta de memória, su fechado): solta ele para o próximo comando abrir outro, em vez de falhar sempre
            if (e.message == "O motor fechou." || e.message?.contains("pipe", ignoreCase = true) == true) synchronized(abrindo) { if (motor === m) { m.fechar(); motor = null } }
            throw e
        }
    }

    /** Roda uma ação do motor; o erro vira o aviso na tela, em vez de fechar o app. */
    fun rodar(f: suspend () -> Unit) = scope.launch {
        ocupado = true
        try { f(); aviso = "" } catch (e: Exception) { aviso = e.message ?: "erro" } finally { ocupado = false }
    }

    /** Os apps abertos que o usuário instalou (jogos); sem nome legível, mostra o pacote. */
    fun carregarJogos(ctx: Context) = rodar {
        val pm = ctx.packageManager
        val r = cmd { it.cmd("ps") }
        val lista = r.linhas.mapNotNull { l ->
            val p = l.split(' ', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            val pac = p[1].substringBefore(':')  // processos extras do mesmo app: "pacote:servico"
            if (pac == ctx.packageName) return@mapNotNull null
            if (pac in FERRAMENTAS) return@mapNotNull null
            // sem app com esse nome = processo do sistema (android.process.acore…): com QUERY_ALL_PACKAGES todo app é achado
            val app = runCatching { pm.getApplicationInfo(pac, 0) }.getOrNull() ?: return@mapNotNull null
            if ((app.flags and ApplicationInfo.FLAG_SYSTEM) != 0) return@mapNotNull null
            if (pm.getLaunchIntentForPackage(pac) == null) return@mapNotNull null
            Jogo(p[0].toInt(), pac, pm.getApplicationLabel(app).toString())
        }.distinctBy { it.pacote }.sortedBy { it.nome.lowercase() }
        jogos.clear(); jogos += lista
        if (lista.isEmpty()) throw IllegalStateException("Nenhum jogo aberto. Abra o jogo e toque em Atualizar.")
    }

    fun escolher(j: Jogo) = rodar {
        if (pausado) runCatching { cmd { it.cmd("resume") } }  // não deixa o jogo de antes parado
        cmd { it.cmd("attach ${j.pid}") }
        jogo = j; pausado = false; zerar(); salvos.clear()
        Ponteiro.aplicar()  // LABORATÓRIO: os salvos guardados com caminho aparecem sozinhos
    }

    /** A cada 2 s (balão aberto): o jogo ainda está aberto? Sem aviso na tela; só liga o jogoFechou. */
    fun verificar() = scope.launch {
        if (jogo == null || ocupado || jogoFechou) return@launch
        runCatching { cmd { it.cmd("vivo") } }.onFailure { if (it.message == "o jogo fechou") { jogoFechou = true; pausado = false } }
    }

    /** Fecha o jogo (para conferir um caminho, ou recomeçar limpo). */
    fun fecharJogo() = rodar { cmd { it.cmd("fechar") }; jogoFechou = true; pausado = false }

    /** Abre o jogo de novo e se liga a ele sozinho (espera até 30 s o processo aparecer). Os salvos com caminho voltam. */
    fun reabrir(ctx: Context) = rodar {
        val j = jogo ?: return@rodar
        val abrir = ctx.packageManager.getLaunchIntentForPackage(j.pacote) ?: throw IllegalStateException("Não achei como abrir ${j.nome}.")
        ctx.startActivity(abrir.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        aviso = "Abrindo ${j.nome}…"
        repeat(60) {
            kotlinx.coroutines.delay(500)
            val pid = cmd { it.cmd("ps") }.linhas.firstNotNullOfOrNull { l -> l.split(' ', limit = 2).takeIf { p -> p.size == 2 && p[1] == j.pacote }?.get(0)?.toIntOrNull() }
            if (pid != null) {
                cmd { it.cmd("attach $pid") }
                jogo = j.copy(pid = pid); jogoFechou = false; pausado = false; zerar(); salvos.clear()
                aviso = "Esperando ${j.nome} carregar…"; kotlinx.coroutines.delay(5000); aviso = ""  // recém-aberto: os caminhos ainda não existem
                Ponteiro.aplicar()
                return@rodar
            }
        }
        throw IllegalStateException("${j.nome} não abriu em 30 s. Abra ele e escolha de novo em Jogos.")
    }

    fun zerar() { total = null; achados.clear(); escolhido = null }

    /** A 1ª vez busca; depois refina com o número novo da tela. Com 1 sobrando, ele já fica escolhido. */
    fun buscar(texto: String) = rodar {
        val v = texto.trim().replace(',', '.')
        cmd { it.cmd(if (total == null) "search $v" else "refine $v") }
        val r = cmd { it.cmd("list 50") }
        total = r.status.toLongOrNull()
        achados.clear()
        if ((total ?: 0) <= 50) achados += r.linhas.map { it.split(' ') }.filter { it.size == 3 }.map { Achado(it[0], it[1], it[2]) }.let { ordenar(juntar(it), aprendido) }
        escolhido = achados.singleOrNull()
    }

    /** Escreve o valor novo; com congelar, o motor segura ele. Fica nos salvos para o mini painel. */
    fun editar(addr: String, tipo: String, texto: String, congelar: Boolean) = rodar {
        aprender(tipo, if (congelar) 2 else 1)
        val v = texto.trim().replace(',', '.')
        val i = salvos.indexOfFirst { it.addr == addr }
        if (congelar) cmd { it.cmd("freeze $addr $tipo $v") }
        else {
            if (i >= 0 && salvos[i].congelado) cmd { it.cmd("unfreeze $addr") }
            cmd { it.cmd("write $addr $tipo $v") }
        }
        if (i >= 0) salvos[i] = salvos[i].copy(valor = v, congelado = congelar)
        else salvos += Salvo(addr, tipo, "Valor ${salvos.size + 1}", v, congelar)
        escolhido = escolhido?.takeIf { it.addr == addr }?.copy(valor = v)
    }

    fun alternarCongelado(s: Salvo) = rodar {
        if (s.congelado) cmd { it.cmd("unfreeze ${s.addr}") } else { cmd { it.cmd("freeze ${s.addr} ${s.tipo} ${s.valor}") }; aprender(s.tipo, 2) }
        val i = salvos.indexOfFirst { it.addr == s.addr }
        if (i >= 0) salvos[i] = salvos[i].copy(congelado = !s.congelado)
    }

    /** Dá nome a um valor (Ouro, Cobre…); se ainda não estava nos salvos, entra com o nome. */
    fun renomear(a: Achado, nome: String) {
        val n = nome.trim().ifEmpty { return }
        val i = salvos.indexOfFirst { it.addr == a.addr }
        if (i >= 0 && salvos[i].caminho) Ponteiro.renomear(salvos[i].nome, n)
        if (i >= 0) salvos[i] = salvos[i].copy(nome = n) else salvos += Salvo(a.addr, a.tipo, n, a.valor, false)
    }

    /** "Ver como": lê e escreve o mesmo endereço em outro tipo. Congelado no tipo antigo solta (o valor seria outro). */
    fun trocarTipo(addr: String, novo: String) = rodar {
        val i = salvos.indexOfFirst { it.addr == addr }
        if (i >= 0 && salvos[i].congelado) cmd { it.cmd("unfreeze $addr") }
        val v = cmd { it.cmd("read $addr $novo") }.status
        if (i >= 0) salvos[i] = salvos[i].copy(tipo = novo, valor = v, congelado = false)
        escolhido = escolhido?.takeIf { it.addr == addr }?.copy(tipo = novo, valor = v)
    }

    fun salvar(a: Achado) { if (salvos.none { it.addr == a.addr }) salvos += Salvo(a.addr, a.tipo, "Valor ${salvos.size + 1}", a.valor, false) }

    // troca o estado na hora e manda o estado de agora: dois toques seguidos (ou a pausa automática) nunca invertem a tela e o jogo
    fun pausar() {
        pausado = !pausado
        rodar { val quer = pausado; try { cmd { it.cmd(if (quer) "pause" else "resume") } } catch (e: Exception) { if (pausado == quer) pausado = !quer; throw e } }
    }

    /** O Unit está fechando: o jogo nunca pode ficar congelado sem ele (o Android mostraria "não está respondendo"). */
    fun soltarAoFechar() {
        pausado = false
        val m = motor ?: return; motor = null
        Thread { runCatching { m.cmd("unfreeze all") }; runCatching { m.cmd("resume") }; m.fechar() }.start()
        salvos.replaceAll { it.copy(congelado = false) }
    }

    /** Os resultados da busca (até 20, os que aparecem) e o valor aberto no Editar, relidos ao vivo: a lista não fica parada no valor da hora da busca. */
    fun atualizarAchados() = scope.launch {
        if (jogo == null || ocupado) return@launch
        runCatching {
            suspend fun ler(a: Achado) = a.copy(valor = runCatching { cmd { it.cmd("read ${a.addr} ${a.tipo}") }.status }.getOrDefault(a.valor))
            val novos = achados.take(20).map { ler(it) }
            novos.forEach { n -> val i = achados.indexOfFirst { it.addr == n.addr && it.tipo == n.tipo }; if (i >= 0 && achados[i].valor != n.valor) achados[i] = n }
            escolhido?.let { e -> val n = novos.firstOrNull { it.addr == e.addr && it.tipo == e.tipo } ?: ler(e); if (n.valor != e.valor && escolhido?.addr == e.addr) escolhido = e.copy(valor = n.valor) }
        }
    }

    /** Os valores salvos de agora (o mini painel mostra ao vivo). Sem aviso: roda a cada segundo. */
    fun atualizarSalvos() = scope.launch {
        if (jogo == null || ocupado) return@launch
        runCatching {
            val novos = salvos.toList().map { s -> s.copy(valor = runCatching { cmd { it.cmd("read ${s.addr} ${s.tipo}") }.status }.getOrDefault(s.valor)) }
            novos.forEach { n -> val i = salvos.indexOfFirst { it.addr == n.addr }; if (i >= 0 && salvos[i].valor != n.valor) salvos[i] = salvos[i].copy(valor = n.valor) }
        }
    }
}
