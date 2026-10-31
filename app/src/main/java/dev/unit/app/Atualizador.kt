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

/**
 * Ao abrir, pergunta ao GitHub a última versão (alguns KB). Se houver uma nova, baixa o APK uma vez, em segundo plano,
 * e avisa; ela entra quando o Unit for fechado (✕). Instala pelo root (pm install).
 */
object Atualizador {
    private const val API = "https://api.github.com/repos/Sedricko/Unit/releases/latest"
    var nova by mutableStateOf<String?>(null)
    var pronto by mutableStateOf(false)   // o APK novo já está baixado
    var estado by mutableStateOf("")
    private var apk = ""
    private var arquivo: File? = null

    /** "0.10.0" > "0.9.1": compara número por número. */
    fun maior(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }; val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d > 0 }
        return false
    }

    /** Vê se há versão nova e, se houver, já deixa o APK baixado (só uma vez por versão). */
    suspend fun preparar(ctx: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val atual = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: return@runCatching
            val j = JSONObject(URL(API).readText())
            val v = j.getString("tag_name").removePrefix("v")
            val a = j.getJSONArray("assets")
            apk = (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.getString("name").endsWith(".apk") }?.getString("browser_download_url") ?: return@runCatching
            if (!maior(v, atual)) return@runCatching
            nova = v
            val f = File(ctx.cacheDir, "unit-$v.apk")
            if (!f.exists() || f.length() == 0L) {
                estado = "Baixando em segundo plano…"
                val tmp = File(ctx.cacheDir, "unit-$v.part")
                URL(apk).openStream().use { i -> tmp.outputStream().use { i.copyTo(it) } }
                if (!tmp.renameTo(f)) throw IOException("não consegui guardar o APK")
            }
            arquivo = f; pronto = true; estado = ""
        }.onFailure { if (nova != null) estado = "Não deu para baixar: ${it.message}" }
    }

    /** O Unit foi fechado (✕) com uma versão pronta: instala sem esperar (o su segue sozinho). */
    fun instalarAoFechar() { val f = arquivo ?: return; if (pronto) Thread { runCatching { pm(f) } }.start() }

    // o pm não lê a pasta privada do app: passa pelo /data/local/tmp
    private fun pm(f: File) {
        val p = ProcessBuilder("su", "-c", "cat '${f.absolutePath}' > /data/local/tmp/unit-novo.apk && pm install -r /data/local/tmp/unit-novo.apk; rm -f /data/local/tmp/unit-novo.apk")
            .redirectErrorStream(true).start()
        val saida = p.inputStream.bufferedReader().readText()
        if (p.waitFor() != 0 || "Success" !in saida) throw IOException(saida.trim().ifEmpty { "o pm install recusou" })
    }
}
