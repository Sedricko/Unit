package dev.unit.app

import java.io.IOException

/**
 * Conversa com o unitd (motor em C). Com root, roda pelo su e enxerga todos os apps. Sem su (dentro de um espaço virtual
 * como o Vcloud), roda direto: só enxerga os processos do mesmo dono - que lá dentro são os do jogo.
 * Cada comando devolve as linhas antes do "ok"/"err".
 */
class Motor(caminho: String) {
    /** false = sem root (motor direto, só vê o próprio espaço). */
    val root: Boolean
    private val proc: Process

    init {
        val su = runCatching { ProcessBuilder("su").redirectErrorStream(true).start() }.getOrNull()
        root = su != null
        proc = su ?: ProcessBuilder(caminho).redirectErrorStream(true).start()
    }

    private val entrada = proc.outputStream.bufferedWriter()
    private val saida = proc.inputStream.bufferedReader()
    val ola: String

    init {
        if (root) { entrada.write("exec $caminho\n"); entrada.flush() }
        ola = saida.readLine() ?: throw IOException(if (root) "Sem root: o su recusou." else "O motor não abriu.")
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
