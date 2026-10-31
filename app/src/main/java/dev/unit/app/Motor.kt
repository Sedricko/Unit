package dev.unit.app

import java.io.IOException

/** Conversa com o unitd (motor em C) rodando como root pelo su. Cada comando devolve as linhas antes do "ok"/"err". */
class Motor(caminho: String) {
    private val proc = ProcessBuilder("su").redirectErrorStream(true).start()
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
        entrada.write(comando + "\n")
        entrada.flush()
        val linhas = mutableListOf<String>()
        while (true) {
            val l = saida.readLine() ?: throw IOException("O motor fechou.")
            if (l.startsWith("err ")) throw IOException(l.removePrefix("err "))
            if (l == "ok" || l.startsWith("ok ")) return Resposta(linhas, l.removePrefix("ok").trim())
            linhas += l
        }
    }

    fun fechar() = proc.destroy()
}
