package dev.unit.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A tela do app: escolher o jogo e ligar o balão. O resto (achar, editar, congelar) é no balão, sobre o jogo. */
class MainActivity : ComponentActivity() {
    private var podeBalao by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Nucleo.init(this)
        setContent { Tela() }
    }

    override fun onResume() {
        super.onResume()
        podeBalao = Settings.canDrawOverlays(this)
        // voltou para o app com um jogo ligado: o painel vira o balão (e volta, se o Android tinha fechado ele)
        if (podeBalao && Nucleo.jogo != null) balao(Balao.MINIMIZAR)
    }

    private fun balao(acao: String) = runCatching { startForegroundService(Intent(this, Balao::class.java).setAction(acao)) }

    private fun abrirBalao(j: Jogo) {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        startForegroundService(Intent(this, Balao::class.java))
        packageManager.getLaunchIntentForPackage(j.pacote)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(::startActivity)
    }

    @Composable
    private fun Tela() {
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) { Nucleo.carregarJogos(this@MainActivity); Atualizador.verificar(this@MainActivity); if (Atualizador.nova != null) Atualizador.instalar(this@MainActivity) }
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Cor.brilho, Cor.fundo), radius = 1400f)).systemBarsPadding()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Unit", color = Cor.texto, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Selo(Nucleo.jogo?.nome ?: "nenhum jogo", Nucleo.jogo != null)
                }
                if (!podeBalao) Linha(Modifier.clickable { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }) {
                    Column(Modifier.weight(1f)) {
                        Text("Permitir o balão sobre os jogos", color = Cor.texto, fontWeight = FontWeight.SemiBold)
                        Text("Uma vez só: toque aqui e ligue \"Sobrepor a outros apps\" para o Unit.", color = Cor.apagado, fontSize = 12.sp)
                    }
                }
                Atualizador.nova?.let { v ->
                    Linha(Modifier.clickable { scope.launch { Atualizador.instalar(this@MainActivity) } }) {
                        Column(Modifier.weight(1f)) {
                            Text("Versão nova: $v", color = Cor.texto, fontWeight = FontWeight.SemiBold)
                            Text(Atualizador.estado.ifEmpty { "Toque para tentar de novo." }, color = Cor.apagado, fontSize = 12.sp)
                        }
                    }
                }
                if (Balao.ativo) Linha {
                    Column(Modifier.weight(1f)) {
                        Text("Balão sobre o jogo", color = Cor.texto, fontWeight = FontWeight.SemiBold)
                        Text(Nucleo.jogo?.nome ?: "ligado", color = Cor.apagado, fontSize = 12.sp)
                    }
                    BotaoIcone(Ic.EXPANDIR) { balao(Balao.EXPANDIR) }
                    Box(Modifier.padding(start = 8.dp)) { BotaoIcone(Ic.MINIMIZAR) { balao(Balao.MINIMIZAR) } }
                    Box(Modifier.padding(start = 8.dp)) { BotaoIcone(Ic.FECHAR) { balao(Balao.FECHAR) } }
                }
                if (Nucleo.aviso.isNotBlank()) Text(Nucleo.aviso, color = Cor.ruim, fontSize = 13.sp)
                Rotulo("Jogos abertos")
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Nucleo.jogos) { j ->
                        Linha(Modifier.clickable { Nucleo.escolher(j); abrirBalao(j) }) {
                            IconeJogo(j)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(j.nome, color = Cor.texto, fontWeight = FontWeight.SemiBold)
                                Text(j.pacote, color = Cor.apagado, fontSize = 12.sp)
                            }
                            if (Nucleo.jogo?.pacote == j.pacote) Selo("escolhido", true)
                        }
                    }
                }
                Text("Toque no jogo: o Unit se liga a ele, abre o jogo e o balão aparece por cima.", color = Cor.apagado, fontSize = 12.sp)
                Botao("Atualizar a lista", false, Modifier.fillMaxWidth()) { Nucleo.carregarJogos(this@MainActivity) }
            }
        }
    }
}
