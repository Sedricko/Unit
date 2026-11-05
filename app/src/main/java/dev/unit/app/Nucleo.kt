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
// classe: de que objeto do Unity o resultado faz parte ("" = não é objeto, ou ainda não olhado); tela = parte da tela
data class Achado(val addr: String, val tipo: String, val valor: String, val classe: String = "", val tela: Boolean = false)

/** A barra, o texto e a animação mostram uma cópia do valor (o Vampire's Fall 2: Slider, CharacterBar, TweenerCore;
 * o de verdade era CharacterStats). Editar a cópia não muda o jogo. */
fun daTela(nome: String, esp: String) = esp.startsWith("UnityEngine.UI") || esp.startsWith("TMPro") || esp.startsWith("DG.Tweening") ||
    listOf("Slider", "Bar", "Tween", "Text", "Label", "Image", "View", "Display", "Animator", "UI").any { it in nome }
/** perdido = veio guardado de uma abertura anterior e o endereço ainda não foi achado de novo (sem caminho que chegue). */
data class Salvo(val addr: String, val tipo: String, val nome: String, val valor: String, val congelado: Boolean, val caminho: Boolean = false, val perdido: Boolean = false)
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

val TIPOS = listOf("i32", "f32", "i64", "f64", "i16", "i8")
val TAMANHO = mapOf("i32" to 4, "f32" to 4, "i64" to 8, "f64" to 8, "i16" to 2, "i8" to 1)

/** O máximo do Dword (o jogo cortaria um número maior); Qword e Float aguentam muito mais. */
const val MAX_DWORD = 2147483647L
fun passaLimite(tipo: String, texto: String): Boolean {
    val v = texto.replace(',', '.').toDoubleOrNull() ?: return false
    return tipo == "i32" && (v > MAX_DWORD || v < -MAX_DWORD - 1) || tipo == "i64" && kotlin.math.abs(v) > 9.2e18 ||
        tipo == "i16" && (v > 32767 || v < -32768) || tipo == "i8" && (v > 127 || v < -128)
}

