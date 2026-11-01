package dev.unit.app

import java.io.BufferedReader
import java.io.IOException

/**
 * Conversa com o unitd (motor em C). Híbrido: sempre pede root primeiro (su) e enxerga todos os apps. Sem su, ou com o
 * root recusado, roda o motor direto: só enxerga os processos do mesmo dono (dentro de um espaço virtual, o jogo).
 * Cada comando devolve as linhas antes do "ok"/"err".
 */
class Motor(caminho: String) {
    private class Aberto(val proc: Process, val saida: BufferedReader)

    /** Abre o processo e espera o "ok unitd"; o su recusado fecha sem responder e vira erro. */
    private fun abrir(pb: ProcessBuilder, primeira: String?): Aberto {
        val p = pb.redirectErrorStream(true).start()
        try {
            if (primeira != null) p.outputStream.run { write("$primeira\n".toByteArray()); flush() }
            val r = p.inputStream.bufferedReader()
            val ola = r.readLine()
            if (ola == null || !ola.startsWith("ok unitd")) throw IOException("O motor não respondeu: ${ola ?: "fechou"}")
            return Aberto(p, r)
        } catch (e: Exception) { p.destroy(); throw e }
    }

    private val su = runCatching { abrir(ProcessBuilder("su"), "exec $caminho") }.getOrNull()
    /** false = sem root (motor direto, só vê o próprio espaço). */
    val root = su != null
    private val aberto = su ?: abrir(ProcessBuilder(caminho), null)
    private val entrada = aberto.proc.outputStream.bufferedWriter()

    class Resposta(val linhas: List<String>, val status: String)

    @Synchronized
    fun cmd(comando: String): Resposta {
        entrada.write(comando + "\n")
        entrada.flush()
        val linhas = mutableListOf<String>()
        while (true) {
            val l = aberto.saida.readLine() ?: throw IOException("O motor fechou.")
            if (l.startsWith("err ")) throw IOException(l.removePrefix("err "))
            if (l == "ok" || l.startsWith("ok ")) return Resposta(linhas, l.removePrefix("ok").trim())
            linhas += l
        }
    }

    fun fechar() = aberto.proc.destroy()
}
