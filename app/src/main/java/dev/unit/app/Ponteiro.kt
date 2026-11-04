package dev.unit.app

import android.content.Context
import android.content.SharedPreferences
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** Um valor achado pelo caminho: o nome, o tipo, os caminhos que ainda valem e se volta congelado. */
/** conferido = false: guardado sozinho ao salvar; a próxima abertura do jogo confere pela maioria. */
/** fixo = a pessoa fixou: caminho que falha só sai depois de 3 aberturas seguidas (falhas conta), e a reserva é mantida cheia. */
data class Caminho(val nome: String, val tipo: String, val caminhos: List<String>, val congelar: Boolean = false, val valor: String = "", val conferido: Boolean = true,
                   val fixo: Boolean = false, val falhas: Map<String, Int> = emptyMap(), val pid: Int = 0,  // pid: a abertura em que foi procurado
                   val unity: String = "")  // jogo Unity: "CLASSE ESPAÇO CAMPO [OFF=HEX,...]" - acha o valor pelo nome, sem caminho

/**
 * LABORATÓRIO (teste): ponteiros. Achou um valor uma vez → o motor procura o caminho (biblioteca + deslocamentos) até
 * ele; reabrindo o jogo, confere qual caminho ainda chega no número da tela. Os caminhos ficam guardados no celular
 * (no próprio Unit, por jogo); ao escolher o jogo, os salvos aparecem sozinhos. Exportar/importar por arquivo.
 * Some junto com a tela "caminho" do Balao.kt se não der certo.
 */
object Ponteiro {
    private lateinit var prefs: SharedPreferences
    private lateinit var pasta: File
    fun init(ctx: Context) { prefs = ctx.getSharedPreferences("ponteiros", Context.MODE_PRIVATE); pasta = ctx.getExternalFilesDir(null) ?: ctx.filesDir }
    val arquivo get() = File(pasta, "unit-ponteiros.json")

    /** O que está sendo conferido agora (um por vez): achado num jogo, esperando a reabertura. */
    var pendente by mutableStateOf<Caminho?>(null)
    var aviso by mutableStateOf("")
    private var recarga by mutableStateOf(0)

