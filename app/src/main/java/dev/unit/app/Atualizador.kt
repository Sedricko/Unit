package dev.unit.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL

/** Ao abrir, vê a última versão publicada no GitHub; com root, baixa e instala sozinho (pm install). */
object Atualizador {
    private const val API = "https://api.github.com/repos/Sedricko/Unit/releases/latest"
    var nova by mutableStateOf<String?>(null)
    var estado by mutableStateOf("")
    private var apk = ""

    /** "0.10.0" > "0.9.1": compara número por número. */
    fun maior(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }; val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d > 0 }
        return false
    }

    suspend fun verificar(ctx: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val atual = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: return@runCatching
            val j = JSONObject(URL(API).readText())
            val v = j.getString("tag_name").removePrefix("v")
            val a = j.getJSONArray("assets")
            apk = (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.getString("name").endsWith(".apk") }?.getString("browser_download_url") ?: return@runCatching
            if (maior(v, atual)) nova = v
        }
    }

    /** Baixa o APK e instala pelo root; o Android fecha o Unit no fim e a versão nova abre pelo ícone. */
    suspend fun instalar(ctx: Context) = withContext(Dispatchers.IO) {
        try {
            estado = "Baixando a versão ${nova}…"
            val f = File(ctx.cacheDir, "unit-novo.apk")
            URL(apk).openStream().use { i -> f.outputStream().use { i.copyTo(it) } }
            estado = "Instalando…"
            // o pm não lê a pasta privada do app: passa pelo /data/local/tmp
            val p = ProcessBuilder("su", "-c", "cat '${f.absolutePath}' > /data/local/tmp/unit-novo.apk && pm install -r /data/local/tmp/unit-novo.apk; rm -f /data/local/tmp/unit-novo.apk")
                .redirectErrorStream(true).start()
            val saida = p.inputStream.bufferedReader().readText()
            if (p.waitFor() != 0 || "Success" !in saida) throw IOException(saida.trim().ifEmpty { "o pm install recusou" })
            estado = "Atualizado. Abra o Unit de novo."
        } catch (e: Exception) {
            estado = "Não deu para atualizar: ${e.message}"
        }
    }
}
