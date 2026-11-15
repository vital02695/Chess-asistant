package com.example.chessassistant

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File

/** Запускает Stockfish как процесс (бинарник лежит как libstockfish.so) и говорит с ним по UCI. */
class StockfishEngine(private val context: Context) {

    data class Line(val move: String, val cp: Int?, val mate: Int?, val pv: List<String>) {
        /** Оценка в сантипешках с точки зрения стороны, которая ходит. Мат = ±10000. */
        val score: Int
            get() = when {
                mate != null -> if (mate > 0) 10000 - mate * 10 else -10000 - mate * 10
                else -> cp ?: 0
            }
    }

    private var proc: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    @Volatile var ready = false
        private set

    val binary: File get() = File(context.applicationInfo.nativeLibraryDir, "libstockfish.so")
    val present: Boolean get() = binary.exists()

    @Synchronized
    private fun send(cmd: String) {
        val w = writer ?: return
        try { w.write(cmd); w.write("\n"); w.flush() } catch (_: Exception) {}
    }

    private fun waitFor(token: String) {
        val r = reader ?: return
        while (true) {
            val line = r.readLine() ?: return
            if (line.startsWith(token)) return
        }
    }

    fun start(): Boolean {
        if (ready) return true
        if (!present) return false
        return try {
            val p = ProcessBuilder(binary.absolutePath).redirectErrorStream(true).start()
            proc = p
            writer = p.outputStream.bufferedWriter()
            reader = p.inputStream.bufferedReader()
            send("uci"); waitFor("uciok")
            send("setoption name Threads value 2")
            send("setoption name Hash value 64")
            send("isready"); waitFor("readyok")
            ready = true
            true
        } catch (e: Exception) { false }
    }

    fun stop() { if (ready) send("stop") }

    fun destroy() {
        send("quit")
        try { proc?.destroy() } catch (_: Exception) {}
        ready = false
    }

    /** Ищет movetimeMs миллисекунд. moves — ходы (UCI) поверх позиции fen. */
    fun search(fen: String, moves: List<String>, multiPv: Int, movetimeMs: Int): List<Line> {
        val r = reader ?: return emptyList()
        send("setoption name MultiPV value $multiPv")
        send("position fen $fen" + if (moves.isEmpty()) "" else " moves " + moves.joinToString(" "))
        send("go movetime $movetimeMs")
        val best = HashMap<Int, Line>()
        while (true) {
            val line = r.readLine() ?: break
            if (line.startsWith("bestmove")) break
            if (line.startsWith("info") && line.contains(" pv ")) {
                parse(line)?.let { (idx, l) -> best[idx] = l }
            }
        }
        return best.keys.sorted().map { best.getValue(it) }
    }

    private fun parse(line: String): Pair<Int, Line>? {
        val t = line.split(' ')
        var mpv = 1
        var cp: Int? = null
        var mate: Int? = null
        var pvStart = -1
        var i = 0
        while (i < t.size) {
            when (t[i]) {
                "multipv" -> mpv = t.getOrNull(i + 1)?.toIntOrNull() ?: 1
                "score" -> {
                    val k = t.getOrNull(i + 1)
                    val v = t.getOrNull(i + 2)?.toIntOrNull()
                    if (k == "cp") cp = v else if (k == "mate") mate = v
                }
                "lowerbound", "upperbound" -> return null
                "pv" -> { pvStart = i + 1; i = t.size }
            }
            i++
        }
        if (pvStart < 0 || pvStart >= t.size || (cp == null && mate == null)) return null
        val pv = t.subList(pvStart, t.size)
        return mpv to Line(pv[0], cp, mate, pv)
    }
}