    // ---- guardar: JSON por jogo (pacote → lista) ----
    fun paraJson(l: List<Caminho>) = JSONArray(l.map { c -> JSONObject().put("nome", c.nome).put("tipo", c.tipo).put("caminhos", JSONArray(c.caminhos)).put("congelar", c.congelar).put("valor", c.valor).put("conferido", c.conferido).put("fixo", c.fixo).put("falhas", JSONObject(c.falhas)).put("pid", c.pid).put("unity", c.unity) })
    fun deJson(a: JSONArray) = (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
        Caminho(o.getString("nome"), o.getString("tipo"), o.getJSONArray("caminhos").let { c -> (0 until c.length()).map(c::getString) }, o.optBoolean("congelar"), o.optString("valor"), o.optBoolean("conferido", true), o.optBoolean("fixo"),
            o.optJSONObject("falhas")?.let { f -> f.keys().asSequence().associateWith { k -> f.getInt(k) } }.orEmpty(), o.optInt("pid"), o.optString("unity")) } }
    fun lista(pacote: String) = runCatching { deJson(JSONArray(prefs.getString(pacote, "[]"))) }.getOrDefault(emptyList())
    // o arquivo (unit-ponteiros.json) acompanha sempre: limpou os mortos, usou, importou, renomeou → já sai atualizado
    private fun gravar(pacote: String, l: List<Caminho>) { prefs.edit().putString(pacote, paraJson(l).toString()).commit(); runCatching { exportar() } }
    /**
     * Junta sem perder nada: mesmo nome no mesmo jogo é FUNDIDO (os caminhos dos dois ficam; os já provados primeiro).
     * O que não fizer sentido sai sozinho na próxima abertura, pela votação da maioria.
     */
    fun juntar(velha: List<Caminho>, nova: List<Caminho>): List<Caminho> {
        val fundidos = nova.map { n -> velha.firstOrNull { it.nome == n.nome }?.let { fundir(it, n) } ?: n }
        return velha.filter { v -> nova.none { it.nome == v.nome } } + fundidos
    }

    fun fundir(v: Caminho, n: Caminho): Caminho {
        // duas procuras de aberturas diferentes (o "rescan" do Cheat Engine): o caminho verdadeiro aparece nas duas, os
        // falsos quase nunca se repetem. Os que estão nas duas viram conferidos, sem votação e sem chute
        if (!v.conferido && !n.conferido && v.pid != 0 && n.pid != 0 && v.pid != n.pid) {
            val antes = v.caminhos.toSet(); val comum = n.caminhos.filter { it in antes }
            if (comum.isNotEmpty()) {
                Registro.add("caminho", "${n.nome}: cruzei duas procuras · ${comum.size} caminho(s) aparecem nas duas aberturas (de ${v.caminhos.size} e ${n.caminhos.size})", lista5(comum, "+ nas duas"))
                return n.copy(caminhos = comum.take(RESERVA), conferido = true, unity = n.unity.ifEmpty { v.unity })
            }
            Registro.add("decisao", "${n.nome}: as duas procuras não têm caminho em comum; guardei os da mais nova para conferir")
            return n.copy(unity = n.unity.ifEmpty { v.unity })
        }
        val ordem = if (v.conferido) v.caminhos + n.caminhos else n.caminhos + v.caminhos  // o provado vem primeiro (desempata a votação)
        return n.copy(caminhos = ordem.distinct().take(AUTO), conferido = v.conferido || n.conferido, congelar = v.congelar || n.congelar,
            fixo = v.fixo || n.fixo, falhas = v.falhas + n.falhas, unity = n.unity.ifEmpty { v.unity })
    }

    /** Resposta do "classe" do motor ("texto OFF HEX", "numeros nOFF=HEX,..." + "ok CLASSE ESPAÇO CAMPO") → o que o "pelonome" pede. */
    fun deClasse(linhas: List<String>, status: String): String? {
        val p = status.split(' ').filter { it.isNotEmpty() }
        if (p.size != 3) return null
        val textos = linhas.filter { it.startsWith("texto ") }.mapNotNull { l -> l.split(' ').takeIf { it.size == 3 }?.let { "${it[1]}=${it[2]}" } }
        // números fixos do objeto (o código do item…): separam objetos da mesma classe sem texto, como o "Item" do Orecraft
        val numeros = linhas.filter { it.startsWith("numeros ") }.flatMap { it.removePrefix("numeros ").split(',') }.filter { it.isNotBlank() }
        val ass = (textos + numeros).joinToString(",")
        return (p + listOfNotNull(ass.ifEmpty { null })).joinToString(" ")
    }
    /** Para o registro e a tela: "ItemRecurso, campo +0x30". */
    fun rotuloUnity(u: String) = u.split(' ').let { if (it.size >= 3) "${it[0]}, campo +0x${it[2]}" else u }

    /**
     * O jogo fechou: guarda o número que estava nos Salvos como o último valor conhecido. O valor muda enquanto se joga
     * (o relatório: salvo com 211, o jogo fechou com 169); ao reabrir, o número é o de quando fechou e desempata os objetos.
     */
    fun lembrarValores(pacote: String, salvos: List<Salvo>) {
        val agora = salvos.filter { !it.perdido && !it.congelado && it.valor.toDoubleOrNull() != null }.associate { it.nome to it.valor }
        val l = lista(pacote)
        if (l.none { it.nome in agora && !it.congelar }) return
        gravar(pacote, l.map { c -> if (c.congelar) c else agora[c.nome]?.let { v -> c.copy(valor = v) } ?: c })
        Registro.add("decisao", "Guardei o último valor de ${l.filter { it.nome in agora && !it.congelar }.joinToString { "${it.nome} = ${agora[it.nome]}" }} (desempata ao reabrir)")
    }

    /**
     * Vários objetos com a mesma assinatura: o que um caminho guardado alcança; senão o ÚNICO com o último valor conhecido
     * (o jogo salva o número: ao reabrir é o mesmo de quando fechou); senão o claramente mais perto dele (o jogo pode ter
     * mudado um pouco). Nenhum se destaca: null (aí o app pede o número).
     */
    fun escolher(cands: List<Pair<String, String?>>, ultimo: String, tipo: String, peloCaminho: Set<String>): String? {
        cands.firstOrNull { it.first in peloCaminho }?.let { return it.first }
        val u = ultimo.toDoubleOrNull() ?: return null
        cands.filter { (_, v) -> v != null && bate(tipo, v, u) }.let { if (it.size == 1) return it[0].first; if (it.size > 1) return null }
        // nenhum igual (o jogo mudou o número desde que fechou: 235 → 236): o mais perto, se for claramente o mais perto
        val d = cands.mapNotNull { (a, v) -> v?.toDoubleOrNull()?.let { a to abs(it - u) } }.sortedBy { it.second }
        if (d.isEmpty()) return null
        val perto = d[0].second <= maxOf(5.0, abs(u) * 0.05)
        val folga = d.size == 1 || d[1].second >= 3 * d[0].second + 1
        return if (perto && folga) d[0].first else null
    }

    /** O número achado é parecido com o último conhecido? (o relatório mostrou um "conferido" com 0, e o jogo com 524) */
    fun plausivel(ultimo: String, valor: String?): Boolean {
        val u = ultimo.toDoubleOrNull()
        return valor?.toDoubleOrNull()?.let { v -> v.isFinite() && v >= 0 && (u == null || u <= 0 || (v > 0 && v in u / 50..u * 50 + 1000)) } == true
    }

    /** O número da tela bate com o da memória? (Float da tela vem arredondado.) */
    fun bate(tipo: String, memoria: String, tela: Double): Boolean {
        val m = memoria.toDoubleOrNull() ?: return false
        return if (tipo.startsWith("f")) abs(m - tela) < 0.5 || abs(m - tela) < 1e-4 else m == tela
    }

    // ---- motor ----
    fun procurar(a: Achado, nome: String) = Nucleo.rodar {
        aviso = "Procurando o caminho… pode levar até 1 minuto."
        val t0 = System.currentTimeMillis()
        val r = Nucleo.cmd { it.cmd("ptrscan ${a.addr} 8") }  // até 8 níveis; o motor manda o mais confiável primeiro
        pendente = Caminho(nome, a.tipo, r.linhas.filter { '+' in it }, valor = a.valor)
        Registro.add("caminho", "$nome: ${pendente!!.caminhos.size} caminhos achados (${(System.currentTimeMillis() - t0) / 1000} s)", lista5(pendente!!.caminhos, "+ achado"))
        aviso = if (pendente!!.caminhos.isEmpty()) "Nenhum caminho achado para esse valor." else ""
        Nucleo.soltarSePausado()
    }

    private suspend fun seguir(c: String, tipo: String): Pair<String, String>? = runCatching {
        val addr = Nucleo.cmd { it.cmd("ptr $c") }.status
        addr to Nucleo.cmd { it.cmd("read $addr $tipo") }.status
    }.getOrNull()

    /**
     * Confirma um valor guardado (a conferir ou já conferido) pelo número que está na tela agora: ficam só os caminhos que
     * chegam nele - certeza, sem votação. Nenhum chegou: não apaga nada e avisa.
     */
    fun confirmarComNumero(nome: String, tela: Double) = Nucleo.rodar {
        val j = Nucleo.jogo ?: return@rodar
        val c = lista(j.pacote).firstOrNull { it.nome == nome } ?: return@rodar
        val chegam = c.caminhos.mapNotNull { cam -> seguir(cam, c.tipo)?.let { (a, v) -> Triple(cam, a, v) } }.filter { bate(c.tipo, it.third, tela) }
        // nenhum caminho, mas é do Unity: o número digitado escolhe entre os objetos achados pelo nome
        if (chegam.isEmpty() && c.unity.isNotEmpty()) {
            val achados = runCatching { Nucleo.cmd { it.cmd("pelonome ${c.unity}") }.linhas.filter { it.isNotBlank() } }.getOrDefault(emptyList())
            val bate1 = achados.take(64).mapNotNull { a -> runCatching { Nucleo.cmd { it.cmd("read $a ${c.tipo}") }.status }.getOrNull()?.let { v -> a to v } }
                .filter { (_, v) -> bate(c.tipo, v, tela) }
            if (bate1.size == 1) {
                val (addr, v) = bate1[0]
                gravar(j.pacote, lista(j.pacote).map { if (it.nome == nome) it.copy(conferido = true, valor = v) else it })
                val s = Salvo(addr, c.tipo, nome, v, false, caminho = true)
                val i = Nucleo.salvos.indexOfFirst { it.nome == nome || it.addr == addr }
                if (i >= 0) Nucleo.salvos[i] = s else Nucleo.salvos += s
                precisaNumero.remove(nome)
                achado[nome] = j.pid to addr
                Registro.add("caminho", "$nome: confirmado pelo número da tela (${EmVolta.texto(tela)}) entre ${achados.size} objetos achados pelo nome")
                recarga++
                return@rodar
            }
        }
        if (chegam.isEmpty()) {
            Registro.add("caminho", "$nome: nenhum dos ${c.caminhos.size} caminhos chega em ${EmVolta.texto(tela)} agora; não apaguei nada")
            throw IllegalStateException("Nenhum caminho chega em ${EmVolta.texto(tela)}. Confira o número (ou espere o jogo carregar) e tente de novo.")
        }
        val bons = chegam.map { it.first }
        gravar(j.pacote, lista(j.pacote).map { if (it.nome == nome) it.copy(caminhos = bons.take(RESERVA), conferido = true, valor = it.valor.ifEmpty { chegam[0].third }) else it })
        val (_, addr, v) = chegam[0]
        val s = Salvo(addr, c.tipo, nome, v, false, caminho = true)
        val i = Nucleo.salvos.indexOfFirst { it.nome == nome || it.addr == addr }
        if (i >= 0) Nucleo.salvos[i] = s else Nucleo.salvos += s
        precisaNumero.remove(nome)
        Registro.add("caminho", "$nome: confirmado pelo número da tela (${EmVolta.texto(tela)}) · ${bons.size} de ${c.caminhos.size} caminhos chegam nele",
            lista5(c.caminhos - bons.toSet(), "– saiu"))
        recarga++
    }

    fun conferir(tela: Double) = Nucleo.rodar {
        val p = pendente ?: return@rodar
        pendente = p.copy(caminhos = p.caminhos.filter { c -> seguir(c, p.tipo)?.let { bate(p.tipo, it.second, tela) } == true })
        val ficou = pendente!!.caminhos
        Registro.add("caminho", "${p.nome}: conferido com ${EmVolta.texto(tela)} · ${p.caminhos.size} → ${ficou.size}", lista5(p.caminhos - ficou.toSet(), "– saiu"))
    }

    /** Guarda no jogo de agora (até 5 caminhos: se um quebrar numa atualização, outro pode valer). */
    fun usar(congelar: Boolean) {
        val p = pendente ?: return; val j = Nucleo.jogo ?: return
        gravar(j.pacote, juntar(lista(j.pacote), listOf(p.copy(caminhos = p.caminhos.take(RESERVA), congelar = congelar))))
        Registro.add("caminho", "${p.nome}: guardado com ${p.caminhos.take(RESERVA).size} caminho(s)" + if (congelar) ", volta congelado em ${p.valor}" else "")
        pendente = null; aplicar()
    }

    /**
     * Dos caminhos que funcionaram agora, quais ficam: os que chegam no endereço da maioria (o mais confiável desempata).
     * Quebrados e os que chegam em outro lugar são mortos. Sem maioria (nenhum ou só 1 por endereço) = guarda todos.
     */
    fun vivos(chegadas: List<Pair<String, String?>>): List<String> {
        val ok = chegadas.filter { it.second != null }
        if (ok.isEmpty()) return chegadas.map { it.first }
        val votos = ok.groupingBy { it.second }.eachCount()
        // só um caminho a favor de cada endereço (ex.: o jogo ainda carregando): pouca prova, não apaga nada
        if (votos.values.max() < 2 && chegadas.size > 1) return chegadas.map { it.first }
        val melhor = ok.first { votos[it.second] == votos.values.max() }.second
        return ok.filter { it.second == melhor }.map { it.first }
    }

    /** Jogo escolhido: cada valor guardado aparece nos Salvos pelo caminho da maioria; os caminhos mortos são apagados. */
    fun aplicar() = Nucleo.rodar {
        val j = Nucleo.jogo ?: return@rodar
        val todos = lista(j.pacote).groupBy { it.nome }.values.map { it.reduce(::fundir) }  // nomes repetidos de antes viram um só
        // jogo Unity: pelo nome primeiro (classe + assinatura). Exatamente 1 objeto = o valor, sem caminho e sem digitar
        val pelonome = mutableMapOf<String, String>()  // nome → endereço achado
        val valorAgora = mutableMapOf<String, String>()
        faltaNome.clear()
        for (c in todos.filter { it.unity.isNotEmpty() }) {
            // já voltou nesta abertura: não decide de novo (o relatório: achou certo aos 20 s e aos 60 s pediu o número)
            val ja = achado[c.nome]
            if (ja != null && ja.first == j.pid) { pelonome[c.nome] = ja.second; continue }
            faltaNome += c.nome  // sai daqui quando achar; senão as tentativas de 20 s e 60 s procuram de novo
            val r = runCatching { Nucleo.cmd { it.cmd("pelonome ${c.unity}") } }.getOrNull()
            val achados = r?.linhas.orEmpty().filter { it.isNotBlank() }
            var escolhido = achados.singleOrNull()
            // vários com a mesma assinatura (no Orecraft: 2 "Item" apontam para a definição da Barra de Cobre): desempata
            if (escolhido == null && achados.size in 2..64) {
                val cands = achados.map { a -> a to runCatching { Nucleo.cmd { it.cmd("read $a ${c.tipo}") }.status }.getOrNull() }
                val porCaminho = c.caminhos.take(RESERVA).mapNotNull { cam -> seguir(cam, c.tipo)?.first }.toSet()
                escolhido = escolher(cands, c.valor, c.tipo, porCaminho)
                Registro.add("decisao", "${c.nome}: pelo nome achei ${achados.size} objetos; " + when {
                    escolhido == null -> "nenhum se destacou (nem caminho nem o último valor, ${c.valor})"
                    porCaminho.any { it == escolhido } -> "fiquei com $escolhido: um caminho guardado chega nele"
                    cands.any { it.first == escolhido && it.second?.let { v -> bate(c.tipo, v, c.valor.toDoubleOrNull() ?: Double.NaN) } == true } -> "fiquei com $escolhido: é o único com o último valor (${c.valor})"
                    else -> "fiquei com $escolhido: é o mais perto do último valor (${c.valor})"
                }, cands.map { "${it.first} = ${it.second ?: "?"}" })
            }
            if (escolhido == null) {
                Registro.add("decisao", "${c.nome}: pelo nome (${rotuloUnity(c.unity)}) achei ${if (r == null) "nada (a classe ainda não carregou?)" else "${achados.size} objeto(s)"}; sigo pelos caminhos")
                continue
            }
            val addr = escolhido
            val v = runCatching { Nucleo.cmd { it.cmd("read $addr ${c.tipo}") }.status }.getOrNull() ?: continue
            val congela = c.congelar && c.valor.isNotEmpty()
            if (congela) Nucleo.cmd { it.cmd("freeze $addr ${c.tipo} ${c.valor}") }
            val s = Salvo(addr, c.tipo, c.nome, if (congela) c.valor else v, congela, caminho = true)
            val i = Nucleo.salvos.indexOfFirst { it.addr == addr || it.nome == c.nome }
            if (i >= 0) Nucleo.salvos[i] = s else Nucleo.salvos += s
            pelonome[c.nome] = addr; faltaNome -= c.nome; achado[c.nome] = j.pid to addr
            if (!congela) valorAgora[c.nome] = v  // o último valor conhecido (desempata na próxima abertura)
            Registro.add("caminho", "${c.nome} pelo nome (${rotuloUnity(c.unity)}) = ${s.valor} · sem caminho e sem digitar")
        }
        val guardados = todos.filter { it.conferido }
        val limpos = guardados.map { c -> c to c.caminhos.map { cam -> cam to seguir(cam, c.tipo)?.first } }
        // os guardados sozinhos ao salvar: achado pelo nome já é a prova (ficam os caminhos que chegam no mesmo lugar)
        val pendentesAgora = todos.filterNot { it.conferido }.map { c ->
            val addr = pelonome[c.nome] ?: return@map conferirSozinho(c)
            c.copy(conferido = true, caminhos = c.caminhos.filter { cam -> seguir(cam, c.tipo)?.first == addr }.take(RESERVA))
        }
        gravar(j.pacote, (limpos.map { (c, ch) -> if (c.fixo) protegido(c, ch) else c.copy(caminhos = vivos(ch)) } + pendentesAgora)
            .map { c -> valorAgora[c.nome]?.let { v -> c.copy(valor = v) } ?: c })  // a cada abertura sobram só os vivos
        for ((c, ch) in limpos) {
            val vivo = vivos(ch).toSet(); val mortos = ch.filter { it.first !in vivo }
            val votos = ch.mapNotNull { it.second }.groupingBy { it }.eachCount()
            if (votos.size > 1) Registro.add("decisao", "${c.nome}: os caminhos chegaram em ${votos.size} lugares; fiquei com o da maioria", votos.entries.sortedByDescending { it.value }.map { "${it.key}: ${it.value} voto(s)" })
            if (vivo.size == ch.size && ch.any { it.second == null }) Registro.add("decisao", "${c.nome}: sem maioria clara, não apaguei nada (espero a próxima abertura)")
            if (mortos.isNotEmpty() || ch.isNotEmpty()) Registro.add("caminho", "${c.nome}: ${vivo.size} vivos, ${mortos.size} mortos apagados",
                lista5(mortos.map { "${it.first}  (${if (it.second == null) "quebrou" else "chegou em outro lugar"})" }, "– saiu"))
        }
        for ((c, ch) in limpos) {
            if (c.nome in pelonome) continue  // já voltou pelo nome
            val cam = vivos(ch).firstOrNull() ?: continue
            val (addr, v) = seguir(cam, c.tipo) ?: continue
            if (c.congelar && c.valor.isNotEmpty()) Nucleo.cmd { it.cmd("freeze $addr ${c.tipo} ${c.valor}") }
            val s = Salvo(addr, c.tipo, c.nome, if (c.congelar && c.valor.isNotEmpty()) c.valor else v, c.congelar && c.valor.isNotEmpty(), caminho = true)
            val i = Nucleo.salvos.indexOfFirst { it.addr == addr || it.nome == c.nome }
            if (i >= 0) Nucleo.salvos[i] = s else Nucleo.salvos += s
            Registro.add("caminho", "${c.nome} pelo caminho $cam = ${s.valor}")
        }
        // auto-alimentar: com poucos caminhos vivos, procura caminhos novos a partir do endereço que um vivo achou agora
        for ((c, ch) in limpos) {
            val chegou = ch.mapNotNull { it.second }
            val addr = chegou.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: continue
            val atual0 = lista(j.pacote).firstOrNull { it.nome == c.nome }
            val limite = if (atual0?.fixo == true) RESERVA / 2 else POUCOS  // fixado: mantém a reserva cheia
            if ((atual0?.caminhos?.size ?: addr.value) >= limite && addr.value >= POUCOS) continue
            if (c.nome in renovados) { Registro.add("decisao", "${c.nome}: não renovei, já renovei uma vez desde que o Unit abriu"); continue }
            // só alimenta com prova: 2+ caminhos concordam, ou o único guardado (já conferido) ainda chega. Senão um caminho
            // errado sozinho viraria "maioria" com os novos que nasceram dele
            if (addr.value < 2 && ch.size > 1) { Registro.add("decisao", "${c.nome}: não renovei, pouca prova (só 1 caminho em cada endereço)"); continue }
            renovados += c.nome
            aviso = "Renovando os caminhos de ${c.nome}… (até 1 minuto)"
            Registro.add("caminho", "${c.nome}: só ${addr.value} vivo(s) → renovando")
            val t0 = System.currentTimeMillis()
            val novos = runCatching { Nucleo.cmd { it.cmd("ptrscan ${addr.key} 8") }.linhas.filter { '+' in it } }.getOrDefault(emptyList())
            val atual = lista(j.pacote).firstOrNull { it.nome == c.nome } ?: continue
            val depois = alimentar(atual.caminhos, novos)
            gravar(j.pacote, juntar(lista(j.pacote), listOf(atual.copy(caminhos = depois))))
            val entraram = depois - atual.caminhos.toSet()
            Registro.add("caminho", "${c.nome}: +${entraram.size} caminhos novos (reserva ${depois.size}/$RESERVA, ${(System.currentTimeMillis() - t0) / 1000} s)", lista5(entraram, "+ entrou"))
            aviso = ""
        }
    }

    const val RESERVA = 20  // caminhos guardados por valor: dá prova para a maioria e reserva se uns quebrarem
    const val POUCOS = 3    // menos vivos que isso: procura caminhos novos
    private val renovados = mutableSetOf<String>()  // uma renovação por valor enquanto o app está aberto (a procura pesa)

    /** Os vivos primeiro (já provados), depois os novos na ordem de confiança do motor, sem repetir, até a reserva. */
    fun alimentar(vivos: List<String>, novos: List<String>) = (vivos + novos).distinct().take(RESERVA)

    /** Detalhe do registro: até 30 caminhos com o sinal (+ entrou / – saiu); o resto vira "… e mais N". */
    fun lista5(l: List<String>, sinal: String) = l.take(30).map { "$sinal $it" } + if (l.size > 30) listOf("… e mais ${l.size - 30}") else emptyList()

    // ---- automático: salvou um valor → procura os caminhos sozinho; na próxima abertura confere pela maioria ----
    private val autoFeitos = mutableSetOf<String>()  // endereços já procurados nesta sessão
    /** O que a procura sozinha está fazendo com cada salvo (por endereço): aparece na linha do salvo. */
    val estadoAuto = androidx.compose.runtime.mutableStateMapOf<String, String>()
    /** Avisa na tela quando a procura termina (o Balao mostra como um aviso rápido do Android). */
    var aoTerminar: ((String) -> Unit)? = null
    const val AUTO = 3000  // todos os que o motor acha (o limite dele): o certo pode não estar entre os primeiros; depois sobram até RESERVA

    fun autoAchar(s: Salvo) {
        val j = Nucleo.jogo ?: return
        // procura mesmo se já houver um "a conferir" com esse nome: a nova procura cruza com a anterior (fundir)
        if (s.caminho || s.perdido || !autoFeitos.add(s.addr) || lista(j.pacote).any { it.nome == s.nome && it.conferido }) return
        estadoAuto[s.addr] = "procurando caminhos…"
        Nucleo.semAviso {
            val t0 = System.currentTimeMillis()
            // jogo Unity: de que objeto o valor faz parte (classe + campo + os textos dele). Ao reabrir, acha pelo nome
            val rc = runCatching { Nucleo.cmd { it.cmd("classe ${s.addr}") } }.getOrNull()
            val unity = rc?.let { r -> deClasse(r.linhas, r.status) }
            // no registro vai também a estrutura do objeto (cada campo: texto, ponteiro para qual classe, número, o valor)
            if (unity != null) Registro.add("caminho", "${s.nome}: é um objeto do Unity (${rotuloUnity(unity)}) · ao reabrir acho pelo nome", listOf(unity) + rc?.linhas.orEmpty())
            val c = runCatching { Nucleo.cmd { it.cmd("ptrscan ${s.addr} 8") }.linhas.filter { '+' in it }.take(AUTO) }.getOrDefault(emptyList())
            if (c.isEmpty() && unity == null) {
                estadoAuto[s.addr] = "nenhum caminho achado"; aoTerminar?.invoke("${s.nome}: nenhum caminho achado")
                Registro.add("caminho", "${s.nome}: nenhum caminho achado sozinho (vai precisar achar de novo ao reabrir)"); return@semAviso
            }
            var nome = Nucleo.salvos.firstOrNull { it.addr == s.addr }?.nome ?: s.nome  // pode ter sido renomeado enquanto procurava
            // o mesmo objeto do Unity (mesma classe, campo e assinatura) já guardado com outro nome: é o mesmo valor. Junta no
            // nome de antes e tira o antigo dos Salvos (o relatório: "Cobre" antigo e "Valor 2" novo, o antigo escolhendo errado)
            val igual = unity?.let { u -> lista(j.pacote).firstOrNull { it.unity == u && it.nome != nome } }
            if (igual != null) {
                val novo = igual.nome
                Nucleo.salvos.removeAll { it.nome == novo && it.addr != s.addr }
                val i = Nucleo.salvos.indexOfFirst { it.addr == s.addr }
                if (i >= 0) Nucleo.salvos[i] = Nucleo.salvos[i].copy(nome = novo)
                Registro.add("decisao", "$nome é o mesmo valor que $novo (mesmo objeto do Unity): juntei em $novo")
                nome = novo
            }
            gravar(j.pacote, juntar(lista(j.pacote), listOf(Caminho(nome, s.tipo, c, valor = s.valor, conferido = false, pid = j.pid, unity = unity ?: ""))))
            Registro.add("caminho", "$nome: ${c.size} caminhos guardados sozinhos (${(System.currentTimeMillis() - t0) / 1000} s) · confiro na próxima abertura", lista5(c, "+ achado"))
            estadoAuto[s.addr] = (if (unity != null) "pelo nome ✓ · " else "") + "${c.size} caminhos guardados · confere ao reabrir"
            aoTerminar?.invoke("$nome: " + if (unity != null) "achado pelo nome ✓" else "caminhos guardados ✓")
        }
    }

    /** Confere sem número: a maioria (3+) chega no mesmo endereço com um número que faz sentido → vira conferido e volta nos Salvos. */
    private suspend fun conferirSozinho(c: Caminho): Caminho {
        val ch = c.caminhos.map { cam -> cam to seguir(cam, c.tipo) }
        val ok = ch.filter { it.second != null }
        val votos = ok.groupingBy { it.second!!.first }.eachCount()
        val melhor = votos.maxByOrNull { it.value }
        val valor = melhor?.let { m -> ok.first { it.second!!.first == m.key }.second!!.second }
        val faz = plausivel(c.valor, valor)  // parecido com o último conhecido
        // zero: a memória tem muito zero solto (o falso positivo do relatório era um 0): pede 5 caminhos concordando, não 3
        val precisa = if (valor?.toDoubleOrNull() == 0.0) 5 else 3
        if (melhor == null || melhor.value < precisa || !faz) {
            Registro.add("decisao", "${c.nome}: não deu para conferir sozinho (${melhor?.value ?: 0} caminho(s) concordam, de ${c.caminhos.size}); guardei todos e tento de novo daqui a pouco e na próxima abertura")
            return c  // não apaga nada: se o jogo ainda estava carregando, os bons pareciam quebrados
        }
        val bons = ok.filter { it.second!!.first == melhor.key }.map { it.first }
        val s = Salvo(melhor.key, c.tipo, c.nome, valor!!, false, caminho = true)
        val i = Nucleo.salvos.indexOfFirst { it.nome == c.nome || it.addr == melhor.key }
        if (i >= 0) Nucleo.salvos[i] = s else Nucleo.salvos += s
        Registro.add("decisao", "${c.nome}: conferi sozinho · ${melhor.value} de ${c.caminhos.size} caminhos chegam em ${melhor.key} (valor $valor)",
            votos.entries.sortedByDescending { it.value }.take(5).map { "${it.key}: ${it.value} voto(s)" })
        return c.copy(caminhos = bons.take(RESERVA), conferido = true)
    }

    /** Fixado: o caminho que falhou só sai na 3ª abertura seguida com falha; o que funcionou zera a conta. */
    fun protegido(c: Caminho, ch: List<Pair<String, String?>>): Caminho {
        val vivo = vivos(ch).toSet()
        val falhas = c.caminhos.associateWith { cam -> if (cam in vivo) 0 else (c.falhas[cam] ?: 0) + 1 }
        val saem = falhas.filter { it.value >= 3 }.keys
        val tropecos = falhas.filter { it.value in 1..2 }
        if (tropecos.isNotEmpty()) Registro.add("decisao", "${c.nome} (fixado): ${tropecos.size} caminho(s) falharam agora, mas ficam até a 3ª falha seguida", tropecos.map { "${it.key}: ${it.value}ª falha" }.take(30))
        if (saem.isNotEmpty()) Registro.add("caminho", "${c.nome} (fixado): ${saem.size} caminho(s) saíram depois de 3 falhas seguidas", lista5(saem.toList(), "– saiu"))
        return c.copy(caminhos = c.caminhos.filter { it !in saem }, falhas = falhas.filter { it.key !in saem && it.value > 0 })
    }

    fun fixar(nome: String, sim: Boolean) {
        val j = Nucleo.jogo ?: return
        gravar(j.pacote, lista(j.pacote).map { if (it.nome == nome) it.copy(fixo = sim, falhas = emptyMap()) else it })
        Registro.add("caminho", "$nome: ${if (sim) "fixado (protegido e realimentado)" else "não está mais fixado"}")
    }

    /** Ainda há valor a conferir: tenta de novo em 20 s e em 60 s (o jogo pode montar as estruturas depois de abrir). */
    fun tentarDeNovo() {
        val j = Nucleo.jogo ?: return
        precisaNumero.clear()
        Nucleo.semAviso {
            for (espera in listOf(20_000L, 40_000L)) {
                kotlinx.coroutines.delay(espera)
                // pelo nome também: logo ao abrir o jogo a classe pode não estar carregada (o relatório: achou nada no 1º segundo)
                if (Nucleo.jogo?.pid != j.pid || (lista(j.pacote).none { !it.conferido } && faltaNome.isEmpty())) return@semAviso
                Registro.add("decisao", "Conferindo os caminhos de novo (${if (espera == 20_000L) "20" else "60"} s depois de abrir)")
                aplicar()
            }
            kotlinx.coroutines.delay(3_000)  // espera a 3ª tentativa terminar
            // não achou sozinho nas 3 tentativas: aí sim pede o número da tela
            val faltam = lista(j.pacote).filter { !it.conferido && achado[it.nome]?.first != j.pid }.map { it.nome }
            if (Nucleo.jogo?.pid == j.pid && faltam.isNotEmpty()) {
                precisaNumero += faltam
                Registro.add("decisao", "Não achei sozinho: ${faltam.joinToString()} · pedindo o número da tela")
                aoTerminar?.invoke("${faltam.joinToString()}: não achei sozinho — toque no aviso do painel para digitar o número")
            }
        }
    }

    /** Valores que já voltaram nesta abertura (nome → pid do jogo e endereço): as tentativas seguintes não mexem neles. */
    private val achado = mutableMapOf<String, Pair<Int, String>>()

    /** Valores do Unity que ainda não voltaram pelo nome nesta abertura (as tentativas de 20 s e 60 s procuram de novo). */
    private val faltaNome = mutableSetOf<String>()

    /** Valores que a conferência sozinha não achou nas 3 tentativas: o painel pede o número da tela. */
    val precisaNumero = mutableStateListOf<String>()

    fun renomear(antigo: String, novo: String) {
        val j = Nucleo.jogo ?: return
        // renomear para um nome que já existe funde os dois (o relatório mostrou 3 "Cobre" separados, de "Valor 1/2" renomeados)
        gravar(j.pacote, lista(j.pacote).map { if (it.nome == antigo) it.copy(nome = novo) else it }.groupBy { it.nome }.values.map { it.reduce(::fundir) })
    }

    fun esquecer(nome: String) { val j = Nucleo.jogo ?: return; gravar(j.pacote, lista(j.pacote).filter { it.nome != nome }) }

    // ---- exportar / importar: um arquivo com todos os jogos ----
    fun exportar(): String {
        val tudo = JSONObject(); prefs.all.keys.forEach { k -> tudo.put(k, JSONArray(prefs.getString(k, "[]"))) }
        arquivo.writeText(tudo.toString(2))
        return arquivo.absolutePath
    }

    /** O que tem no arquivo: (jogo, valor) para a pessoa marcar. */
    fun lerArquivo(): List<Pair<String, Caminho>> = runCatching {
        val o = JSONObject(arquivo.readText())
        o.keys().asSequence().flatMap { k -> deJson(o.getJSONArray(k)).map { k to it } }.toList()
    }.getOrDefault(emptyList())

    /** Juntar: entra o marcado (mesmo nome troca). Substituir: os jogos dos marcados ficam só com o que veio do arquivo. */
    fun importar(marcados: List<Pair<String, Caminho>>, substituir: Boolean) {
        marcados.groupBy({ it.first }, { it.second }).forEach { (pacote, novos) ->
            gravar(pacote, if (substituir) novos else juntar(lista(pacote), novos))
        }
        aplicar()
    }

    @Composable
    fun Tela() = Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        var importando by remember { mutableStateOf<List<Pair<String, Caminho>>?>(null) }
        val abertos = remember { mutableStateListOf<String>() }  // valores com a cascata aberta
        val marcados = remember { mutableStateListOf<Int>() }
        var campo by remember { mutableStateOf("") }
        var congelar by remember { mutableStateOf(false) }
        if (aviso.isNotEmpty()) Text(aviso, color = Cor.a2, fontSize = 13.sp)

        importando?.let { lido ->
            Rotulo("Importar · marque o que quer trazer")
            if (lido.isEmpty()) Text("Não achei o arquivo ${arquivo.absolutePath}", color = Cor.apagado, fontSize = 12.sp)
            lido.forEachIndexed { i, (pacote, c) ->
                Linha(Modifier.toque { if (i in marcados) marcados.remove(i) else marcados += i }) {
                    Column(Modifier.weight(1f)) {
                        Text(c.nome, color = Cor.texto, fontWeight = FontWeight.SemiBold)
                        Text(pacote, color = Cor.apagado, fontSize = 12.sp)
                    }
                    Selo(if (i in marcados) "marcado" else "deixar", i in marcados)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Botao("Juntar", true, Modifier.weight(1f), marcados.isNotEmpty()) { importar(marcados.map(lido::get), false); importando = null }
                Botao("Substituir", false, Modifier.weight(1f), marcados.isNotEmpty()) { importar(marcados.map(lido::get), true); importando = null }
            }
            Text("Juntar: o que já existe fica (mesmo nome é trocado). Substituir: o jogo fica só com o que você marcou.", color = Cor.apagado, fontSize = 12.sp)
            Botao("Cancelar", false, Modifier.fillMaxWidth()) { importando = null }
            return@Column
        }

        pendente?.let { p ->
            Linha {
                Column(Modifier.weight(1f)) {
                    Text(p.nome, color = Cor.texto, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("${p.caminhos.size} caminho(s)", color = Cor.apagado, fontSize = 12.sp)
                }
            }
            if (p.caminhos.isEmpty()) {
                Text("Nenhum caminho continuou. Ache o valor de novo e procure outra vez.", color = Cor.apagado, fontSize = 12.sp)
                Botao("Descartar", false, Modifier.fillMaxWidth()) { pendente = null }
                return@let
            }
            Text("Para conferir: toque em Fechar o jogo, depois em Reabrir, volte aqui e digite o número que está na tela.",
                color = Cor.apagado, fontSize = 12.sp)
            Botao("Fechar o jogo", false, Modifier.fillMaxWidth(), !Nucleo.ocupado && !Nucleo.jogoFechou) { Nucleo.fecharJogo() }
            CampoGrande(campo, "número na tela")
            Teclado("Conferir", campo.isNotBlank() && !Nucleo.ocupado, digitou = { campo += it }, apagou = { campo = campo.dropLast(1) },
                confirmou = { campo.replace(',', '.').toDoubleOrNull()?.let(::conferir); campo = "" })
            Linha(Modifier.toque { congelar = !congelar }) {
                Text("Voltar congelado em ${p.valor}", color = Cor.texto, modifier = Modifier.weight(1f))
                Selo(if (congelar) "sim" else "não", congelar)
            }
            Botao("Usar este caminho", true, Modifier.fillMaxWidth(), !Nucleo.ocupado) { usar(congelar) }
            Botao("Descartar", false, Modifier.fillMaxWidth()) { pendente = null }
        }

        val j = Nucleo.jogo
        Rotulo("Guardados neste jogo")
        @Suppress("UNUSED_EXPRESSION") recarga  // relê a lista quando fixa/desfixa
        val guardados = j?.let { lista(it.pacote) }.orEmpty()
        if (guardados.isEmpty()) Text("Nenhum ainda. No Editar de um valor, toque em Achar o caminho.", color = Cor.apagado, fontSize = 12.sp)
        var confirmando by remember { mutableStateOf<String?>(null) }
        var numero by remember { mutableStateOf("") }
        confirmando?.let { nome ->
            Rotulo("Confirmar $nome")
            Text("Digite o número de $nome que está na tela do jogo agora. Ficam só os caminhos que chegam nele.", color = Cor.apagado, fontSize = 12.sp)
            CampoGrande(numero, "número na tela")
            Teclado("Confirmar", numero.isNotBlank() && !Nucleo.ocupado, digitou = { numero += it }, apagou = { numero = numero.dropLast(1) },
                confirmou = { numero.replace(',', '.').toDoubleOrNull()?.let { confirmarComNumero(nome, it) }; numero = ""; confirmando = null })
            Botao("Cancelar", false, Modifier.fillMaxWidth()) { confirmando = null; numero = "" }
            return@Column
        }
        guardados.forEach { c ->
            val aberto = c.nome in abertos
            // cascata: fechada é uma linha só; tocar abre os caminhos do grupo (o 1º é o mais confiável)
            Linha(Modifier.toque { if (aberto) abertos.remove(c.nome) else abertos += c.nome }) {
                Column(Modifier.weight(1f)) {
                    Text((if (aberto) "▾ " else "▸ ") + c.nome, color = Cor.texto, fontWeight = FontWeight.SemiBold)
                    Text("${NOME_TIPO[c.tipo] ?: c.tipo} · ${c.caminhos.size} caminho(s)" + (if (!c.conferido) " · a conferir na próxima abertura" else "") + if (c.congelar) " · volta congelado" else "", color = Cor.apagado, fontSize = 12.sp)
                }
                if (!c.conferido) Box(Modifier.padding(start = 8.dp).toque { confirmando = c.nome }) { Text("Confirmar", color = Cor.a2, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                if (c.conferido) Box(Modifier.padding(start = 8.dp).toque { fixar(c.nome, !c.fixo); recarga++ }) { Text(if (c.fixo) "📌 Fixado" else "Fixar", color = Cor.a2, fontSize = 13.sp) }
                Box(Modifier.padding(start = 10.dp).toque { esquecer(c.nome); aviso = "${c.nome} esquecido." }) { Text("Esquecer", color = Cor.ruim, fontSize = 13.sp) }
            }
            if (aberto) Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Nucleo.salvos.firstOrNull { it.nome == c.nome && it.caminho }?.let { s ->
                    Text("agora em 0x${s.addr} = ${s.valor}", color = Cor.a2, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
                c.caminhos.forEachIndexed { i, cam ->
                    val f = c.falhas[cam] ?: 0
                    // sem quebrar linha: o caminho é uma palavra comprida e a quebra deixava só o "1." à vista
                    Text("${i + 1}. $cam" + if (f > 0) "   (${f}ª falha)" else "", color = if (f > 0) Color(0xFFFFB86B) else Cor.apagado, fontSize = 11.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Botao("Exportar", false, Modifier.weight(1f)) { aviso = "Salvo em ${exportar()}" }
            Botao("Importar", false, Modifier.weight(1f)) { marcados.clear(); importando = lerArquivo() }
        }
        Text("O arquivo é atualizado sozinho e tem os caminhos de todos os jogos. Para levar a outro celular, copie ele para a mesma pasta lá.", color = Cor.apagado, fontSize = 12.sp)
    }
}
