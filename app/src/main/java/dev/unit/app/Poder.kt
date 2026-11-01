package dev.unit.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File

/** Um candidato da busca por mudança: o valor de agora e o da foto. */
data class Mudou(val addr: String, val tipo: String, val valor: String, val foto: String)

/** O que o vigia achou: o comando (pc), onde ele mora (lib+deslocamento), a instrução e o começo do bloco. */
data class Vigia(val pc: String, val lib: String, val ins: String, val base: Long?, val arm: Boolean)

/**
 * LABORATÓRIO (teste): congelar o estado de um poder. 1) Observar: foto da memória, usar o poder, voltar (busca por
 * mudança). 2) Quem escreve aqui?: o vigia do processador mostra o comando e o bloco do poder. 3) Escolher o momento e
 * congelar os campos que mudam; opcional: desligar o comando (troca a instrução por uma que não faz nada).
 * Some junto com as telas "poder" e "vigia" do Balao.kt se não der certo.
 */
object Poder {
    const val NOP_ARM = "1f2003d5"  // a instrução "não faz nada" do ARM64 (bytes na memória)

    var pid by mutableStateOf(0)
    var foto by mutableStateOf(false)
    var total by mutableStateOf<Long?>(null)
    var usou by mutableStateOf(0)
    val lista = mutableStateListOf<Mudou>()
    var alvo by mutableStateOf<Mudou?>(null)
    var vigia by mutableStateOf<Vigia?>(null)
    var desligado by mutableStateOf<String?>(null)  // bytes antigos do comando desligado (para o Desfazer)
    val congelados = mutableStateListOf<String>()
    var nome by mutableStateOf("Poder")  // o nome que a pessoa deu (Escudo, Recarga do mago…); vira o nome nos Salvos

    /** Troca o nome; os campos já congelados mudam junto nos Salvos ("Escudo 1", "Escudo 2"…). */
    fun renomear(n: String) {
        nome = n.trim().ifEmpty { return }
        congelados.forEachIndexed { k, a -> val i = Nucleo.salvos.indexOfFirst { it.addr == a }; if (i >= 0) Nucleo.salvos[i] = Nucleo.salvos[i].copy(nome = "$nome ${k + 1}") }
    }

    /** Mesmo palpite do motor: inteiro pequeno = Dword; senão, número com vírgula com cara de jogo = Float. */
    fun palpite(raw: Int): Pair<String, String> {
        if (raw > -100_000_000 && raw < 100_000_000) return "i32" to raw.toString()
        val f = java.lang.Float.intBitsToFloat(raw)
        return if (f.isFinite() && kotlin.math.abs(f) > 1e-4f && kotlin.math.abs(f) < 1e8f) "f32" to EmVolta.texto(f.toDouble()) else "i32" to raw.toString()
    }

    /** O valor para congelar sem perder nada: Float pelo texto mais curto que volta aos mesmos bytes. */
    fun exato(raw: Int) = if (palpite(raw).first == "f32") java.lang.Float.intBitsToFloat(raw).toString() else raw.toString()

    /** As linhas do "watch": pc, lib, ins, base; o status diz o processador. */
    fun lerVigia(linhas: List<String>, status: String): Vigia {
        fun campo(n: String) = linhas.firstOrNull { it.startsWith("$n ") }?.substringAfter(' ')
        return Vigia(campo("pc").orEmpty(), campo("lib").orEmpty(), campo("ins").orEmpty(),
            campo("base")?.substringBefore(' ')?.toLongOrNull(16), status == "arm64")
    }

    /** O bloco começa no registro base quando o valor cai até 1 KB depois dele; senão, 32 bytes antes do valor. */
    fun inicioBloco(addr: Long, base: Long?) = if (base != null && addr - base in 0..1024) base else addr - 32

    private fun zerar() { foto = false; total = null; usou = 0; lista.clear(); alvo = null; vigia = null }

    /** Trocou de jogo: começa do zero. */
    private fun conferirJogo() { val p = Nucleo.jogo?.pid ?: 0; if (p != pid) { pid = p; zerar(); desligado = null; congelados.clear(); nome = "Poder" } }

    private suspend fun comparar(modo: String) {
        total = Nucleo.cmd { it.cmd("diff $modo") }.status.substringBefore(' ').toLongOrNull()
        lista.clear()
        if ((total ?: 0) in 1..50) lista += Nucleo.cmd { it.cmd("list 50") }.linhas.map { it.split(' ') }.filter { it.size == 4 }.map { Mudou(it[0], it[1], it[2], it[3]) }
    }

