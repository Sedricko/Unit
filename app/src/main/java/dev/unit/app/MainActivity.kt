package dev.unit.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Um lugar achado na memória: endereço (hex), tipo (i32/f32/i64/f64) e o valor de agora. */
data class Achado(val addr: String, val tipo: String, val valor: String)
data class Jogo(val pid: Int, val pacote: String, val nome: String)

val NOME_TIPO = mapOf("i32" to "Inteiro", "f32" to "Vírgula", "i64" to "Inteiro longo", "f64" to "Vírgula longa")

class MainActivity : ComponentActivity() {
    private var motor: Motor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Tela() }
    }

    override fun onDestroy() { motor?.fechar(); super.onDestroy() }

    /** O motor sobe uma vez; sem root o app explica em vez de quebrar. */
    private suspend fun m(): Motor = withContext(Dispatchers.IO) {
        motor ?: Motor(applicationInfo.nativeLibraryDir + "/libunitd.so").also { motor = it }
    }

    private suspend fun <T> cmd(f: (Motor) -> T): T = withContext(Dispatchers.IO) { f(m()) }

    @Composable
    private fun Tela() {
        val scope = rememberCoroutineScope()
        var aba by remember { mutableStateOf("jogo") }
        var aviso by remember { mutableStateOf("") }
        var jogo by remember { mutableStateOf<Jogo?>(null) }
        val jogos = remember { mutableStateListOf<Jogo>() }
        var campo by remember { mutableStateOf("") }
        var total by remember { mutableStateOf<Long?>(null) }
        val achados = remember { mutableStateListOf<Achado>() }
        var escolhido by remember { mutableStateOf<Achado?>(null) }
        var novo by remember { mutableStateOf("") }
        val congelados = remember { mutableStateListOf<Achado>() }
        var ocupado by remember { mutableStateOf(false) }

        fun rodar(f: suspend () -> Unit) = scope.launch {
            ocupado = true
            try { f() } catch (e: Exception) { aviso = e.message ?: "erro" } finally { ocupado = false }
        }
        suspend fun listar() {
            val r = cmd { it.cmd("list 50") }
            total = r.status.toLongOrNull()
            achados.clear()
            if ((total ?: 0) <= 50) achados += r.linhas.map { it.split(' ') }.filter { it.size == 3 }.map { Achado(it[0], it[1], it[2]) }
            if (achados.size == 1) escolhido = achados[0]
        }
        fun carregarJogos() = rodar {
            val r = cmd { it.cmd("ps") }
            val pm = packageManager
            jogos.clear()
            jogos += r.linhas.mapNotNull { l ->
                val (pid, pac) = l.split(' ', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                val app = runCatching { pm.getApplicationInfo(pac, 0) }.getOrNull() ?: return@mapNotNull null
                // só apps que abrem pela tela inicial e não são do sistema (jogos e apps do usuário)
                if (pm.getLaunchIntentForPackage(pac) == null || pac == packageName) return@mapNotNull null
                if (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0) return@mapNotNull null
                Jogo(pid.toInt(), pac, pm.getApplicationLabel(app).toString())
            }.distinctBy { it.pacote }.sortedBy { it.nome }
            aviso = if (jogos.isEmpty()) "Nenhum jogo aberto. Abra o jogo e toque em Atualizar." else ""
        }
        LaunchedEffect(Unit) { carregarJogos() }

        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Cor.brilho, Cor.fundo), radius = 1400f)).systemBarsPadding()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // topo: nome, jogo escolhido e as duas abas
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Unit", color = Cor.texto, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Selo(jogo?.nome ?: "nenhum jogo", jogo != null)
                }
                Row(Modifier.fillMaxWidth().background(Cor.cartao, RoundedCornerShape(14.dp)).border(1.dp, Cor.linha, RoundedCornerShape(14.dp)).padding(4.dp)) {
                    listOf("jogo" to "Jogo", "busca" to "Busca", "congelados" to "Congelados").forEach { (k, t) ->
                        Box(Modifier.weight(1f).background(if (aba == k) Cor.cartao2 else Cor.cartao, RoundedCornerShape(10.dp)).clickable { aba = k }.padding(10.dp),
                            contentAlignment = Alignment.Center) { Text(t, color = if (aba == k) Cor.texto else Cor.apagado, fontWeight = FontWeight.SemiBold) }
                    }
                }
                if (aviso.isNotBlank()) Text(aviso, color = Cor.ruim, fontSize = 13.sp)

                when (aba) {
                    "jogo" -> {
                        Rotulo("Jogos abertos")
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(jogos) { j ->
                                Cartao(Modifier.clickable {
                                    rodar { cmd { it.cmd("attach ${j.pid}") }; jogo = j; total = null; achados.clear(); escolhido = null; congelados.clear(); aba = "busca"; aviso = "" }
                                }) {
                                    Column(Modifier.weight(1f)) {
                                        Text(j.nome, color = Cor.texto, fontWeight = FontWeight.SemiBold)
                                        Text(j.pacote, color = Cor.apagado, fontSize = 12.sp)
                                    }
                                    if (jogo?.pacote == j.pacote) Selo("escolhido", true)
                                }
                            }
                        }
                        Botao("Atualizar", false) { carregarJogos() }
                    }
                    "busca" -> {
                        if (jogo == null) { Text("Escolha um jogo na aba Jogo.", color = Cor.apagado); return@Column }
                        Rotulo(if (total == null) "Número na tela" else "Número novo na tela")
                        Campo(campo, if (total == null) "digite o número que o jogo mostra" else "mude no jogo e digite o novo") { campo = it }
                        Botao(if (total == null) "Buscar" else "Refinar", true, ocupado || campo.isBlank()) {
                            val v = campo.trim().replace(',', '.')
                            rodar {
                                escolhido = null
                                cmd { it.cmd(if (total == null) "search $v" else "refine $v") }
                                listar(); campo = ""; aviso = ""
                            }
                        }
                        total?.let { n ->
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text("${"%,d".format(n).replace(',', '.')} resultado${if (n == 1L) "" else "s"}", color = Cor.texto, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.weight(1f))
                                Text("começar de novo", color = Cor.a2, fontSize = 13.sp, modifier = Modifier.clickable { total = null; achados.clear(); escolhido = null; campo = "" })
                            }
                            if (n > 50) Text("Muitos lugares têm esse número. Mude o valor no jogo (ganhe ou gaste) e refine.", color = Cor.apagado, fontSize = 13.sp)
                        }
                        val e = escolhido
                        if (e != null) {
                            Cartao {
                                Column(Modifier.weight(1f)) {
                                    Text(e.valor, color = Cor.texto, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                                    Text("${NOME_TIPO[e.tipo]} · 0x${e.addr}", color = Cor.apagado, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                            Rotulo("Valor novo")
                            Campo(novo, "ex.: 999") { novo = it }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Botao("Editar", true, novo.isBlank(), Modifier.weight(1f)) {
                                    val v = novo.trim().replace(',', '.')
                                    rodar { cmd { it.cmd("write ${e.addr} ${e.tipo} $v") }; listar(); novo = ""; aviso = "" }
                                }
                                val frio = congelados.any { it.addr == e.addr }
                                Botao(if (frio) "Soltar" else "Congelar", false, false, Modifier.weight(1f)) {
                                    rodar {
                                        if (frio) { cmd { it.cmd("unfreeze ${e.addr}") }; congelados.removeAll { it.addr == e.addr } }
                                        else {
                                            val v = novo.trim().replace(',', '.').ifBlank { e.valor }
                                            cmd { it.cmd("freeze ${e.addr} ${e.tipo} $v") }; congelados += e.copy(valor = v); novo = ""
                                        }
                                    }
                                }
                            }
                        }
                        if (achados.size > 1) LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(achados) { a ->
                                Cartao(Modifier.clickable { escolhido = a }) {
                                    Column(Modifier.weight(1f)) {
                                        Text(a.valor, color = Cor.texto, fontWeight = FontWeight.SemiBold)
                                        Text("0x${a.addr}", color = Cor.apagado, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                    }
                                    Text(NOME_TIPO[a.tipo] ?: a.tipo, color = Cor.a2, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    else -> {
                        Rotulo("Congelados")
                        if (congelados.isEmpty()) Text("Nada congelado. Ache um valor na Busca e toque em Congelar.", color = Cor.apagado)
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(congelados) { c ->
                                Cartao {
                                    Column(Modifier.weight(1f)) {
                                        Text(c.valor, color = Cor.a2, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                        Text("${NOME_TIPO[c.tipo]} · 0x${c.addr}", color = Cor.apagado, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                    }
                                    Text("Soltar", color = Cor.texto, modifier = Modifier.clickable { rodar { cmd { it.cmd("unfreeze ${c.addr}") }; congelados.remove(c) } }.padding(8.dp))
                                }
                            }
                        }
                    }
                }
                jogo?.let { j ->
                    Botao("Abrir ${j.nome}", false) { packageManager.getLaunchIntentForPackage(j.pacote)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(::startActivity) }
                }
            }
        }
    }
}

@Composable
fun Rotulo(t: String) = Text(t.uppercase(), color = Cor.apagado, fontSize = 11.sp, letterSpacing = 1.5.sp)

@Composable
fun Selo(t: String, ligado: Boolean) = Row(
    Modifier.border(1.dp, Cor.linha, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
) {
    Box(Modifier.size(7.dp).background(if (ligado) Cor.ok else Cor.apagado, RoundedCornerShape(50)))
    Text(t, color = Cor.apagado, fontSize = 12.sp)
}

@Composable
fun Cartao(modifier: Modifier = Modifier, conteudo: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) = Row(
    modifier.fillMaxWidth().background(Cor.cartao, RoundedCornerShape(14.dp)).border(1.dp, Cor.linha, RoundedCornerShape(14.dp)).padding(14.dp),
    verticalAlignment = Alignment.CenterVertically, content = conteudo
)

@Composable
fun Campo(valor: String, dica: String, mudou: (String) -> Unit) = BasicTextField(
    valor, mudou, singleLine = true,
    textStyle = TextStyle(color = Cor.texto, fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    cursorBrush = SolidColor(Cor.a2),
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    decorationBox = { dentro ->
        Box(Modifier.fillMaxWidth().background(Color0, RoundedCornerShape(14.dp)).border(1.dp, Cor.linha, RoundedCornerShape(14.dp)).padding(14.dp)) {
            if (valor.isEmpty()) Text(dica, color = Cor.apagado, fontSize = 15.sp)
            dentro()
        }
    }
)
private val Color0 = androidx.compose.ui.graphics.Color(0xFF050C1F)

@Composable
fun Botao(t: String, principal: Boolean, desligado: Boolean = false, modifier: Modifier = Modifier, clique: () -> Unit) = Box(
    modifier.fillMaxWidth()
        .then(if (principal) Modifier.background(Cor.destaque, RoundedCornerShape(14.dp)) else Modifier.background(Cor.cartao2, RoundedCornerShape(14.dp)).border(1.dp, Cor.linha, RoundedCornerShape(14.dp)))
        .clickable(enabled = !desligado, onClick = clique).padding(14.dp),
    contentAlignment = Alignment.Center
) { Text(t, color = if (principal) Cor.tinta else Cor.texto, fontWeight = FontWeight.Bold, modifier = Modifier.then(if (desligado) Modifier.alpha(0.45f) else Modifier)) }
