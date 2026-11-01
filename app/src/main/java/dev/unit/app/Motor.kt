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
            // o su espera o "Permitir" do gerenciador de root; sem resposta em 20 s, desiste em vez de travar a lista
            var ola: String? = null
            val t = Thread { ola = runCatching { r.readLine() }.getOrNull() }.apply { start(); join(20_000) }
            if (t.isAlive) { semRoot = "O pedido de root não foi respondido em 20 s (abra o Magisk/su e permita o Unit)."; throw IOException(semRoot) }
            if (ola?.startsWith("ok unitd") != true) throw IOException("O motor não respondeu: ${ola ?: "fechou"}")
            return Aberto(p, r)
        } catch (e: Exception) { p.destroy(); throw e }
    }

    /** Por que ficou sem root (para o aviso da lista vazia); null = sem su ou recusado. */
    var semRoot: String? = null
        private set

    // o erro exato do su vai para o aviso: sem ele não dá para saber se o su não existe, recusou ou o motor falhou
    private val su = runCatching { abrir(ProcessBuilder("su"), "exec $caminho") }.onFailure { if (semRoot == null) semRoot = "su: ${it.message}" }.getOrNull()
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
