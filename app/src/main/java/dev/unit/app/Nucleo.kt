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
data class Salvo(val addr: String, val tipo: String, val nome: String, val valor: String, val congelado: Boolean)
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

    fun init(ctx: Context) { lib = ctx.applicationInfo.nativeLibraryDir + "/libunitd.so"; prefs = ctx.getSharedPreferences("aprendido", Context.MODE_PRIVATE) }

    private suspend fun <T> cmd(f: (Motor) -> T): T = withContext(Dispatchers.IO) { f(motor ?: Motor(lib).also { motor = it }) }

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

    fun pausar() = rodar { cmd { it.cmd(if (pausado) "resume" else "pause") }; pausado = !pausado }

    /** Os valores salvos de agora (o mini painel mostra ao vivo). Sem aviso: roda a cada segundo. */
    fun atualizarSalvos() = scope.launch {
        if (jogo == null || ocupado) return@launch
        runCatching {
            val novos = salvos.toList().map { s -> s.copy(valor = runCatching { cmd { it.cmd("read ${s.addr} ${s.tipo}") }.status }.getOrDefault(s.valor)) }
            novos.forEach { n -> val i = salvos.indexOfFirst { it.addr == n.addr }; if (i >= 0 && salvos[i].valor != n.valor) salvos[i] = n }
        }
    }
}
