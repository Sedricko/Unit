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
                   val fixo: Boolean = false, val falhas: Map<String, Int> = emptyMap())

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
    fun paraJson(l: List<Caminho>) = JSONArray(l.map { c -> JSONObject().put("nome", c.nome).put("tipo", c.tipo).put("caminhos", JSONArray(c.caminhos)).put("congelar", c.congelar).put("valor", c.valor).put("conferido", c.conferido).put("fixo", c.fixo).put("falhas", JSONObject(c.falhas)) })
    fun deJson(a: JSONArray) = (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
        Caminho(o.getString("nome"), o.getString("tipo"), o.getJSONArray("caminhos").let { c -> (0 until c.length()).map(c::getString) }, o.optBoolean("congelar"), o.optString("valor"), o.optBoolean("conferido", true), o.optBoolean("fixo"),
            o.optJSONObject("falhas")?.let { f -> f.keys().asSequence().associateWith { k -> f.getInt(k) } }.orEmpty()) } }
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
        val ordem = if (v.conferido) v.caminhos + n.caminhos else n.caminhos + v.caminhos  // o provado vem primeiro (desempata a votação)
        return n.copy(caminhos = ordem.distinct().take(AUTO), conferido = v.conferido || n.conferido, congelar = v.congelar || n.congelar,
            fixo = v.fixo || n.fixo, falhas = v.falhas + n.falhas)
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
        val guardados = todos.filter { it.conferido }
        val limpos = guardados.map { c -> c to c.caminhos.map { cam -> cam to seguir(cam, c.tipo)?.first } }
        val pendentesAgora = todos.filterNot { it.conferido }.map { conferirSozinho(it) }  // os guardados sozinhos ao salvar
        gravar(j.pacote, limpos.map { (c, ch) -> if (c.fixo) protegido(c, ch) else c.copy(caminhos = vivos(ch)) } + pendentesAgora)  // a cada abertura sobram só os vivos
        for ((c, ch) in limpos) {
            val vivo = vivos(ch).toSet(); val mortos = ch.filter { it.first !in vivo }
            val votos = ch.mapNotNull { it.second }.groupingBy { it }.eachCount()
            if (votos.size > 1) Registro.add("decisao", "${c.nome}: os caminhos chegaram em ${votos.size} lugares; fiquei com o da maioria", votos.entries.sortedByDescending { it.value }.map { "${it.key}: ${it.value} voto(s)" })
            if (vivo.size == ch.size && ch.any { it.second == null }) Registro.add("decisao", "${c.nome}: sem maioria clara, não apaguei nada (espero a próxima abertura)")
            if (mortos.isNotEmpty() || ch.isNotEmpty()) Registro.add("caminho", "${c.nome}: ${vivo.size} vivos, ${mortos.size} mortos apagados",
                lista5(mortos.map { "${it.first}  (${if (it.second == null) "quebrou" else "chegou em outro lugar"})" }, "– saiu"))
        }
        for ((c, ch) in limpos) {
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
    const val AUTO = 1000  // todos os que o motor acha (o limite dele): o certo pode não estar entre os primeiros; depois sobram até RESERVA

    fun autoAchar(s: Salvo) {
        val j = Nucleo.jogo ?: return
        if (s.caminho || s.perdido || !autoFeitos.add(s.addr) || lista(j.pacote).any { it.nome == s.nome }) return
        estadoAuto[s.addr] = "procurando caminhos…"
        Nucleo.semAviso {
            val t0 = System.currentTimeMillis()
            val c = runCatching { Nucleo.cmd { it.cmd("ptrscan ${s.addr} 8") }.linhas.filter { '+' in it }.take(AUTO) }.getOrDefault(emptyList())
            if (c.isEmpty()) {
                estadoAuto[s.addr] = "nenhum caminho achado"; aoTerminar?.invoke("${s.nome}: nenhum caminho achado")
                Registro.add("caminho", "${s.nome}: nenhum caminho achado sozinho (vai precisar achar de novo ao reabrir)"); return@semAviso
            }
            val nome = Nucleo.salvos.firstOrNull { it.addr == s.addr }?.nome ?: s.nome  // pode ter sido renomeado enquanto procurava
            gravar(j.pacote, juntar(lista(j.pacote), listOf(Caminho(nome, s.tipo, c, valor = s.valor, conferido = false))))
            Registro.add("caminho", "$nome: ${c.size} caminhos guardados sozinhos (${(System.currentTimeMillis() - t0) / 1000} s) · confiro na próxima abertura", lista5(c, "+ achado"))
            estadoAuto[s.addr] = "${c.size} caminhos guardados · confere ao reabrir"; aoTerminar?.invoke("$nome: caminhos guardados ✓")
        }
    }

    /** Confere sem número: a maioria (3+) chega no mesmo endereço com um número que faz sentido → vira conferido e volta nos Salvos. */
    private suspend fun conferirSozinho(c: Caminho): Caminho {
        val ch = c.caminhos.map { cam -> cam to seguir(cam, c.tipo) }
        val ok = ch.filter { it.second != null }
        val votos = ok.groupingBy { it.second!!.first }.eachCount()
        val melhor = votos.maxByOrNull { it.value }
        val valor = melhor?.let { m -> ok.first { it.second!!.first == m.key }.second!!.second }
        val faz = valor?.toDoubleOrNull()?.let { it.isFinite() && it >= 0 } == true
        if (melhor == null || melhor.value < 3 || !faz) {
            Registro.add("decisao", "${c.nome}: não deu para conferir sozinho (${melhor?.value ?: 0} caminho(s) concordam, de ${c.caminhos.size}); fica guardado para a próxima abertura")
            return c.copy(caminhos = ok.map { it.first }.ifEmpty { c.caminhos })  // tira só os quebrados
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
        guardados.forEach { c ->
            val aberto = c.nome in abertos
            // cascata: fechada é uma linha só; tocar abre os caminhos do grupo (o 1º é o mais confiável)
            Linha(Modifier.toque { if (aberto) abertos.remove(c.nome) else abertos += c.nome }) {
                Column(Modifier.weight(1f)) {
                    Text((if (aberto) "▾ " else "▸ ") + c.nome, color = Cor.texto, fontWeight = FontWeight.SemiBold)
                    Text("${NOME_TIPO[c.tipo] ?: c.tipo} · ${c.caminhos.size} caminho(s)" + (if (!c.conferido) " · a conferir na próxima abertura" else "") + if (c.congelar) " · volta congelado" else "", color = Cor.apagado, fontSize = 12.sp)
                }
                if (c.conferido) Box(Modifier.padding(start = 8.dp).toque { fixar(c.nome, !c.fixo); recarga++ }) { Text(if (c.fixo) "📌 Fixado" else "Fixar", color = Cor.a2, fontSize = 13.sp) }
                Box(Modifier.padding(start = 10.dp).toque { esquecer(c.nome); aviso = "${c.nome} esquecido." }) { Text("Esquecer", color = Cor.ruim, fontSize = 13.sp) }
            }
            if (aberto) Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Nucleo.salvos.firstOrNull { it.nome == c.nome && it.caminho }?.let { s ->
                    Text("agora em 0x${s.addr} = ${s.valor}", color = Cor.a2, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
                c.caminhos.forEachIndexed { i, cam ->
                    val f = c.falhas[cam] ?: 0
                    Text("${i + 1}. $cam" + if (f > 0) "   (${f}ª falha)" else "", color = if (f > 0) Color(0xFFFFB86B) else Cor.apagado, fontSize = 11.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 1)
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