    @Composable
    fun Observar(ctx: Context, escolher: (Mudou) -> Unit) = Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        conferirJogo()
        Text("Com o poder pronto, tire a foto. Use o poder no jogo e toque em Usei. Quando ele voltar a ficar pronto, toque em Voltou. Repita até sobrar pouco.",
            color = Cor.apagado, fontSize = 12.sp)
        if (!foto) {
            Botao("Foto (poder pronto)", true, Modifier.fillMaxWidth(), !Nucleo.ocupado) {
                Nucleo.rodar { Nucleo.cmd { it.cmd("snap ${File(ctx.cacheDir, "foto.bin").absolutePath}") }; foto = true; total = null; usou = 0; lista.clear() }
            }
            return@Column
        }
        Linha {
            Column(Modifier.weight(1f)) {
                Text(total?.let { "$it candidato(s)" } ?: "Foto tirada", color = Cor.texto, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(if (usou == 0) "agora use o poder e toque em Usei" else "usou $usou vez(es)", color = Cor.apagado, fontSize = 12.sp)
            }
            if (Nucleo.ocupado) Text("olhando…", color = Cor.a2, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Botao("Usei", true, Modifier.weight(1f), !Nucleo.ocupado) { Nucleo.rodar { comparar("mudou"); usou++ } }
            Botao("Voltou", false, Modifier.weight(1f), !Nucleo.ocupado && total != null) { Nucleo.rodar { comparar("foto") } }
        }
        // a busca por mudança completa (para outros usos além do poder)
        Rotulo("Outros filtros")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("aumentou" to "Aumentou", "diminuiu" to "Diminuiu", "igual" to "Não mudou").forEach { (m, n) ->
                Selo(n, false) { if (!Nucleo.ocupado && (total != null || m != "igual")) Nucleo.rodar { comparar(m) } }
            }
        }
        lista.forEach { m ->
            Linha(Modifier.toque { alvo = m; vigia = null; escolher(m) }) {
                Text(m.addr, color = Cor.texto, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                Text(NOME_TIPO[m.tipo] ?: m.tipo, color = Cor.a2, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp))
                Text("${m.foto} → ${m.valor}", color = Cor.texto, fontSize = 13.sp)
            }
        }
        if ((total ?: 0) > 50) Text("Ainda são muitos: use o poder de novo (Usei) e depois Voltou.", color = Cor.apagado, fontSize = 12.sp)
        Botao("Recomeçar", false, Modifier.fillMaxWidth()) { zerar() }
    }