val NOME_TIPO = mapOf("i32" to "Dword", "f32" to "Float", "i64" to "Qword", "f64" to "Double", "i16" to "Word", "i8" to "Byte")

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
    var pedirFechar by mutableStateOf(false)  // tocou em "Fechar jogo": o painel pede confirmação (perde o que não foi salvo)

    fun init(ctx: Context) { lib = ctx.applicationInfo.nativeLibraryDir + "/libunitd.so"; prefs = ctx.getSharedPreferences("aprendido", Context.MODE_PRIVATE); salvosGuardados = ctx.getSharedPreferences("salvos", Context.MODE_PRIVATE); Ponteiro.init(ctx); Registro.init(ctx); java.io.File(ctx.cacheDir, "foto.bin").delete() }

    private val abrindo = Any()
    // um motor só: dois pedidos ao mesmo tempo (o app e o balão abrindo juntos) não podem abrir dois pedidos de root
    internal suspend fun <T> cmd(f: (Motor) -> T): T = withContext(Dispatchers.IO) {
        // motor novo (o primeiro, ou depois de um que caiu/fechou): já se liga ao jogo escolhido
        val m = synchronized(abrindo) { motor ?: Motor(lib).also { n -> motor = n; Registro.aberturas++; Registro.add("outro", "Motor abriu (${n.ola})" + if (Registro.aberturas > 1) " · ${Registro.aberturas}ª vez: ele tinha caído" else ""); jogo?.let { j -> runCatching { n.cmd("attach ${j.pid}") }.onSuccess { if (Registro.aberturas > 1) Registro.add("decisao", "Religuei o motor novo a ${j.nome} sozinho") } } } }
        try { f(m) } catch (e: java.io.IOException) {
            // o motor caiu (falta de memória, su fechado): solta ele para o próximo comando abrir outro, em vez de falhar sempre
            if (e.message == "O motor fechou." || e.message?.contains("pipe", ignoreCase = true) == true) synchronized(abrindo) { if (motor === m) { m.fechar(); motor = null } }
            throw e
        }
    }

    /** Roda uma ação do motor; o erro vira o aviso na tela, em vez de fechar o app. */
    fun semAviso(f: suspend () -> Unit) = scope.launch { runCatching { f() } }

    // quantos rodar estão rodando: com um booleano, o 1º que terminava apagava o "ocupado" com outro ainda rodando
    private var rodando = 0
    fun rodar(f: suspend () -> Unit) = scope.launch {
        rodando++; ocupado = true
        try { f(); aviso = "" } catch (e: Exception) { aviso = e.message ?: "erro"; Registro.add("erro", "Erro: $aviso") } finally { rodando--; ocupado = rodando > 0 }
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

    fun escolher(alvo: Jogo) = rodar {
        // o número do processo de agora (a lista da tela do app pode ser de antes: o jogo reabriu fora do Unit)
        val pidAgora = cmd { it.cmd("ps") }.linhas.firstNotNullOfOrNull { l -> l.split(' ', limit = 2).takeIf { p -> p.size == 2 && p[1] == alvo.pacote }?.get(0)?.toIntOrNull() }
        val j = alvo.copy(pid = pidAgora ?: alvo.pid)
        // o mesmo jogo, no mesmo processo: não apaga os salvos nem os congelados; só procura de novo os que faltam
        if (jogo?.pacote == j.pacote && jogo?.pid == j.pid && !jogoFechou) {
            Registro.add("jogo", "${j.nome} já estava ligado: procurando de novo os que faltam")
            Ponteiro.aplicar(); Ponteiro.tentarDeNovo(); return@rodar
        }
        if (pausado) runCatching { cmd { it.cmd("resume") } }  // não deixa o jogo de antes parado
        cmd { it.cmd("attach ${j.pid}") }
        val antigos = lerSalvos(j.pacote)
        Ponteiro.novaAbertura()
        jogo = j; jogoFechou = false; pausado = false; zerar(); salvos.clear(); salvos += antigos  // os nomes voltam; o caminho (aplicar) acha o endereço
        Registro.add("jogo", "Ligado a ${j.nome} (pid ${j.pid})" + if (antigos.isNotEmpty()) " · ${antigos.size} salvo(s) guardados de antes" else "")
        Ponteiro.aplicar()  // LABORATÓRIO: os salvos guardados com caminho aparecem sozinhos
        Ponteiro.tentarDeNovo()
        Registro.autotesteRapido()
    }

    /** A cada 2 s (balão aberto): o jogo ainda está aberto? Sem aviso na tela; só liga o jogoFechou. */
    fun verificar() = scope.launch {
        if (jogo == null || ocupado || jogoFechou) return@launch
        // "!jogoFechou" de novo no fim: vários pedidos esperando atrás de uma procura respondiam juntos (um aviso só)
        runCatching { cmd { it.cmd("vivo") } }.onFailure { if (it.message == "o jogo fechou" && !jogoFechou) { jogo?.let { Ponteiro.lembrarValores(it.pacote, salvos.toList()) }; jogoFechou = true; pausado = false; Registro.add("jogo", "${jogo?.nome} fechou"); Registro.lerAndroid() } }  // o Android diz por quê
    }

    /**
     * Fecha o jogo na hora: um "su" à parte, sem passar pelo motor. O motor atende um pedido por vez e pode estar no meio
     * de uma procura longa (pelo nome, caminhos): o "fechar" ficava esperando e o botão parecia não funcionar.
     */
    fun fecharJogo() {
        val j = jogo ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { ProcessBuilder("su", "-c", "kill -CONT ${j.pid}; kill -9 ${j.pid}").redirectErrorStream(true).start().waitFor() == 0 }.getOrDefault(false)
            }
            if (!ok) runCatching { cmd { it.cmd("fechar") } }  // sem o su à parte: pelo motor, como antes
            Ponteiro.lembrarValores(j.pacote, salvos.toList())
            jogoFechou = true; pausado = false; Registro.add("jogo", "Fechei ${j.nome}" + if (ok) "" else " (pelo motor)")
        }
    }

    /** Abre o jogo de novo e se liga a ele sozinho (espera até 30 s o processo aparecer). Os salvos com caminho voltam. */
    fun reabrir(ctx: Context) = rodar {
        val j = jogo ?: return@rodar
        val abrir = ctx.packageManager.getLaunchIntentForPackage(j.pacote) ?: throw IllegalStateException("Não achei como abrir ${j.nome}.")
        ctx.startActivity(abrir.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        aviso = "Abrindo ${j.nome}…"; Registro.add("jogo", "Reabrindo ${j.nome}")
        repeat(60) {
            kotlinx.coroutines.delay(500)
            val pid = cmd { it.cmd("ps") }.linhas.firstNotNullOfOrNull { l -> l.split(' ', limit = 2).takeIf { p -> p.size == 2 && p[1] == j.pacote }?.get(0)?.toIntOrNull() }
            if (pid != null) {
                cmd { it.cmd("attach $pid") }
                val antigos = lerSalvos(j.pacote)
                Ponteiro.novaAbertura()
                jogo = j.copy(pid = pid); jogoFechou = false; pausado = false; zerar(); salvos.clear(); salvos += antigos
                Registro.add("jogo", "${j.nome} reaberto (pid $pid)")
                aviso = "Esperando ${j.nome} carregar…"; kotlinx.coroutines.delay(5000); aviso = ""  // recém-aberto: os caminhos ainda não existem
                Ponteiro.aplicar()
                Ponteiro.tentarDeNovo()  // o jogo pode montar as estruturas depois: confere de novo em 20 s e para
                return@rodar
            }
        }
        throw IllegalStateException("${j.nome} não abriu em 30 s. Abra ele e escolha de novo em Jogos.")
    }

    /** Apaga resultados da busca (no motor também: o próximo refino não traz eles de volta). */
    fun apagar(addrs: List<String>) = rodar {
        var sobraram = total
        // 60 endereços por vez: cabem na linha de comando do motor
        addrs.chunked(60).forEach { parte -> sobraram = cmd { it.cmd("tirar ${parte.joinToString(",")}") }.status.substringBefore(' ').toLongOrNull() ?: sobraram }
        total = sobraram
        achados.removeAll { it.addr in addrs }
        if (escolhido?.addr in addrs) escolhido = null
        Registro.add("busca", "Apaguei ${addrs.size} resultado(s) da busca · sobraram ${total ?: 0}")
    }

    fun zerar() { total = null; achados.clear(); escolhido = null }

    /** A 1ª vez busca; depois refina com o número novo da tela. Com 1 sobrando, ele já fica escolhido. */
    fun buscar(texto: String) = rodar { try { buscarJa(texto) } finally { soltarSePausado() } }
    // tipos: "" = Auto (Dword, Float, Qword, Double); "i16,i8" = Word e Byte, que o Auto não olha (dariam milhões)
    private suspend fun buscarJa(texto: String, tipos: String = "") {
        val v = texto.trim().replace(',', '.')
        val refinar = total != null; val t0 = System.currentTimeMillis(); val antes = total
        pausarParaBuscar()
        val rb = cmd { it.cmd(if (!refinar) "search $v $tipos".trim() else "refine $v") }
        val manteve = refinar && rb.status.startsWith("0 manteve")  // nenhum bateu: o motor guardou os de antes
        val repetiu = v == ultimoV; ultimoV = v
        val cortou = "cortado" in rb.status  // o motor guardou só os primeiros 16 milhões
        if (cortou) Registro.add("aviso", "Busca de $v: resultados demais, guardei só os primeiros 16 milhões")
        // páginas que o motor não conseguiu ler (o valor pode estar ali): o relatório mostra
        Regex("naoleu=(\\d+)KB").find(rb.status)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }?.let { kb ->
            Registro.add("aviso", "Busca de $v: ${kb / 1024} MB do jogo não deram para ler (páginas protegidas ou que sumiram durante a busca)") }
        val r = cmd { it.cmd("list 50") }
        total = r.status.toLongOrNull()
        achados.clear()
        if ((total ?: 0) <= 50) {
            val crus = r.linhas.map { it.split(' ') }.filter { it.size == 3 }.map { Achado(it[0], it[1], it[2]) }
            val juntos = juntar(crus)
            if (juntos.size < crus.size) Registro.add("decisao", "Juntei ${crus.size - juntos.size} resultado(s) Dword/Qword repetidos (mesmo endereço e número)")
            val ordem = ordenar(juntos, aprendido)
            if (aprendido != null && ordem.firstOrNull()?.tipo == aprendido && juntos.firstOrNull()?.tipo != aprendido) Registro.add("decisao", "Mostrei ${NOME_TIPO[aprendido] ?: aprendido} primeiro: é o tipo que você mais usa neste jogo")
            // de que objeto é cada um (rápido: ~20 ms): o que é do jogo primeiro, as cópias da tela depois
            val comClasse = if (ordem.size <= 20) ordem.map { a ->
                val p = runCatching { cmd { it.cmd("classe ${a.addr}") }.status.split(' ') }.getOrNull()
                if (p == null || p.size < 2) a else a.copy(classe = p[0], tela = daTela(p[0], p[1]))
            }.sortedBy { if (it.tela) 1 else 0 } else ordem
            if (comClasse.any { it.tela } && comClasse.any { !it.tela && it.classe.isNotEmpty() })
                Registro.add("decisao", "Pus primeiro os do jogo; ${comClasse.count { it.tela }} são da tela (barra, texto, animação)", comClasse.map { "${it.addr} ${it.classe}${if (it.tela) " (tela)" else ""}" })
            achados += comClasse
        }
        if (!refinar) { buscaDesde = t0; passos = 0 } else passos++
        if (total == 1L && buscaDesde > 0) { Registro.add("busca", "Achou o valor em ${passos} refino(s), ${(System.currentTimeMillis() - buscaDesde) / 1000} s desde o primeiro Buscar"); buscaDesde = 0 }
        escolhido = achados.singleOrNull()
        if (escolhido != null) Registro.add("decisao", "Sobrou 1: já deixei ${escolhido!!.addr} escolhido para editar")
        Registro.add("busca", "${if (refinar) "Refinar" else "Buscar"} $v → ${total ?: 0} resultado(s) (${"%.1f".format((System.currentTimeMillis() - t0) / 1000.0)} s, jogo pausado durante a busca)")
        when {
            total == 0L -> Registro.add("aviso", "Nenhum resultado para $v: o número pode estar em outro tipo, ter mudado antes da busca, ou ser só desenho na tela")
            manteve -> {
                // o relatório (Obsidian Knight): refinava, dava 0 e perdia tudo; o número da tela já tinha mudado de novo
                val agora = achados.take(5).joinToString { it.valor }
                val porque = "Nenhum dos $antes ficou com $v. Eles continuam na lista com o número de agora" +
                    (if (agora.isNotEmpty()) " ($agora)" else "") + ": confira qual bate com a tela e refine com ele."
                Registro.add("aviso", porque); throw IllegalStateException(porque)
            }
            // mesmo número de novo e nada saiu: o valor não mudou (com número novo e todos seguindo, está certo)
            refinar && repetiu && antes != null && total == antes && antes > 1 -> Registro.add("aviso", "Refinar $v de novo não diminuiu nada ($antes): mude o valor no jogo antes de refinar")
            (total ?: 0) > 10_000_000 -> Registro.add("aviso", "Mais de 10 milhões de resultados: o próximo refino vai ser lento; use um número mais raro ou uma faixa menor")
        }
        // busca sem nada: procura perto (n-1~n+1, todos os tipos) para dizer o porquê
        val n = v.toDoubleOrNull()
        fun t(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()
        // inteiro sem nada no Auto: procura sozinho em Word e Byte (quantidades pequenas às vezes ficam em 1 ou 2 bytes)
        if (total == 0L && !refinar && tipos.isEmpty() && n != null && n % 1.0 == 0.0 && n in -32768.0..65535.0) {
            Registro.add("decisao", "Nada em Dword/Float/Qword/Double para $v: procurando em Word e Byte")
            total = null; return buscarJa(texto, "i16,i8")
        }
        if (total == 0L && n != null) {  // também no Refinar: o número ainda existe na memória ou sumiu?
            val perto = runCatching { cmd { it.cmd("search ${t(n - 1)}~${t(n + 1)}") }.status.substringBefore(' ').toLongOrNull() }.getOrNull() ?: 0L
            total = null  // o próximo é um Buscar novo (refinar 0 resultados não serve)
            val porque = if (perto > 0 && refinar) "Nenhum dos resultados ficou com $v, mas há $perto número(s) perto na memória: o valor pode ter mudado de novo antes do Refinar. Toque em Buscar com o número que está na tela agora."
                else if (perto > 0) "Nenhum $v exato, mas $perto número(s) perto (${t(n - 1)} a ${t(n + 1)}): o número pode ter mudado antes da busca. Mude o valor no jogo e busque o número novo."
                else "Não há nenhum número perto de $v na memória, em nenhum tipo: o jogo pode guardar de outro jeito (fração, dano recebido, vezes 10): tente a Busca por mudança no Achar. Se nem ela achar, o valor está codificado ou vem do servidor."
            Registro.add("aviso", "Por que não achou $v: $porque")
            throw IllegalStateException(porque)
        }
        // na tela (os resultados ficam): o certo pode ter ficado de fora dos 16 milhões
        if (cortou) throw IllegalStateException("Resultados demais: o valor certo pode ter ficado de fora. Mude o valor no jogo e busque o número novo.")
    }

    private var ultimoV = ""  // o número do último Buscar/Refinar (refinar com o mesmo não ajuda)
    private var buscaDesde = 0L; private var passos = 0  // tempo e refinos do primeiro Buscar até sobrar 1
    private var pausaJob: kotlinx.coroutines.Job? = null  // a última pausa automática a caminho do motor
    private var porDigitar = false  // quem pausou foi o 1º número digitado no Achar (sair sem buscar solta)
    fun pausarAoDigitar() {
        if (pausado || jogo == null) return
        pausado = true; porDigitar = true; Registro.add("jogo", "Pausou ao digitar no Achar")
        pausaJob = scope.launch { runCatching { cmd { it.cmd("pause") } }.onFailure { if (porDigitar) { pausado = false; porDigitar = false } } }
    }
    fun soltarDigitacao() {
        if (!porDigitar || !pausado) { porDigitar = false; return }
        porDigitar = false; pausado = false; Registro.add("jogo", "Soltou: apagou tudo ou saiu do Achar sem buscar")
        val antes = pausaJob
        scope.launch { antes?.join(); runCatching { cmd { it.cmd("resume") } } }  // nunca chega ao motor antes da pausa
    }

    // a pergunta "qual destes números está na tela?" aberta: pausa para o número parar de mudar; quem pausou solta
    private var porDuvida = false
    fun pausarPelaDuvida() {
        if (pausado || jogo == null) return
        pausado = true; porDuvida = true; Registro.add("jogo", "Pausou para você comparar os números da pergunta")
        pausaJob = scope.launch { runCatching { cmd { it.cmd("pause") } }.onFailure { if (porDuvida) { pausado = false; porDuvida = false } } }
    }
    fun soltarDuvida() {
        if (!porDuvida || !pausado) { porDuvida = false; return }
        porDuvida = false; pausado = false; Registro.add("jogo", "Soltou: a pergunta foi respondida ou fechada")
        val antes = pausaJob
        scope.launch { antes?.join(); runCatching { cmd { it.cmd("resume") } } }
    }

    /** O jogo escolhido está na frente (na tela)? null = não deu para saber. Prioridade 0 do Android = app na tela. */
    suspend fun jogoNaFrente(): Boolean? = runCatching { cmd { it.cmd("frente") }.status.trim().toInt() <= 0 }.getOrNull()

    /** Começo da busca: o jogo para, para o valor não mudar no meio (o soltarSePausado solta no fim). */
    internal suspend fun pausarParaBuscar() {
        if (pausado || jogo == null) return
        runCatching { cmd { it.cmd("pause") } }.onSuccess { pausado = true; Registro.add("jogo", "Pausou para a busca") }
    }

    /** Terminou uma busca: o jogo volta a rodar (pausado muito tempo, o Android mostra "não está respondendo"). */
    internal suspend fun soltarSePausado() {
        if (!pausado) return
        runCatching { cmd { it.cmd("resume") } }.onSuccess { pausado = false; porDigitar = false; Registro.add("jogo", "Busca terminou: soltou o jogo") }
    }

    /** Escreve o valor novo; com congelar, o motor segura ele. Fica nos salvos para o mini painel. */
    fun editar(addr: String, tipo: String, texto: String, congelar: Boolean) = rodar {
        aprender(tipo, if (congelar) 2 else 1)
        Registro.add("busca", "${if (congelar) "Congelou" else "Escreveu"} $addr ($tipo) = ${texto.trim()}")
        val v = texto.trim().replace(',', '.')
        val i = salvos.indexOfFirst { it.addr == addr && !it.perdido }  // um perdido com o mesmo endereço é de outra abertura
        if (congelar) cmd { it.cmd("freeze $addr $tipo $v") }
        else {
            if (i >= 0 && salvos[i].congelado) cmd { it.cmd("unfreeze $addr") }
            cmd { it.cmd("write $addr $tipo $v") }
        }
        if (i >= 0) salvos[i] = salvos[i].copy(valor = v, congelado = congelar)
        else salvos += Salvo(addr, tipo, nomeLivre(), v, congelar)
        escolhido = escolhido?.takeIf { it.addr == addr }?.copy(valor = v)
        if (congelar) return@rodar
        // o jogo escreveu por cima? (Grim Soul: escreveu 99, o item seguiu em 12: era só uma cópia, o valor de verdade
        // fica em outro lugar). Confere e diz, em vez de mostrar 99 nos Salvos
        kotlinx.coroutines.delay(1500)
        val agora = runCatching { cmd { it.cmd("read $addr $tipo") }.status }.getOrNull() ?: return@rodar
        if ((agora.toDoubleOrNull() ?: return@rodar) == v.toDoubleOrNull()) return@rodar
        salvos.indexOfFirst { it.addr == addr && !it.perdido }.let { k -> if (k >= 0) salvos[k] = salvos[k].copy(valor = agora) }
        Registro.add("aviso", "Escrita não segurou ($addr): o jogo voltou para $agora logo depois de escrever $v · é uma cópia; procuro o de verdade em Word e Byte")
        // o de verdade pode estar num tipo que o Auto não olha: procura sozinho o número de agora em Word e Byte
        val achou = if (agora.toDoubleOrNull()?.let { it % 1.0 == 0.0 && it in -32768.0..65535.0 } == true) {
            zerar()
            try { runCatching { buscarJa(agora, "i16,i8") }; total ?: 0L } finally { soltarSePausado() }
        } else 0L
        throw IllegalStateException("O jogo voltou o valor para $agora logo depois de escrever $v: esse endereço é só uma cópia (a da tela). " +
            if (achou > 0) "Procurei $agora em Word e Byte: $achou resultado(s) no Achar. Mude o valor no jogo e refine."
            else "Não há $agora em Word nem Byte: o de verdade pode estar guardado de outro jeito (fração, dano recebido). Tente a Busca por mudança no Achar.")
    }

    fun alternarCongelado(s: Salvo) = rodar {
        if (s.perdido) throw IllegalStateException("${s.nome}: o endereço é de antes de reabrir o jogo. Ache de novo para congelar.")
        if (s.congelado) cmd { it.cmd("unfreeze ${s.addr}") } else { cmd { it.cmd("freeze ${s.addr} ${s.tipo} ${s.valor}") }; aprender(s.tipo, 2) }
        val i = salvos.indexOfFirst { it.addr == s.addr && !it.perdido }
        if (i >= 0) salvos[i] = salvos[i].copy(congelado = !s.congelado)
    }

    /** Dá nome a um valor (Ouro, Cobre…); se ainda não estava nos salvos, entra com o nome. */
    fun renomear(a: Achado, nome: String) {
        val n = nome.trim().ifEmpty { return }
        salvos.removeAll { it.perdido && it.nome == n && it.addr != a.addr }  // o "Cobre" de antes (sem endereço) dá lugar ao novo
        val i = salvos.indexOfFirst { it.addr == a.addr && !it.perdido }  // um perdido no mesmo endereço é de outra abertura
        if (i >= 0) Ponteiro.renomear(salvos[i].nome, n)  // o caminho guardado (conferido ou ainda a conferir) muda de nome junto
        if (i >= 0) salvos[i] = salvos[i].copy(nome = n) else salvos += Salvo(a.addr, a.tipo, n, a.valor, false)
    }

    /** "Ver como": lê e escreve o mesmo endereço em outro tipo. Congelado no tipo antigo solta (o valor seria outro). */
    fun trocarTipo(addr: String, novo: String) = rodar {
        val i = salvos.indexOfFirst { it.addr == addr && !it.perdido }
        if (i >= 0 && salvos[i].congelado) cmd { it.cmd("unfreeze $addr") }
        val v = cmd { it.cmd("read $addr $novo") }.status
        if (i >= 0) salvos[i] = salvos[i].copy(tipo = novo, valor = v, congelado = false)
        escolhido = escolhido?.takeIf { it.addr == addr }?.copy(tipo = novo, valor = v)
    }

    // ---- os Salvos ficam guardados no celular, por jogo: ao reabrir, os nomes voltam (o endereço, só pelo caminho) ----
    private var salvosGuardados: android.content.SharedPreferences? = null
    fun guardarSalvos() {
        val j = jogo ?: return
        val a = org.json.JSONArray(salvos.map { s -> org.json.JSONObject().put("addr", s.addr).put("tipo", s.tipo).put("nome", s.nome).put("valor", s.valor).put("congelado", s.congelado) })
        salvosGuardados?.edit()?.putString(j.pacote, a.toString())?.apply()
    }
    fun lerSalvos(pacote: String): List<Salvo> = runCatching {
        val a = org.json.JSONArray(salvosGuardados?.getString(pacote, "[]") ?: "[]")
        (0 until a.length()).map { i -> a.getJSONObject(i).let { o -> Salvo(o.getString("addr"), o.getString("tipo"), o.getString("nome"), o.optString("valor"), false, perdido = true) } }
    }.getOrDefault(emptyList())
    /**
     * Apaga na hora, mesmo com o Unit ocupado (antes esperava a procura terminar e o botão parecia não funcionar), e apaga
     * também o que o Unit guardou para achar o valor ao reabrir: senão ele voltava sozinho na próxima abertura.
     */
    fun esquecerSalvo(s: Salvo) {
        salvos.removeAll { it.nome == s.nome && it.addr == s.addr }
        if (salvos.none { it.nome == s.nome }) Ponteiro.esquecerTudo(s.nome, s.addr)
        if (s.congelado) semAviso { cmd { it.cmd("unfreeze ${s.addr}") } }  // apagado não pode ficar congelado sem ninguém ver
        Registro.add("decisao", "Você apagou ${s.nome} dos salvos")
    }

    fun salvar(a: Achado) { if (salvos.none { it.addr == a.addr && !it.perdido }) salvos += Salvo(a.addr, a.tipo, nomeLivre(), a.valor, false) }
    /** "Valor k" que ninguém usa (nem nos Salvos nem nos caminhos guardados): "Valor 2" repetido misturava dois valores. */
    private fun nomeLivre() = Ponteiro.nomeLivre(salvos.map { it.nome } + (jogo?.let { j -> Ponteiro.lista(j.pacote).map { it.nome } }.orEmpty()))

    // troca o estado na hora e manda o estado de agora: dois toques seguidos (ou a pausa automática) nunca invertem a tela e o jogo
    fun pausar() {
        pausado = !pausado; porDigitar = false; porDuvida = false  // agora a pausa (ou a soltura) é da pessoa
        Registro.add("jogo", if (pausado) "Você pausou o jogo (⏸)" else "Você soltou o jogo (▷)")
        rodar { val quer = pausado; try { cmd { it.cmd(if (quer) "pause" else "resume") } } catch (e: Exception) { if (pausado == quer) pausado = !quer; throw e } }
    }

    /** O Unit está fechando: o jogo nunca pode ficar congelado sem ele (o Android mostraria "não está respondendo"). */
    fun soltarAoFechar() {
        pausado = false
        val m = motor ?: return; motor = null
        Registro.aberturas = 0  // fechado pelo ✕: abrir de novo não é "o motor caiu"
        Thread { runCatching { m.cmd("unfreeze all") }; runCatching { m.cmd("resume") }; m.fechar() }.start()
        salvos.replaceAll { it.copy(congelado = false) }
    }

    /** Os resultados da busca (até 20, os que aparecem) e o valor aberto no Editar, relidos ao vivo: a lista não fica parada no valor da hora da busca. */
    // um pedido por vez: durante uma procura longa no motor, os pedidos de 1 em 1 s se acumulavam esperando (e o
    // "Fechar jogo" ficava atrás deles)
    private var lendoAchados: kotlinx.coroutines.Job? = null
    private var lendoSalvos: kotlinx.coroutines.Job? = null
    fun atualizarAchados() { if (lendoAchados?.isActive == true) return; lendoAchados = lerAchados() }
    private fun lerAchados() = scope.launch {
        if (jogo == null || ocupado) return@launch
        runCatching {
            suspend fun ler(a: Achado) = a.copy(valor = runCatching { cmd { it.cmd("read ${a.addr} ${a.tipo}") }.status }.getOrDefault(a.valor))
            val novos = achados.take(20).map { ler(it) }
            novos.forEach { n -> val i = achados.indexOfFirst { it.addr == n.addr && it.tipo == n.tipo }; if (i >= 0 && achados[i].valor != n.valor) achados[i] = n }
            escolhido?.let { e -> val n = novos.firstOrNull { it.addr == e.addr && it.tipo == e.tipo } ?: ler(e); if (n.valor != e.valor && escolhido?.addr == e.addr) escolhido = e.copy(valor = n.valor) }
        }
    }

    /** Os valores salvos de agora (o mini painel mostra ao vivo). Sem aviso: roda a cada segundo. */
    private val falhouLer = mutableSetOf<String>()  // cada salvo que não deu para ler só vira aviso uma vez
    fun atualizarSalvos() { if (lendoSalvos?.isActive == true) return; lendoSalvos = lerSalvosAgora() }
    // mesmo com o Unit ocupado: o último valor tem que estar em dia quando o jogo fechar (desempata ao reabrir)
    private fun lerSalvosAgora() = scope.launch {
        if (jogo == null || jogoFechou) return@launch
        val pid = jogo?.pid
        runCatching {
            val novos = salvos.toList().filterNot { it.perdido }.map { s -> s.copy(valor = runCatching { cmd { it.cmd("read ${s.addr} ${s.tipo}") }.status }
                .onFailure { e -> if (falhouLer.add(s.addr)) Registro.add("aviso", "Não consegui ler ${s.nome} (${s.addr}): ${e.message} · o endereço pode ter morrido") }
                .getOrDefault(s.valor)) }
            if (jogo?.pid != pid) return@runCatching  // escolheu/reabriu no meio: as leituras eram da abertura de antes
            novos.forEach { n ->
                val i = salvos.indexOfFirst { it.addr == n.addr && !it.perdido }
                if (i < 0 || salvos[i].valor == n.valor) return@forEach
                val antes = salvos[i]
                salvos[i] = antes.copy(valor = n.valor)
                // voltou pelo nome e caiu de repente para 0: pode ter sido a cópia do save (o Ponteiro decide se refaz)
                if (antes.caminho && n.valor.toDoubleOrNull() == 0.0 && (antes.valor.toDoubleOrNull() ?: 0.0) > 0.0) Ponteiro.caiuParaZero(antes.nome, antes.addr)
            }
        }
    }
}
