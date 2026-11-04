package dev.unit.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.round

/**
 * LABORATÓRIO (teste): "Em volta" - os 100 campos acima e abaixo de um valor, como o GameGuardian. Auto por padrão (cada
 * campo no tipo que faz sentido; procurando, testa os 4 tipos); a pessoa pode trocar para um tipo só.
 */
data class Vizinho(val desloc: Int, val addr: Long, val tipo: String, val valor: String)

object EmVolta {
    const val CAMPOS = 100
    const val ANTES = CAMPOS * 4

    fun texto(v: Double): String = if (v == round(v) && abs(v) < 1e15) v.toLong().toString()
        else "%.4f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')

    /** bytes começa em base-400 e tem 808 bytes (o último Qword cabe). tipo = "auto" ou i32/f32/i64/f64. */
    fun ler(bytes: ByteArray, base: Long, tipo: String, procura: Double?): List<Vizinho> {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = mutableListOf<Vizinho>()
        for (k in -CAMPOS..CAMPOS) {
            val i = ANTES + 4 * k; if (i < 0 || i + 4 > bytes.size) continue
            val d = 4 * k; val a = base + d
            val vals = buildMap {
                put("i32", bb.getInt(i).toDouble()); put("f32", bb.getFloat(i).toDouble())
                if (i + 8 <= bytes.size) { put("i64", bb.getLong(i).toDouble()); put("f64", bb.getDouble(i)) }
            }
            fun bate(t: String, v: Double) = if (t.startsWith("f")) v.isFinite() && abs(v - procura!!) < 0.5 else v == procura
            if (procura != null) {
                for ((t, v) in vals) if ((tipo == "auto" || t == tipo) && bate(t, v)) out += Vizinho(d, a, t, texto(v))
            } else if (tipo == "auto") {
                val f = vals.getValue("f32")
                val pareceFloat = f.isFinite() && abs(f) in 1e-3..1e7 && abs(f * 100 - round(f * 100)) < 1e-3
                out += if (pareceFloat) Vizinho(d, a, "f32", texto(f)) else Vizinho(d, a, "i32", texto(vals.getValue("i32")))
            } else vals[tipo]?.let { out += Vizinho(d, a, tipo, texto(it)) }
        }
        return out
    }

    @Composable
    fun Tela(base: Achado, abrir: (Achado) -> Unit) {
        val b = base.addr.toLong(16)
        var tipo by remember { mutableStateOf("auto") }
        var campo by remember { mutableStateOf("") }
        var procura by remember { mutableStateOf<Double?>(null) }
        var restam by remember { mutableStateOf<Set<String>?>(null) }  // depois de procurar: os que bateram (refinar corta daqui)
        var bytes by remember { mutableStateOf<ByteArray?>(null) }
        var erro by remember { mutableStateOf("") }
        // relê a cada 2 s enquanto a tela está aberta (os vizinhos mudam com o jogo)
        LaunchedEffect(b) {
            while (true) {
                runCatching { Nucleo.cmd { it.cmd("bytes ${(b - ANTES).toString(16)} ${ANTES * 2 + 8}") }.status }
                    .onSuccess { h -> bytes = ByteArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() }; erro = "" }
                    .onFailure { erro = it.message ?: "não li a memória" }
                delay(2000)
            }
        }
        fun chave(v: Vizinho) = "${v.desloc}${v.tipo}"
        val lista = bytes?.let { m -> ler(m, b, tipo, procura).filter { restam == null || chave(it) in restam!! } }.orEmpty()
        fun confirmar() {
            val n = campo.replace(',', '.').toDoubleOrNull() ?: return
            val m = bytes ?: return
            val novos = ler(m, b, tipo, n).map(::chave).toSet()
            restam = if (restam == null) novos else restam!! intersect novos
            procura = n; campo = ""
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("auto" to "Auto", "i32" to "Dword", "f32" to "Float", "i64" to "Qword", "f64" to "Double").forEach { (t, n) ->
                    Selo(n, tipo == t) { tipo = t; restam = null; procura = null }
                }
            }
            CampoGrande(campo, if (restam == null) "procurar número" else "número novo")
            if (erro.isNotBlank()) Text(erro, color = Cor.ruim, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (restam != null) "${lista.size} vizinho(s)" else "100 campos acima e 100 abaixo · role para ver mais",
                    color = if (restam != null) Cor.texto else Cor.apagado, fontSize = if (restam != null) 15.sp else 12.sp,
                    fontWeight = if (restam != null) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                if (restam != null) Text("ver todos", color = Cor.a2, fontSize = 13.sp, modifier = Modifier.toque { restam = null; procura = null })
            }
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                items(lista, key = { chave(it) }) { v ->
                    val eu = v.desloc == 0
                    val a = Achado(v.addr.toString(16), v.tipo, v.valor)
                    Row(Modifier.fillMaxWidth().then(if (eu) Modifier.background(Color(0x333B8CFF), RoundedCornerShape(10.dp)).border(1.dp, Color(0x8867C6FF), RoundedCornerShape(10.dp)) else Modifier)
                        .toque { abrir(a) }.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text((if (v.desloc < 0) "−0x" else if (v.desloc > 0) "+0x" else "") + abs(v.desloc).toString(16).let { if (v.desloc == 0) "0" else it },
                            color = Cor.apagado, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(60.dp))
                        Text(v.valor, color = Cor.texto, fontSize = 15.sp, fontWeight = if (eu) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                        Text(NOME_TIPO[v.tipo] ?: v.tipo, color = Cor.a2, fontSize = 11.sp)
                        val ja = Nucleo.salvos.any { it.addr == a.addr }
                        if (!eu) Box(Modifier.padding(start = 8.dp).toque { Nucleo.salvar(a) }) { Icone(if (ja) Ic.SALVO else Ic.SALVAR, 20.dp, if (ja) Cor.a2 else Cor.icone) }
                    }
                }
            }
            Teclado(if (restam == null) "Procurar" else "Refinar", campo.isNotBlank() && bytes != null,
                digitou = { campo += it }, apagou = { campo = campo.dropLast(1) }, confirmou = { confirmar() })
        }
    }
}
