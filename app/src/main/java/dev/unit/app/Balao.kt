package dev.unit.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay

/**
 * O Unit sobre o jogo, em 3 tamanhos: o balão "U" (arrastável), o mini painel (valores salvos ao vivo) e o painel de
 * vidro com os blocos. A janela tem só o tamanho do que mostra: o toque fora dela vai para o jogo.
 */
class Balao : Service() {
    private lateinit var wm: WindowManager
    private val dono = Dono()
    private var vista: ComposeView? = null
    private var modo by mutableStateOf("balao")   // balao | mini | painel
    private val params = LayoutParams(
        LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, LayoutParams.TYPE_APPLICATION_OVERLAY,
        LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,  // teclado próprio: a janela nunca pega o foco do jogo
    )
    private var bx = 24; private var by = 360   // onde o balão estava (volta para lá ao minimizar)

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        primeiroPlano()
        wm = getSystemService(WindowManager::class.java)
        dono.iniciar()
        val v = ComposeView(this).apply {
            setViewTreeLifecycleOwner(dono); setViewTreeSavedStateRegistryOwner(dono)
            setContent { Conteudo() }
        }
        vista = v
        posicionar()
        wm.addView(v, params)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == FECHAR) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() { vista?.let(wm::removeView); dono.parar(); super.onDestroy() }

    override fun onConfigurationChanged(c: Configuration) { super.onConfigurationChanged(c); posicionar(); vista?.let { wm.updateViewLayout(it, params) } }

    private fun tela(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
        else resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    /** Em pé o painel sobe de baixo (62%); deitado, fica na direita (45%). O mini é uma faixa embaixo. */
    private fun posicionar() {
        val (w, h) = tela()
        when (modo) {
            "balao" -> { params.width = LayoutParams.WRAP_CONTENT; params.height = LayoutParams.WRAP_CONTENT; params.gravity = Gravity.TOP or Gravity.START; params.x = bx; params.y = by }
            "mini" -> { params.width = LayoutParams.MATCH_PARENT; params.height = LayoutParams.WRAP_CONTENT; params.gravity = Gravity.BOTTOM; params.x = 0; params.y = 0 }
            else -> if (w > h) { params.width = (w * .45f).toInt(); params.height = LayoutParams.MATCH_PARENT; params.gravity = Gravity.END or Gravity.TOP; params.x = 0; params.y = 0 }
                    else { params.width = LayoutParams.MATCH_PARENT; params.height = (h * .62f).toInt(); params.gravity = Gravity.BOTTOM; params.x = 0; params.y = 0 }
        }
    }

    private fun mudar(m: String) { modo = m; posicionar(); vista?.let { wm.updateViewLayout(it, params) } }

    @Composable
    private fun Conteudo() {
        // os salvos ao vivo enquanto o mini ou o painel está à vista
        LaunchedEffect(modo) { while (modo != "balao") { Nucleo.atualizarSalvos(); delay(1000) } }
        when (modo) {
            "balao" -> Box(
                Modifier.size(52.dp).background(Color(0xD90E162C), CircleShape).border(1.dp, Cor.linha, CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures { ch, d ->
                            ch.consume(); bx += d.x.toInt(); by += d.y.toInt(); params.x = bx; params.y = by
                            vista?.let { wm.updateViewLayout(it, params) }
                        }
                    }
                    .pointerInput(Unit) { detectTapGestures(onTap = { mudar(if (Nucleo.salvos.isEmpty()) "painel" else "mini") }, onLongPress = { mudar("painel") }) },
                contentAlignment = Alignment.Center
            ) { Text("U", color = Cor.a2, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp) }
            "mini" -> Mini()
            else -> Painel()
        }
    }

    @Composable
    private fun Mini() = Column(
        Modifier.fillMaxWidth().padding(10.dp).background(Cor.vidro, RoundedCornerShape(22.dp)).border(1.dp, Cor.linha, RoundedCornerShape(22.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Nucleo.salvos.take(3).forEach { s ->
                Column(Modifier.weight(1f).background(Cor.bloco, RoundedCornerShape(14.dp)).border(1.dp, Cor.linha, RoundedCornerShape(14.dp))
                    .clickable { Nucleo.escolhido = Achado(s.addr, s.tipo, s.valor); tela = "editar"; mudar("painel") }.padding(8.dp)) {
                    Text(s.nome + if (s.congelado) " · congelado" else "", color = Cor.apagado, fontSize = 11.sp, maxLines = 1)
                    Text(s.valor, color = if (s.congelado) Cor.a2 else Cor.texto, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Botao("Achar", true) { tela = "achar"; mudar("painel") }
            Spacer(Modifier.weight(1f))
            BotaoIcone(if (Nucleo.pausado) Ic.CONTINUAR else Ic.PAUSAR) { Nucleo.pausar() }
            BotaoIcone(Ic.EXPANDIR) { mudar("painel") }
            BotaoIcone(Ic.MINIMIZAR) { mudar("balao") }
        }
    }

    private var tela by mutableStateOf("inicio")   // inicio | achar | editar | salvos

    @Composable
    private fun Painel() = Column(
        Modifier.fillMaxSize().background(Cor.vidro, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).border(1.dp, Cor.linha, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tela == "inicio") Icone(Ic.JOGO) else Box(Modifier.clickable { tela = "inicio" }) { Text("‹", color = Cor.texto, fontSize = 26.sp) }
            Column(Modifier.weight(1f)) {
                Text(when (tela) { "achar" -> "Achar"; "editar" -> "Editar"; "salvos" -> "Salvos"; else -> Nucleo.jogo?.nome ?: "Unit" }, color = Cor.texto, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(if (Nucleo.pausado) "jogo pausado" else Nucleo.jogo?.pacote ?: "escolha o jogo no app", color = Cor.apagado, fontSize = 12.sp, maxLines = 1)
            }
            BotaoIcone(if (Nucleo.pausado) Ic.CONTINUAR else Ic.PAUSAR) { Nucleo.pausar() }
            BotaoIcone(Ic.MINIMIZAR) { mudar(if (Nucleo.salvos.isEmpty()) "balao" else "mini") }
            BotaoIcone(Ic.FECHAR) { stopSelf() }
        }
        if (Nucleo.aviso.isNotBlank()) Text(Nucleo.aviso, color = Cor.ruim, fontSize = 13.sp)
        when (tela) {
            "achar" -> Achar()
            "editar" -> Editar()
            "salvos" -> Salvos()
            else -> Inicio()
        }
    }

    @Composable
    private fun Inicio() = Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Bloco(Ic.ACHAR, "Achar", Nucleo.jogo != null, Modifier.weight(1f)) { tela = "achar" }
            Bloco(Ic.EDITAR, "Editar", Nucleo.escolhido != null, Modifier.weight(1f)) { tela = "editar" }
            Bloco(Ic.SALVOS, "Salvos", Nucleo.salvos.isNotEmpty(), Modifier.weight(1f)) { tela = "salvos" }
        }
        if (Nucleo.salvos.isNotEmpty()) {
            Rotulo("Salvos")
            Nucleo.salvos.take(4).forEach { s -> LinhaSalvo(s) }
        } else Text("Toque em Achar e digite um número que o jogo mostra.", color = Cor.apagado, fontSize = 13.sp)
    }

    @Composable
    private fun LinhaSalvo(s: Salvo) = Linha(Modifier.clickable { Nucleo.escolhido = Achado(s.addr, s.tipo, s.valor); tela = "editar" }) {
        Column(Modifier.weight(1f)) {
            Text(s.nome, color = Cor.texto, fontSize = 15.sp)
            Text((NOME_TIPO[s.tipo] ?: s.tipo) + if (s.congelado) " · congelado" else "", color = Cor.apagado, fontSize = 11.sp)
        }
        Text(s.valor, color = if (s.congelado) Cor.a2 else Cor.texto, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.padding(start = 8.dp).clickable { Nucleo.alternarCongelado(s) }) { Icone(Ic.CONGELAR, 20.dp, if (s.congelado) Cor.a2 else Cor.apagado) }
    }

    @Composable
    private fun Achar() {
        var campo by remember { mutableStateOf("") }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CampoGrande(campo, if (Nucleo.total == null) "número na tela" else "número novo")
            Nucleo.total?.let { n ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${"%,d".format(n).replace(',', '.')} resultado${if (n == 1L) "" else "s"}", color = Cor.texto, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("começar de novo", color = Cor.a2, fontSize = 13.sp, modifier = Modifier.clickable { Nucleo.zerar(); campo = "" })
                }
                if (n > 1) Text("Mude o valor no jogo (ganhe ou gaste), digite o novo e refine.", color = Cor.apagado, fontSize = 12.sp)
            }
            if (Nucleo.achados.size in 2..50) Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Nucleo.achados.take(20).forEach { a ->
                    Linha(Modifier.clickable { Nucleo.escolhido = a; tela = "editar" }) {
                        Column(Modifier.weight(1f)) { Text(a.valor, color = Cor.texto); Text("0x${a.addr}", color = Cor.apagado, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                        Text(NOME_TIPO[a.tipo] ?: a.tipo, color = Cor.a2, fontSize = 12.sp)
                    }
                }
            }
            if (Nucleo.escolhido != null && Nucleo.total == 1L) Botao("Editar este valor", false, Modifier.fillMaxWidth()) { tela = "editar" }
            Spacer(Modifier.weight(1f))
            Teclado(if (Nucleo.total == null) "Buscar" else "Refinar", campo.isNotBlank() && !Nucleo.ocupado,
                digitou = { campo += it }, apagou = { campo = campo.dropLast(1) }, confirmou = { Nucleo.buscar(campo); campo = "" })
        }
    }

    @Composable
    private fun Editar() {
        val e = Nucleo.escolhido ?: run { tela = "inicio"; return }
        var campo by remember(e.addr) { mutableStateOf("") }
        var congelar by remember(e.addr) { mutableStateOf(Nucleo.salvos.any { it.addr == e.addr && it.congelado }) }
        val atual = Nucleo.salvos.firstOrNull { it.addr == e.addr }?.valor ?: e.valor
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("agora: $atual · ${NOME_TIPO[e.tipo]}", color = Cor.apagado, fontSize = 13.sp)
            CampoGrande(campo, "valor novo")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val n = atual.replace(',', '.').toDoubleOrNull() ?: 0.0
                fun num(d: Double) = if (e.tipo.startsWith("i")) d.toLong().toString() else d.toString()
                listOf("999" to "999", "9.999" to "9999", "+100" to num(n + 100), "×2" to num(n * 2)).forEach { (t, v) -> Selo(t, campo == v) { campo = v } }
            }
            Row(Modifier.clickable { congelar = !congelar }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(40.dp, 22.dp).background(if (congelar) Cor.a1 else Cor.bloco, RoundedCornerShape(50)).border(1.dp, Cor.linha, RoundedCornerShape(50)),
                    contentAlignment = if (congelar) Alignment.CenterEnd else Alignment.CenterStart) { Box(Modifier.padding(3.dp).size(16.dp).background(Color.White, CircleShape)) }
                Text("Congelar neste valor", color = Cor.texto, fontSize = 14.sp)
            }
            Spacer(Modifier.weight(1f))
            Teclado("Aplicar", (campo.isNotBlank() || congelar) && !Nucleo.ocupado, digitou = { campo += it }, apagou = { campo = campo.dropLast(1) },
                confirmou = { Nucleo.editar(e.addr, e.tipo, campo.ifBlank { atual }, congelar); campo = ""; tela = "inicio" })
        }
    }

    @Composable
    private fun Salvos() = Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Nucleo.salvos.forEach { s -> LinhaSalvo(s) }
        Text("Toque num valor para editar; no floco, congela ou solta.", color = Cor.apagado, fontSize = 12.sp)
    }

    private fun primeiroPlano() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Balão do Unit", NotificationManager.IMPORTANCE_LOW))
        val fechar = PendingIntent.getService(this, 0, Intent(this, Balao::class.java).setAction(FECHAR), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CANAL).setSmallIcon(R.drawable.ic_unit).setContentTitle("Unit ativo")
            .setContentText("O balão está sobre o jogo").addAction(Notification.Action.Builder(null, "Fechar", fechar).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
    }

    private companion object {
        const val CANAL = "balao"
        const val FECHAR = "dev.unit.app.FECHAR"
    }
}

/** Compose precisa de um ciclo de vida; um Service não tem, então este fica sempre ativo. */
private class Dono : LifecycleOwner, SavedStateRegistryOwner {
    private val reg = LifecycleRegistry(this)
    private val ctl = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = reg
    override val savedStateRegistry get() = ctl.savedStateRegistry
    fun iniciar() { ctl.performRestore(null); reg.currentState = Lifecycle.State.RESUMED }
    fun parar() { reg.currentState = Lifecycle.State.DESTROYED }
}
