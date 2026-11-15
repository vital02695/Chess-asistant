package com.example.chessassistant

import android.graphics.Color
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

data class Analysis(val arrows: List<Arrow>, val whiteCp: Int?, val whiteMate: Int?, val text: String)

class Analyzer(private val engine: StockfishEngine) {

    private val executor = Executors.newSingleThreadExecutor()
    private val gen = AtomicInteger(0)
    private val green = Color.argb(215, 30, 160, 70)

    fun cancel() { gen.incrementAndGet(); engine.stop() }

    fun shutdown() {
        gen.incrementAndGet()
        executor.shutdownNow()
        engine.destroy()
    }

    fun request(
        board: Array<CharArray>, fen: String, whiteToMove: Boolean, myWhite: Boolean,
        whiteAtBottom: Boolean, temperature: Int,
        onResult: (Analysis) -> Unit, onStatus: (String) -> Unit
    ) {
        val my = gen.incrementAndGet()
        engine.stop()
        executor.execute {
            if (my != gen.get()) return@execute
            try {
                if (!engine.ready && !engine.start()) {
                    onStatus("Stockfish не найден. Положите libstockfish.so в app/src/main/jniLibs/arm64-v8a/ и пересоберите (см. README).")
                    return@execute
                }
                analyse(my, board, fen, whiteToMove, myWhite, whiteAtBottom, temperature, onResult)
            } catch (e: Exception) {
                if (my == gen.get()) onStatus("Ошибка движка: ${e.message}")
            }
        }
    }

    private fun analyse(
        my: Int, board: Array<CharArray>, fen: String, whiteToMove: Boolean, myWhite: Boolean,
        wab: Boolean, temp: Int, onResult: (Analysis) -> Unit
    ) {
        val myTurn = whiteToMove == myWhite
        val first = engine.search(fen, emptyList(), if (myTurn) 1 else 3, 1200)
        if (my != gen.get()) return
        if (first.isEmpty()) {
            onResult(Analysis(emptyList(), null, null, "Ходов нет (мат или пат)"))
            return
        }
        val top = first[0]
        val sign = if (whiteToMove) 1 else -1
        val whiteCp = if (top.mate == null) (top.cp ?: 0) * sign else null
        val whiteMate = top.mate?.let { it * sign }
        val evalText = evalText(whiteCp, whiteMate)
        val arrows = ArrayList<Arrow>()
        val text: String

        if (myTurn) {
            val lbl = Rules.label(board, top.move)
            arrows += arrow(top.move, wab, green, 1.5f, lbl)
            val sb = StringBuilder("Лучший ход: $lbl   [$evalText]")
            if (top.mate == 1) {
                sb.append("\nЭто мат!")
            } else {
                val after = Rules.apply(board, top.move)
                val reply = engine.search(fen, listOf(top.move), 3, 700)
                if (my != gen.get()) return
                if (reply.isNotEmpty()) {
                    val p = probs(reply, temp)
                    arrows += opponentArrows(reply, p, after, wab)
                    sb.append("\nОтвет соперника (прогноз): ")
                    sb.append(reply.indices.joinToString(", ") {
                        Rules.label(after, reply[it].move) + " " + pct(p[it])
                    })
                }
            }
            text = sb.toString()
        } else {
            val p = probs(first, temp)
            arrows += opponentArrows(first, p, board, wab)
            val sb = StringBuilder("Ход соперника (прогноз): ")
            sb.append(first.indices.joinToString(", ") { Rules.label(board, first[it].move) + " " + pct(p[it]) })
            val likely = first[0]
            val after = Rules.apply(board, likely.move)
            val rep = engine.search(fen, listOf(likely.move), 1, 800)
            if (my != gen.get()) return
            if (rep.isNotEmpty()) {
                val rl = Rules.label(after, rep[0].move)
                arrows += arrow(rep[0].move, wab, green, 1.2f, rl)
                sb.append("\nЕсли ${Rules.label(board, likely.move)} — ваш ответ: $rl")
            }
            sb.append("   [$evalText]")
            text = sb.toString()
        }
        onResult(Analysis(arrows, whiteCp, whiteMate, text))
    }

    private fun pct(p: Double) = "${(p * 100).roundToInt()}%"

    private fun probs(lines: List<StockfishEngine.Line>, temp: Int): List<Double> {
        val mx = lines.maxOf { it.score }
        val w = lines.map { exp((it.score - mx).toDouble() / temp) }
        val s = w.sum()
        return w.map { it / s }
    }

    private fun opponentArrows(
        lines: List<StockfishEngine.Line>, p: List<Double>, b: Array<CharArray>, wab: Boolean
    ): List<Arrow> = lines.indices.map { i ->
        val alpha = (90 + 150 * p[i]).toInt()
        arrow(
            lines[i].move, wab, Color.argb(alpha, 211, 47, 47), (0.55 + 0.9 * p[i]).toFloat(),
            Rules.label(b, lines[i].move) + " " + pct(p[i])
        )
    }

    private fun arrow(uci: String, wab: Boolean, color: Int, scale: Float, label: String?) = Arrow(
        Rules.screenRow(uci[1] - '1', wab), Rules.screenCol(uci[0] - 'a', wab),
        Rules.screenRow(uci[3] - '1', wab), Rules.screenCol(uci[2] - 'a', wab),
        color, scale, label
    )

    private fun evalText(cp: Int?, mate: Int?): String = when {
        mate != null -> "мат в ${abs(mate)} за " + if (mate > 0) "белых" else "чёрных"
        cp != null -> "%+.1f".format(java.util.Locale.US, cp / 100.0)
        else -> "?"
    }
}
