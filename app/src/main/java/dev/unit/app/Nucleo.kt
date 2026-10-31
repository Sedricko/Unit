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

val NOME_TIPO = mapOf("i32" to "Inteiro", "f32" to "Vírgula", "i64" to "Inteiro longo", "f64" to "Vírgula longa")

/**
 * O estado do Unit, um só para a tela do app e o balão sobre o jogo: o jogo escolhido, a busca, os salvos. Toda conversa
 * com o motor passa por aqui.
 */
object Nucleo {
    private var motor: Motor? = null
    private var lib = ""
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

    fun init(ctx: Context) { lib = ctx.applicationInfo.nativeLibraryDir + "/libunitd.so" }

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
            val app = runCatching { pm.getApplicationInfo(pac, 0) }.getOrNull()
            if (app != null && (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0) return@mapNotNull null
            if (app != null && pm.getLaunchIntentForPackage(pac) == null) return@mapNotNull null
            Jogo(p[0].toInt(), pac, app?.let { pm.getApplicationLabel(it).toString() } ?: pac)
        }.distinctBy { it.pacote }.sortedBy { it.nome.lowercase() }
        jogos.clear(); jogos += lista
        if (lista.isEmpty()) throw IllegalStateException("Nenhum jogo aberto. Abra o jogo e toque em Atualizar.")
    }

    fun escolher(j: Jogo) = rodar {
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
        if ((total ?: 0) <= 50) achados += r.linhas.map { it.split(' ') }.filter { it.size == 3 }.map { Achado(it[0], it[1], it[2]) }
        escolhido = achados.singleOrNull()
    }

    /** Escreve o valor novo; com congelar, o motor segura ele. Fica nos salvos para o mini painel. */
    fun editar(addr: String, tipo: String, texto: String, congelar: Boolean) = rodar {
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
        if (s.congelado) cmd { it.cmd("unfreeze ${s.addr}") } else cmd { it.cmd("freeze ${s.addr} ${s.tipo} ${s.valor}") }
        val i = salvos.indexOfFirst { it.addr == s.addr }
        if (i >= 0) salvos[i] = salvos[i].copy(congelado = !s.congelado)
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
