package dev.unit.app

import java.io.IOException

/** Conversa com o unitd (motor em C) rodando como root pelo su. Cada comando devolve as linhas antes do "ok"/"err". */
class Motor(caminho: String) {
    private val proc = try { ProcessBuilder("su").redirectErrorStream(true).start() }
        catch (e: IOException) { throw IOException("Este Android não tem root (não existe o su). Instale o Unit dentro do vPhoneOS, como o Tinker.") }
    private val entrada = proc.outputStream.bufferedWriter()
    private val saida = proc.inputStream.bufferedReader()
    val ola: String

    init {
        entrada.write("exec $caminho\n")
        entrada.flush()
        ola = saida.readLine() ?: throw IOException("Sem root: o su recusou ou não existe.")
        if (!ola.startsWith("ok unitd")) throw IOException("O motor não respondeu: $ola")
    }

    class Resposta(val linhas: List<String>, val status: String)

    @Synchronized
    fun cmd(comando: String): Resposta {
        val t0 = System.nanoTime()
        // uma quebra de linha dentro do comando viraria outro comando do motor (root): recusa
        require('\n' !in comando && '\r' !in comando) { "comando inválido" }
        try {
            entrada.write(comando + "\n")
            entrada.flush()
            val linhas = mutableListOf<String>()
            while (true) {
                val l = saida.readLine() ?: throw IOException("O motor fechou.")
                if (l.startsWith("err ")) throw IOException(l.removePrefix("err "))
                if (l == "ok" || l.startsWith("ok ")) return Resposta(linhas, l.removePrefix("ok").trim())
                    .also { Registro.comando(comando, "ok ${it.status}".trim() + if (linhas.isEmpty()) "" else " (+${linhas.size} linhas)", (System.nanoTime() - t0) / 1_000_000, false) }
                linhas += l
            }
        } catch (e: IOException) { Registro.comando(comando, "err ${e.message}", (System.nanoTime() - t0) / 1_000_000, true); throw e }
    }

    fun fechar() = proc.destroy()
}
