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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
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
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
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
    private var my = 0                          // altura do mini painel, contada de baixo (arrastável)
    private var px = -1; private var py = -1    // onde o painel foi arrastado (-1: ainda no lugar de começo)
    private var cheio by mutableStateOf(false)   // painel em tela cheia (botão expandir)
    private var espiar by mutableStateOf(false)
    private var confirmar by mutableStateOf(false)  // tocou no ✕: pergunta antes de fechar  // segurando o olho: o painel some para ver o jogo

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
        ativo = true
        lembrar()
        Arrumar.carregar(prefs)
        posicionar()
        // o escudo entra antes do painel: fica embaixo dele e por cima do jogo
        val esc = android.view.View(this).apply {
            setOnTouchListener { _, e -> if (e.action == android.view.MotionEvent.ACTION_DOWN) soltarPausa(); true }
            isFocusable = true; isFocusableInTouchMode = true
            setOnKeyListener { _, _, e -> if (e.action == android.view.KeyEvent.ACTION_UP) soltarPausa(); true }
        }
        escudo = esc
        wm.addView(esc, pEscudo)
        wm.addView(v, params)
    }

    /**
     * Escudo: com o jogo pausado, uma camada transparente sobre o jogo segura os toques e o "voltar". O jogo congelado não
     * recebe nada, então o Android não mostra "não está respondendo" (com o botão de fechar o jogo, fácil de tocar sem
     * querer). Tocar no escudo continua o jogo.
     */
    private var escudo: android.view.View? = null
    private val pEscudo = LayoutParams(1, 1, LayoutParams.TYPE_APPLICATION_OVERLAY,
        LayoutParams.FLAG_NOT_TOUCHABLE or LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT
    ).apply { alpha = 0f; width = 1; height = 1; gravity = Gravity.TOP or Gravity.START }
    private fun ligarEscudo(on: Boolean) {
        pEscudo.flags = if (on) LayoutParams.FLAG_LAYOUT_IN_SCREEN else LayoutParams.FLAG_NOT_TOUCHABLE or LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN
        // desligado: 1 pixel no canto. Do Android 12 em diante uma janela de outro app cobrindo a tela bloqueia o toque no jogo
        // mesmo "não tocável" (proteção contra toque enganado); só cobre tudo quando precisa segurar o toque (jogo pausado)
        pEscudo.width = if (on) LayoutParams.MATCH_PARENT else 1; pEscudo.height = if (on) LayoutParams.MATCH_PARENT else 1
        pEscudo.gravity = Gravity.TOP or Gravity.START
        escudo?.let { runCatching { wm.updateViewLayout(it, pEscudo) } }
    }
    private fun soltarPausa() {
        if (!Nucleo.pausado) return
        pausaAuto = false; Nucleo.pausar()
        android.widget.Toast.makeText(this, "Jogo continuou", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Pausa automática (Opções): nunca | digitar (Achar e Editar) | painel (painel aberto). Nunca desfaz uma pausa sua. */
    private var pausaAuto by mutableStateOf(false)
    private var opcaoPausa by mutableStateOf("nunca")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            FECHAR -> stopSelf()
            MINIMIZAR -> if (modo != "balao") mudar("balao", lembrar = false)  // voltou ao app: não esquece como estava
            EXPANDIR -> mudar("painel")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { ativo = false; Nucleo.soltarAoFechar(); Atualizador.instalarAoFechar(); escudo?.let { runCatching { wm.removeView(it) } }; vista?.let(wm::removeView); dono.parar(); super.onDestroy() }

    override fun onConfigurationChanged(c: Configuration) { super.onConfigurationChanged(c); posicionar(); vista?.let { wm.updateViewLayout(it, params) } }

    private fun tela(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
        else resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    /** Em pé o painel sobe de baixo (62%); deitado, fica na direita (45%). O mini é uma faixa embaixo. */
    private fun posicionar() {
        val (w, h) = tela()
        when (modo) {
            "balao" -> { params.width = LayoutParams.WRAP_CONTENT; params.height = LayoutParams.WRAP_CONTENT; params.gravity = Gravity.TOP or Gravity.START; params.x = bx; params.y = by }
            "mini" -> { my = my.coerceIn(0, (h * .8f).toInt()); params.width = LayoutParams.MATCH_PARENT; params.height = LayoutParams.WRAP_CONTENT; params.gravity = Gravity.BOTTOM; params.x = 0; params.y = my }
            else -> if (cheio) { params.width = LayoutParams.MATCH_PARENT; params.height = LayoutParams.MATCH_PARENT; params.gravity = Gravity.TOP or Gravity.START; params.x = 0; params.y = 0 }
            else {
                // janela solta: começa embaixo (em pé) ou na direita (deitado) e vai para onde for arrastada pela barra de cima
                params.width = if (w > h) (w * .45f).toInt() else (w * .94f).toInt()
                params.height = if (w > h) (h * .9f).toInt() else (h * .62f).toInt()
                params.gravity = Gravity.TOP or Gravity.START
                if (px < 0) { px = if (w > h) w - params.width - w / 40 else (w - params.width) / 2; py = h - params.height - h / 40 }
                px = px.coerceIn(0, (w - params.width).coerceAtLeast(0)); py = py.coerceIn(0, (h - params.height).coerceAtLeast(0))
                params.x = px; params.y = py
            }
        }
    }

    private fun arrastar(dx: Float, dy: Float) { px += dx.toInt(); py += dy.toInt(); posicionar(); vista?.let { wm.updateViewLayout(it, params) }; guardar() }

    /** Como o usuário deixou (tamanho, tela cheia, lugar do painel e do balão) fica guardado para a próxima vez. */
    private val prefs by lazy { getSharedPreferences("balao", MODE_PRIVATE) }
    private fun lembrar() {
        modo = (prefs.getString("modo", "balao") ?: "balao").let { if (it == "mini" && Nucleo.salvos.isEmpty()) "balao" else it }; cheio = prefs.getBoolean("cheio", false)
        opcaoPausa = prefs.getString("pausa", "nunca") ?: "nunca"
        px = prefs.getInt("px", -1); py = prefs.getInt("py", -1); my = prefs.getInt("my", 0); bx = prefs.getInt("bx", bx); by = prefs.getInt("by", by)
    }
    private fun guardar() = prefs.edit().putString("modo", modo).putBoolean("cheio", cheio)
        .putInt("px", px).putInt("py", py).putInt("my", my).putInt("bx", bx).putInt("by", by).apply()

    /** O olho: enquanto o dedo segura, o painel fica invisível (como no GameGuardian). */
    @Composable
    private fun Olho() = Box(
        Modifier.size(40.dp).background(Cor.bloco, RoundedCornerShape(12.dp)).border(1.dp, Cor.linha, RoundedCornerShape(12.dp))
            .pointerInput(Unit) { detectTapGestures(onPress = { vibrar(this@Balao); espiar = true; tryAwaitRelease(); espiar = false }) },
        contentAlignment = Alignment.Center
    ) { Icone(Ic.OLHO, 18.dp) }

    /** Um botão de controle. Normal: toca e faz; segurando entra no modo arrumar (o olho: segurar é espiar). Arrumando: arrasta. */
    @Composable
    private fun Controle(id: String) {
        val passo = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx() }
        var pos by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        var tam by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        var arr by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        val icone = when (id) { "olho" -> Ic.OLHO; "pausa" -> if (Nucleo.pausado) Ic.CONTINUAR else Ic.PAUSAR; "expandir" -> Ic.EXPANDIR; "minimizar" -> Ic.MINIMIZAR; else -> Ic.FECHAR }
        fun acao() {
            when (id) {
                "pausa" -> { pausaAuto = false; Nucleo.pausar() }
                "expandir" -> { cheio = !cheio; mudar("painel") }
                "minimizar" -> mudar(if (Nucleo.salvos.isEmpty()) "balao" else "mini")
                else -> confirmar = true
            }
        }
        val giro = if (Arrumar.editando) androidx.compose.animation.core.rememberInfiniteTransition(label = "tremer").animateFloat(initialValue = -2.5f, targetValue = 2.5f, animationSpec =
            androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(110), androidx.compose.animation.core.RepeatMode.Reverse), label = "giro").value else 0f
        val gesto = when {
            Arrumar.editando -> Modifier.pointerInput(id, true) {
                detectDragGestures(onDragEnd = { Arrumar.soltar(id, pos + androidx.compose.ui.geometry.Offset(tam.width / 2f, tam.height / 2f) + arr, passo, tam.width.toFloat()); arr = androidx.compose.ui.geometry.Offset.Zero },
                    onDragCancel = { arr = androidx.compose.ui.geometry.Offset.Zero }) { ch, d -> ch.consume(); arr += d }
            }
            id == "olho" -> Modifier.pointerInput(id, false) { detectTapGestures(onPress = { vibrar(this@Balao); espiar = true; tryAwaitRelease(); espiar = false }) }
            else -> Modifier.pointerInput(id, false) { detectTapGestures(onTap = { vibrar(this@Balao); acao() }, onLongPress = { vibrar(this@Balao); Arrumar.editando = true }) }
        }
        Box(Modifier.onGloballyPositioned { pos = it.positionInRoot(); tam = it.size }
            .graphicsLayer { translationX = arr.x; translationY = arr.y; rotationZ = giro }
            .size(40.dp).background(Cor.bloco, RoundedCornerShape(12.dp)).border(1.dp, if (Arrumar.editando) Cor.a2 else Cor.linha, RoundedCornerShape(12.dp))
            .then(gesto), contentAlignment = Alignment.Center) {
            Icone(icone, 18.dp)
            if (id == "pausa" && pausaAuto && Nucleo.pausado) Text("A", color = Cor.a2, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 4.dp))
        }
    }

    /** A barra de baixo (perto do teclado): os botões que a pessoa trouxe para cá; arrumando, aparece vazia com o "Pronto". */
    @Composable
    private fun BarraBaixo() {
        if (Arrumar.baixo.isEmpty() && !Arrumar.editando) return
        Row(Modifier.fillMaxWidth().onGloballyPositioned { Arrumar.areaBaixo = it.boundsInRoot() }
            .then(if (Arrumar.editando) Modifier.background(Color(0x1F3B8CFF), RoundedCornerShape(14.dp)).border(1.dp, Color(0x6667C6FF), RoundedCornerShape(14.dp)).padding(4.dp) else Modifier),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Arrumar.baixo.forEach { Controle(it) }
            if (Arrumar.editando) {
                if (Arrumar.baixo.isEmpty()) Text("solte aqui", color = Cor.apagado, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Botao("Pronto", true) { Arrumar.editando = false; Arrumar.guardar(prefs) }
            }
        }
    }

    /** O ⏸: toque é sempre seu (tira o "automático"); o "A" mostra que quem pausou foi a pausa automática. */
    @Composable
    private fun BotaoPausa() = Box {
        BotaoIcone(if (Nucleo.pausado) Ic.CONTINUAR else Ic.PAUSAR) { pausaAuto = false; Nucleo.pausar() }
        if (pausaAuto && Nucleo.pausado) Text("A", color = Cor.a2, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 4.dp))
    }

    /** Opções (a primeira): a pausa automática. */
    @Composable
    private fun Opcoes() = Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Rotulo("Pausa automática")
        listOf(
            Triple("nunca", "Desligada", "Só o ⏸ na mão (como hoje)."),
            Triple("digitar", "Ao digitar", "Pausa no Achar e no Editar; saiu ou minimizou, o jogo volta. Início, Salvos e Em volta ficam ao vivo."),
            Triple("painel", "Ao abrir o painel", "Painel aberto = jogo pausado (como o Autopause do GameGuardian)."),
        ).forEach { (id, nome, txt) ->
            Linha(Modifier.toque { opcaoPausa = id; prefs.edit().putString("pausa", id).apply() }) {
                Column(Modifier.weight(1f)) {
                    Text(nome, color = Cor.texto, fontWeight = FontWeight.SemiBold)
                    Text(txt, color = Cor.apagado, fontSize = 12.sp)
                }
                if (opcaoPausa == id) Selo("escolhida", true)
            }
        }
        Text("Com o jogo pausado, um escudo invisível segura os toques no jogo: assim o Android não mostra \"não está respondendo\". Tocar no jogo continua ele.",
            color = Cor.apagado, fontSize = 12.sp)
    }

    private fun mudar(m: String, lembrar: Boolean = true) { modo = m; posicionar(); vista?.let { wm.updateViewLayout(it, params) }; if (lembrar) guardar() }

    @Composable
    private fun Conteudo() {
        // os salvos ao vivo enquanto o mini ou o painel está à vista
        LaunchedEffect(modo) { while (modo != "balao") { Nucleo.atualizarSalvos(); delay(1000) } }
        LaunchedEffect(Nucleo.pausado) { ligarEscudo(Nucleo.pausado); if (!Nucleo.pausado) pausaAuto = false }
        // pausa automática: entra ao digitar (ou com o painel aberto) e sai ao sair dessas telas / minimizar
        LaunchedEffect(modo, tela, opcaoPausa) {
            if (Nucleo.jogo == null) return@LaunchedEffect
            val quer = when (opcaoPausa) { "digitar" -> modo == "painel" && (tela == "achar" || tela == "editar"); "painel" -> modo == "painel"; else -> false }
            if (quer && !Nucleo.pausado) { Nucleo.pausar(); pausaAuto = true }
            else if (!quer && pausaAuto && Nucleo.pausado) { pausaAuto = false; Nucleo.pausar() }
        }
        when (modo) {
            "balao" -> Box(
                Modifier.size(52.dp).background(Color(0xD90E162C), CircleShape).border(1.dp, Cor.linha, CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures { ch, d ->
                            ch.consume(); bx += d.x.toInt(); by += d.y.toInt(); params.x = bx; params.y = by
                            vista?.let { wm.updateViewLayout(it, params) }; guardar()
                        }
                    }
                    .pointerInput(Unit) { detectTapGestures(onTap = { vibrar(this@Balao); mudar(if (Nucleo.salvos.isEmpty()) "painel" else "mini") }, onLongPress = { mudar("painel") }) },
                contentAlignment = Alignment.Center
            ) { Text("U", color = Cor.a2, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp) }
            "mini" -> Mini()
            else -> Painel()
        }
    }

    @Composable
    private fun Mini() = Column(
        Modifier.fillMaxWidth().alpha(if (espiar) 0f else 1f).padding(10.dp).background(Cor.vidro, RoundedCornerShape(22.dp)).border(1.dp, Cor.linha, RoundedCornerShape(22.dp))
            // arrastar em qualquer parte do mini (os toques nos botões continuam valendo): sobe e desce
            .pointerInput(Unit) { detectDragGestures { ch, d -> ch.consume(); my -= d.y.toInt(); posicionar(); vista?.let { wm.updateViewLayout(it, params) }; guardar() } }
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(40.dp, 4.dp).background(Cor.linha, RoundedCornerShape(2.dp)))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Nucleo.salvos.take(3).forEach { s ->
                Column(Modifier.weight(1f).background(Cor.bloco, RoundedCornerShape(14.dp)).border(1.dp, Cor.linha, RoundedCornerShape(14.dp))
                    .toque { Nucleo.escolhido = Achado(s.addr, s.tipo, s.valor); tela = "editar"; mudar("painel") }.padding(8.dp)) {
                    Text(s.nome + if (s.congelado) " · congelado" else "", color = Cor.apagado, fontSize = 11.sp, maxLines = 1)
                    Text(s.valor, color = if (s.congelado) Cor.a2 else Cor.texto, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Botao("Achar", true) { tela = "achar"; mudar("painel") }
            Spacer(Modifier.weight(1f))
            Olho()
            BotaoPausa()
            BotaoIcone(Ic.EXPANDIR) { mudar("painel") }
            BotaoIcone(Ic.MINIMIZAR) { mudar("balao") }
        }
    }

    private var tela by mutableStateOf("inicio")   // inicio | achar | editar | salvos

    @Composable
    private fun Painel() = Box(Modifier.fillMaxSize().alpha(if (espiar) 0f else 1f).onGloballyPositioned { Arrumar.areaPainel = it.boundsInRoot() }) {
        PainelConteudo()
        // os botões soltos onde a pessoa deixou (arrastados para fora das barras)
        Arrumar.livre.forEach { (id, p) -> Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(p.x.toInt(), p.y.toInt()) }) { Controle(id) } }
    }

    @Composable
    private fun PainelConteudo() = Column(
        Modifier.fillMaxSize().background(Cor.vidro, RoundedCornerShape(28.dp)).border(1.dp, Cor.linha, RoundedCornerShape(28.dp)).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // a barrinha de cima: segure e arraste para mudar o painel de lugar
        Box(Modifier.fillMaxWidth().pointerInput(Unit) { detectDragGestures { ch, d -> ch.consume(); arrastar(d.x, d.y) } }.padding(top = 8.dp, bottom = 2.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(40.dp, 4.dp).background(Cor.linha, RoundedCornerShape(2.dp)))
        }
        Row(Modifier.pointerInput(Unit) { detectDragGestures { ch, d -> ch.consume(); arrastar(d.x, d.y) } }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // em cima só os controles; o nome da tela aparece fora do início, com a seta para voltar
            if (tela != "inicio") Box(Modifier.toque { tela = "inicio" }) { Text("‹", color = Cor.texto, fontSize = 26.sp) }
            Text(if (Arrumar.editando) "Arrumar" else when (tela) { "achar" -> "Achar"; "editar" -> "Editar"; "salvos" -> "Salvos"; "jogos" -> "Jogos"; "nome" -> "Nome"; "opcoes" -> "Opções"; "lab" -> "Laboratório"; "volta" -> "Em volta"; else -> "" },
                color = Cor.texto, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
            // os controles na ordem que a pessoa arrumou (Laboratório: Arrumar.kt)
            Row(Modifier.onGloballyPositioned { Arrumar.areaCima = it.boundsInRoot() }.then(if (Arrumar.editando) Modifier.border(1.dp, Color(0x6667C6FF), RoundedCornerShape(12.dp)).padding(2.dp) else Modifier),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) { Arrumar.cima.forEach { Controle(it) } }
        }
        // o jogo ligado: tocar abre a lista para trocar de processo
        if (tela == "inicio") Linha(Modifier.toque { tela = "jogos" }) {
            Nucleo.jogo?.let { IconeJogo(it, 36.dp) } ?: Icone(Ic.JOGO)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(Nucleo.jogo?.nome ?: "Escolher jogo", color = Cor.texto, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(if (Nucleo.pausado) "jogo pausado" else Nucleo.jogo?.pacote ?: "toque para ver os jogos abertos", color = Cor.apagado, fontSize = 12.sp, maxLines = 1)
            }
            Text("Trocar", color = Cor.a2, fontSize = 13.sp)
        }
        if (confirmar) ConfirmarFechar({ confirmar = false }) { stopSelf() }
        if (tela == "inicio" && Atualizador.pronto) Text("Atualização ${Atualizador.nova} baixada: feche o Unit (✕) para atualizar.", color = Cor.a2, fontSize = 12.sp)
        if (Nucleo.aviso.isNotBlank()) Text(Nucleo.aviso, color = Cor.ruim, fontSize = 13.sp)
        when (tela) {
            "achar" -> Achar()
            "editar" -> Editar()
            "salvos" -> Salvos()
            "jogos" -> Jogos()
            "nome" -> Nome()
            "lab" -> Laboratorio.Tela(this@Balao)
            "volta" -> Nucleo.escolhido?.let { e -> EmVolta.Tela(e) { a -> Nucleo.escolhido = a; tela = "editar" } }
            "opcoes" -> Opcoes()
            else -> Inicio()
        }
        // telas sem o teclado do Unit: a barra de baixo fica no pé do painel
        if (tela != "achar" && tela != "editar") { Spacer(Modifier.weight(1f)); BarraBaixo() }
    }

    @Composable
    private fun Inicio() = Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Bloco(Ic.ACHAR, "Achar", Nucleo.jogo != null, Modifier.weight(1f)) { tela = "achar" }
            Bloco(Ic.EDITAR, "Editar", Nucleo.escolhido != null, Modifier.weight(1f)) { tela = "editar" }
            Bloco(Ic.SALVOS, "Salvos", Nucleo.salvos.isNotEmpty(), Modifier.weight(1f)) { tela = "salvos" }
        }
        // LABORATÓRIO (teste: assinaturas). Some junto com o Laboratorio.kt se não der certo
        if (Nucleo.jogo != null) Linha(Modifier.toque { tela = "lab" }) {
            Column(Modifier.weight(1f)) {
                Text("Laboratório", color = Cor.texto, fontWeight = FontWeight.SemiBold)
                Text("teste: achar os salvos de novo depois de reabrir o jogo", color = Cor.apagado, fontSize = 12.sp)
            }
        }
        if (Nucleo.salvos.isNotEmpty()) {
            Rotulo("Salvos")
            Nucleo.salvos.take(4).forEach { s -> LinhaSalvo(s) }
        } else Text("Toque em Achar e digite um número que o jogo mostra.", color = Cor.apagado, fontSize = 13.sp)
        Linha(Modifier.toque { tela = "opcoes" }) {
            Column(Modifier.weight(1f)) {
                Text("Opções", color = Cor.texto, fontWeight = FontWeight.SemiBold)
                Text("pausa automática: " + mapOf("nunca" to "desligada", "digitar" to "ao digitar", "painel" to "ao abrir o painel")[opcaoPausa], color = Cor.apagado, fontSize = 12.sp)
            }
        }
    }

    @Composable
    private fun LinhaSalvo(s: Salvo) = Linha(Modifier.toque { Nucleo.escolhido = Achado(s.addr, s.tipo, s.valor); tela = "editar" }) {
        Column(Modifier.weight(1f)) {
            Text(s.nome, color = Cor.texto, fontSize = 15.sp)
            Text((NOME_TIPO[s.tipo] ?: s.tipo) + if (s.congelado) " · congelado" else "", color = Cor.apagado, fontSize = 11.sp)
        }
        Text(s.valor, color = if (s.congelado) Cor.a2 else Cor.texto, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.padding(start = 8.dp).toque { Nucleo.alternarCongelado(s) }) { Icone(Ic.CONGELAR, 20.dp, if (s.congelado) Cor.a2 else Cor.apagado) }
    }

    @Composable
    private fun Achar() {
        var campo by remember { mutableStateOf("") }
        ComTeclado({
            Teclado(if (Nucleo.total == null) "Buscar" else "Refinar", campo.isNotBlank() && !Nucleo.ocupado,
                digitou = { if (it != "~" || "~" !in campo.substringAfterLast(';')) campo += it }, apagou = { campo = campo.dropLast(1) }, confirmou = { Nucleo.buscar(campo); campo = "" }, faixa = true)
        }) {
            CampoGrande(campo, if (Nucleo.total == null) "número na tela" else "número novo")
            Nucleo.total?.let { n ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${"%,d".format(n).replace(',', '.')} resultado${if (n == 1L) "" else "s"}", color = Cor.texto, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("começar de novo", color = Cor.a2, fontSize = 13.sp, modifier = Modifier.toque { Nucleo.zerar(); campo = "" })
                }
                if (n > 1) Text("Mude o valor no jogo (ganhe ou gaste), digite o novo e refine.", color = Cor.apagado, fontSize = 12.sp)
            }
            if (Nucleo.achados.size in 1..50) Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Nucleo.achados.take(20).forEach { a ->
                    Linha(Modifier.toque { Nucleo.escolhido = a; tela = "editar" }) {
                        Column(Modifier.weight(1f)) { Text(a.valor, color = Cor.texto); Text("0x${a.addr}", color = Cor.apagado, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(NOME_TIPO[a.tipo] ?: a.tipo, color = Cor.a2, fontSize = 12.sp)
                            if (a.tipo == Nucleo.aprendido) Text("o que você mais usa neste jogo", color = Cor.ok, fontSize = 11.sp)
                        }
                        val ja = Nucleo.salvos.any { it.addr == a.addr }
                        Box(Modifier.padding(start = 10.dp).toque { Nucleo.salvar(a) }) { Icone(if (ja) Ic.SALVO else Ic.SALVAR, 20.dp, if (ja) Cor.a2 else Cor.icone) }
                    }
                }
            }
            if (Nucleo.achados.size in 2..20) Botao("Salvar todos (${Nucleo.achados.size})", false, Modifier.fillMaxWidth()) { Nucleo.achados.forEach(Nucleo::salvar) }
            Nucleo.escolhido?.takeIf { Nucleo.total == 1L }?.let { a ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val ja = Nucleo.salvos.any { it.addr == a.addr }
                    Botao(if (ja) "Salvo" else "Salvar", false, Modifier.weight(1f), ligado = !ja) { Nucleo.salvar(a) }
                    Botao("Editar este valor", true, Modifier.weight(1f)) { tela = "editar" }
                }
            }
        }
    }

    @Composable
    private fun Editar() {
        val e = Nucleo.escolhido ?: run { tela = "inicio"; return }
        var campo by remember(e.addr) { mutableStateOf("") }
        var congelar by remember(e.addr) { mutableStateOf(Nucleo.salvos.any { it.addr == e.addr && it.congelado }) }
        val atual = Nucleo.salvos.firstOrNull { it.addr == e.addr }?.valor ?: e.valor
        val origem = remember(e.addr) { e.tipo }  // o tipo com que o valor foi achado
        val acima = passaLimite(e.tipo, campo)
        var vizinho by remember(e.addr, e.tipo) { mutableStateOf<String?>(null) }
        // trocou para um tipo maior: os 4 bytes de depois (+0x4) também serão escritos; mostra quanto vale agora
        LaunchedEffect(e.addr, e.tipo) {
            vizinho = if (TAMANHO[e.tipo] == 8 && TAMANHO[origem] == 4)
                runCatching { Nucleo.cmd { it.cmd("read ${(e.addr.toLong(16) + 4).toString(16)} i32") }.status }.getOrNull() else null
        }
        ComTeclado({
            Teclado("Aplicar", (campo.isNotBlank() || congelar) && !Nucleo.ocupado && !acima, digitou = { campo += it }, apagou = { campo = campo.dropLast(1) },
                confirmou = { Nucleo.editar(e.addr, e.tipo, campo.ifBlank { atual }, congelar); campo = ""; tela = "inicio" })
        }) {
            Linha(Modifier.toque { tela = "nome" }) {
                Column(Modifier.weight(1f)) {
                    Rotulo("Nome")
                    Text(Nucleo.salvos.firstOrNull { it.addr == e.addr }?.nome ?: "sem nome", color = Cor.texto, fontSize = 17.sp)
                }
                Icone(Ic.EDITAR, 18.dp)
            }
            Rotulo("Ver como")
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                TIPOS.forEach { t -> Selo(NOME_TIPO[t] ?: t, e.tipo == t) { if (t != e.tipo) { congelar = false; Nucleo.trocarTipo(e.addr, t) } } }
            }
            vizinho?.let { v ->
                Aviso(Cor.atencao) {
                    Text("${NOME_TIPO[e.tipo]} escreve 8 bytes: muda também o vizinho em +0x4 (agora: $v)." +
                        if (v == "0") " Vizinho 0 costuma ser seguro." else " Confira no Em volta antes.", color = Cor.texto, fontSize = 12.sp)
                }
            }
            Text("agora: $atual · ${NOME_TIPO[e.tipo]}", color = Cor.apagado, fontSize = 13.sp)
            // LABORATÓRIO (teste): os vizinhos deste valor; sai junto com o EmVolta.kt se não der certo
            Botao("Em volta (100 campos acima e abaixo)", false, Modifier.fillMaxWidth()) { tela = "volta" }
            CampoGrande(campo, "valor novo")
            if (acima) Aviso(Cor.atencao) {
                Text("Passa do limite do ${NOME_TIPO[e.tipo]} (máximo ${"%,d".format(MAX_DWORD).replace(',', '.')}).", color = Cor.texto, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Botao("999.999.999", true, Modifier.weight(1f)) { campo = "999999999" }
                    Botao("Máximo", false, Modifier.weight(1f)) { campo = MAX_DWORD.toString() }
                }
                Text("O máximo pode virar negativo se o jogo somar algo a ele.", color = Cor.apagado, fontSize = 11.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val n = atual.replace(',', '.').toDoubleOrNull() ?: 0.0
                fun num(d: Double) = if (e.tipo.startsWith("i")) d.toLong().toString() else d.toString()
                listOf("999" to "999", "999.999.999" to "999999999", "+100" to num(n + 100), "×2" to num(n * 2)).forEach { (t, v) -> Selo(t, campo == v) { campo = v } }
            }
            Row(Modifier.toque { congelar = !congelar }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(40.dp, 22.dp).background(if (congelar) Cor.a1 else Cor.bloco, RoundedCornerShape(50)).border(1.dp, Cor.linha, RoundedCornerShape(50)),
                    contentAlignment = if (congelar) Alignment.CenterEnd else Alignment.CenterStart) { Box(Modifier.padding(3.dp).size(16.dp).background(Color.White, CircleShape)) }
                Text("Congelar neste valor", color = Cor.texto, fontSize = 14.sp)
            }
        }
    }

    /** Em pé: o conteúdo em cima e o teclado embaixo. Deitado não cabe empilhado: o teclado vai para a direita. */
    @Composable
    private fun ComTeclado(teclado: @Composable () -> Unit, conteudo: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
        val deitado = androidx.compose.ui.platform.LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (deitado) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp), content = conteudo)
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom)) { BarraBaixo(); teclado() }
        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            conteudo()
            Spacer(Modifier.weight(1f))
            BarraBaixo()
            teclado()
        }
    }

    /** Só nesta tela a janela pega o foco: o teclado do Android aparece para digitar o nome; ao sair, volta a não pegar. */
    private fun tecladoDoAndroid(ligar: Boolean, mostrar: Boolean = true) {
        params.flags = if (ligar) params.flags and LayoutParams.FLAG_NOT_FOCUSABLE.inv() else params.flags or LayoutParams.FLAG_NOT_FOCUSABLE
        params.softInputMode = if (ligar && mostrar) LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            else if (ligar) LayoutParams.SOFT_INPUT_STATE_HIDDEN or LayoutParams.SOFT_INPUT_ADJUST_RESIZE else LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        vista?.let { wm.updateViewLayout(it, params) }
    }

    @Composable
    private fun Nome() {
        val e = Nucleo.escolhido ?: run { tela = "inicio"; return }
        var campo by remember(e.addr) { mutableStateOf(Nucleo.salvos.firstOrNull { it.addr == e.addr }?.nome?.takeUnless { it.startsWith("Valor ") } ?: "") }
        val foco = remember { androidx.compose.ui.focus.FocusRequester() }
        androidx.compose.runtime.DisposableEffect(Unit) { tecladoDoAndroid(true); onDispose { tecladoDoAndroid(false) } }
        LaunchedEffect(Unit) { delay(150); runCatching { foco.requestFocus() } }
        fun pronto() { Nucleo.renomear(e, campo); tela = "editar" }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.foundation.text.BasicTextField(
                campo, { campo = it.take(24) }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Cor.texto, fontSize = 20.sp, fontWeight = FontWeight.Bold),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Cor.a2),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { pronto() }),
                modifier = Modifier.fillMaxWidth().focusRequester(foco).background(Color(0x40000000), RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0x9967C6FF), RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 12.dp)
            )
            listOf("Ouro", "Joias", "Cobre", "Ferro", "Vida", "Moedas").chunked(4).forEach { linha ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { linha.forEach { n -> Selo(n, campo == n) { campo = n } } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Botao("Cancelar", false, Modifier.weight(1f)) { tela = "editar" }
                Botao("Salvar nome", true, Modifier.weight(1f), ligado = campo.isNotBlank()) { pronto() }
            }
        }
    }

    @Composable
    private fun Aviso(cor: Color, conteudo: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) = Column(
        Modifier.fillMaxWidth().background(cor.copy(alpha = .13f), RoundedCornerShape(12.dp)).border(1.dp, cor, RoundedCornerShape(12.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = conteudo
    )

    @Composable
    private fun Salvos() = Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Nucleo.salvos.forEach { s -> LinhaSalvo(s) }
        Text("Toque num valor para editar; no floco, congela ou solta.", color = Cor.apagado, fontSize = 12.sp)
    }

    /** Os jogos abertos, dentro do painel: tocar troca o processo (o Unit solta o jogo de antes). */
    @Composable
    private fun Jogos() {
        LaunchedEffect(Unit) { Nucleo.carregarJogos(this@Balao) }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Nucleo.jogos.forEach { j ->
                Linha(Modifier.toque { Nucleo.escolher(j); tela = "inicio" }) {
                    IconeJogo(j, 40.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(j.nome, color = Cor.texto, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(j.pacote, color = Cor.apagado, fontSize = 12.sp, maxLines = 1)
                    }
                    if (Nucleo.jogo?.pacote == j.pacote) Selo("ligado", true)
                }
            }
            Botao("Atualizar a lista", false, Modifier.fillMaxWidth()) { Nucleo.carregarJogos(this@Balao) }
        }
    }

    private fun primeiroPlano() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Balão do Unit", NotificationManager.IMPORTANCE_LOW))
        val fechar = PendingIntent.getService(this, 0, Intent(this, Balao::class.java).setAction(FECHAR), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CANAL).setSmallIcon(R.drawable.ic_unit).setContentTitle("Unit ativo")
            .setContentText("O balão está sobre o jogo").addAction(Notification.Action.Builder(null, "Fechar", fechar).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
    }

    companion object {
        private const val CANAL = "balao"
        const val FECHAR = "dev.unit.app.FECHAR"
        const val MINIMIZAR = "dev.unit.app.MINIMIZAR"
        const val EXPANDIR = "dev.unit.app.EXPANDIR"
        /** O balão está ligado (a tela do app mostra minimizar e fechar). */
        var ativo by mutableStateOf(false)
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