    @Composable
    fun TelaVigia(darNome: () -> Unit) = Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val m = alvo ?: return@Column
        val addr = m.addr.toLong(16)
        Linha {
            Text(m.addr, color = Cor.texto, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            Text("${NOME_TIPO[m.tipo] ?: m.tipo} · ${m.valor}", color = Cor.apagado, fontSize = 13.sp)
        }
        Linha(Modifier.toque(clique = darNome)) {
            Column(Modifier.weight(1f)) {
                Text(nome, color = Cor.texto, fontWeight = FontWeight.SemiBold)
                Text("nome do que você vai congelar · tocar para mudar", color = Cor.apagado, fontSize = 12.sp)
            }
        }
        val v = vigia
        if (v == null) {
            Text("Toque abaixo e use o poder no jogo em até 30 segundos. O vigia mostra o comando que escreveu aqui. Precisa de root.",
                color = Cor.apagado, fontSize = 12.sp)
            Botao(if (Nucleo.ocupado) "Vigiando… use o poder" else "Quem escreve aqui?", true, Modifier.fillMaxWidth(), !Nucleo.ocupado) {
                Nucleo.rodar {
                    if (Nucleo.pausado) { Nucleo.cmd { it.cmd("resume") }; Nucleo.pausado = false }  // o jogo precisa rodar para escrever
                    val r = Nucleo.cmd { it.cmd("watch ${m.addr} 30") }
                    vigia = lerVigia(r.linhas, r.status)
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().background(Color(0x55000000), RoundedCornerShape(10.dp)).border(1.dp, Color(0x5567C6FF), RoundedCornerShape(10.dp)).padding(10.dp)) {
                Text(v.lib.ifEmpty { v.pc }, color = Cor.texto, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                if (v.ins.isNotEmpty()) Text("instrução ${v.ins}", color = Cor.a2, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                v.base?.let { Text("bloco em ${it.toString(16)}", color = Cor.apagado, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
            }
            BlocoDoPoder(inicioBloco(addr, v.base), addr)
            if (v.arm) Linha(Modifier.toque {
                val antigos = desligado
                Nucleo.rodar {
                    desligado = if (antigos == null) Nucleo.cmd { it.cmd("patch ${v.pc} $NOP_ARM") }.status
                    else { Nucleo.cmd { it.cmd("patch ${v.pc} $antigos") }; null }
                }
            }) {
                Column(Modifier.weight(1f)) {
                    Text(if (desligado == null) "Desligar esse comando" else "Comando desligado · tocar desfaz", color = Cor.texto, fontWeight = FontWeight.SemiBold)
                    Text("troca a instrução por uma que não faz nada; vale até fechar o jogo. Se o jogo fechar, era o comando errado.", color = Cor.apagado, fontSize = 12.sp)
                }
                Selo(if (desligado == null) "ligado" else "desligado", desligado != null)
            }
            Botao("Vigiar de novo", false, Modifier.fillMaxWidth()) { vigia = null }
        }
    }

    /** 16 campos do bloco, relidos a cada meio segundo; os que mudam desde que a tela abriu vêm marcados. */
    @Composable
    private fun BlocoDoPoder(inicio: Long, addr: Long) {
        val mudam = remember(inicio) { mutableStateListOf<Int>() }
        val marcados = remember(inicio) { mutableStateListOf<Int>() }
        var primeiro by remember(inicio) { mutableStateOf<IntArray?>(null) }
        var agora by remember(inicio) { mutableStateOf<IntArray?>(null) }
        LaunchedEffect(inicio) {
            while (true) {
                runCatching { Nucleo.cmd { it.cmd("bytes ${inicio.toString(16)} 64") }.status }.onSuccess { h ->
                    val v = IntArray(16) { i -> (0 until 4).fold(0) { acc, k -> acc or (h.substring(8 * i + 2 * k, 8 * i + 2 * k + 2).toInt(16) shl (8 * k)) } }
                    val p = primeiro ?: v.also { primeiro = it }
                    for (i in 0 until 16) if (v[i] != p[i] && i !in mudam) { mudam += i; marcados += i }
                    agora = v
                }
                delay(500)
            }
        }
        Rotulo("Bloco do poder · espere o momento certo")
        Text("Os campos que mudam ficam marcados. Use o poder, espere ele ficar pronto (ou ativo) e toque em Congelar este estado.",
            color = Cor.apagado, fontSize = 12.sp)
        val v = agora ?: return
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            for (i in 0 until 16) {
                val a = inicio + 4 * i; val (t, txt) = palpite(v[i]); val eu = a == addr; val marc = i in marcados
                Row(Modifier.fillMaxWidth().then(if (eu) Modifier.background(Color(0x333B8CFF), RoundedCornerShape(8.dp)) else Modifier)
                    .toque { if (marc) marcados.remove(i) else marcados += i }.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(16.dp).then(if (marc) Modifier.background(Cor.a2, RoundedCornerShape(4.dp)) else Modifier.border(1.dp, Cor.linha, RoundedCornerShape(4.dp))))
                    Text("+0x" + (4 * i).toString(16), color = Cor.apagado, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 8.dp).width(52.dp))
                    Text(txt, color = if (i in mudam) Cor.texto else Cor.apagado, fontSize = 14.sp, fontWeight = if (eu) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                    Text(NOME_TIPO[t] ?: t, color = Cor.a2, fontSize = 11.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Botao("Congelar este estado", true, Modifier.weight(1f), marcados.isNotEmpty() && !Nucleo.ocupado) {
                val campos = marcados.sorted().map { i -> Triple((inicio + 4 * i).toString(16), palpite(v[i]).first, exato(v[i])) }
                Nucleo.congelarVarios(campos, nome)
                congelados.clear(); congelados += campos.map { it.first }
            }
            Botao("Desfazer", false, Modifier.weight(1f), congelados.isNotEmpty()) { Nucleo.soltar(congelados.toList()); congelados.clear() }
        }
    }
}
