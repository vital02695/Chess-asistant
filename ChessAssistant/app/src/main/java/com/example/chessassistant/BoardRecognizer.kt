package com.example.chessassistant

import android.content.Context
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class BoardRect(val x: Float, val y: Float, val size: Float)

/**
 * 1) Находит доску автоматически: берёт самые частые цвета на экране, ищет пару цветов,
 *    которые образуют квадрат 8x8 с шахматным чередованием (любая цветовая тема).
 * 2) Фигуры сравнивает по форме тёмных областей. Эталоны снимаются с начальной позиции
 *    при калибровке, поэтому подходит любой набор фигур с тёмным контуром.
 */
class BoardRecognizer(private val context: Context) {

    companion object {
        const val N = 16
        const val SS = 4
        private const val OCC_MASS = 0.02f   // меньше — клетка пустая
        private const val DOT_MASS = 0.09f   // небольшое пятно, не похожее на фигуру (подсказка хода)
        private const val MAX_DIST = 0.60f
        private const val TOL = 10
    }

    private class Feat(val v: FloatArray, val mass: Float)
    private class Found(val rect: BoardRect, val score: Int)

    private val templates = HashMap<Char, MutableList<FloatArray>>()
    private val file get() = File(context.filesDir, "templates.txt")
    private var lockedA = -1
    private var lockedB = -1
    private var misses = 0
    @Volatile private var darkTh = 100

    init { load() }

    val hasTemplates get() = templates.isNotEmpty()

    // ---------- поиск доски ----------

    private fun near(p: Int, c: Int, tol: Int): Boolean =
        abs(((p shr 16) and 255) - ((c shr 16) and 255)) <= tol &&
        abs(((p shr 8) and 255) - ((c shr 8) and 255)) <= tol &&
        abs((p and 255) - (c and 255)) <= tol

    private fun lum(c: Int) =
        (((c shr 16) and 255) * 30 + ((c shr 8) and 255) * 59 + (c and 255) * 11) / 100

    private fun span(a: IntArray): IntArray? {
        val mx = a.max()
        if (mx < 40) return null
        val thr = mx * 0.3f
        var lo = -1
        var hi = -1
        for (i in a.indices) if (a[i] > thr) { if (lo < 0) lo = i; hi = i }
        return if (lo < 0) null else intArrayOf(lo, hi)
    }

    /** Точные края вдоль одной оси (полное разрешение). */
    private fun refine(
        px: IntArray, stride: Int, rows: Int, a: Int, b: Int,
        m: IntArray, c: IntArray, horizontal: Boolean
    ): IntArray? {
        val lo = max(0, m[0] - 4)
        val hi = min((if (horizontal) stride else rows) - 1, m[1] + 4)
        if (hi <= lo) return null
        val cnt = IntArray(hi - lo + 1)
        for (p in lo..hi) {
            var q = c[0]
            var n = 0
            while (q <= c[1]) {
                val pix = if (horizontal) px[q * stride + p] else px[p * stride + q]
                if (near(pix, a, TOL) || near(pix, b, TOL)) n++
                q += 2
            }
            cnt[p - lo] = n
        }
        val mx = cnt.max()
        if (mx < 20) return null
        val thr = mx * 0.3f
        var first = -1
        var last = -1
        for (i in cnt.indices) if (cnt[i] > thr) { if (first < 0) first = i; last = i }
        return if (first < 0) null else intArrayOf(lo + first, lo + last)
    }

    private fun findBoard(px: IntArray, stride: Int, rows: Int, a: Int, b: Int): Found? {
        val cols = IntArray(stride)
        val rws = IntArray(rows)
        var y = 0
        while (y < rows) {
            val base = y * stride
            var x = 0
            while (x < stride) {
                val p = px[base + x]
                if (near(p, a, TOL) || near(p, b, TOL)) { cols[x]++; rws[y]++ }
                x += 2
            }
            y += 2
        }
        val sx = span(cols) ?: return null
        val sy = span(rws) ?: return null
        val ex = refine(px, stride, rows, a, b, sx, sy, true) ?: return null
        val ey = refine(px, stride, rows, a, b, sy, sx, false) ?: return null
        val w = ex[1] - ex[0] + 1
        val h = ey[1] - ey[0] + 1
        if (w < 200 || h < 200 || abs(w - h) > 0.06f * max(w, h)) return null
        val size = (w + h) / 2f
        val cell = size / 8f

        // проверка шахматного рисунка 8x8
        var okA = 0
        var okB = 0
        for (r in 0..7) for (c in 0..7) {
            var ha = 0
            var hb = 0
            for (k in 0 until 4) {
                val fx = if (k % 2 == 0) .12f else .88f
                val fy = if (k < 2) .12f else .88f
                val xx = (ex[0] + (c + fx) * cell).toInt().coerceIn(0, stride - 1)
                val yy = (ey[0] + (r + fy) * cell).toInt().coerceIn(0, rows - 1)
                val p = px[yy * stride + xx]
                val which = if (near(p, a, TOL)) 0 else if (near(p, b, TOL)) 1 else -1
                val expected = (r + c) % 2
                if (which == expected) ha++ else if (which == 1 - expected) hb++
            }
            if (ha >= 3) okA++
            if (hb >= 3) okB++
        }
        val score = max(okA, okB)
        if (score < 56) return null
        return Found(BoardRect(ex[0].toFloat(), ey[0].toFloat(), size), score)
    }

    fun detect(px: IntArray, stride: Int, rows: Int): BoardRect? {
        if (lockedA >= 0) {
            val f = findBoard(px, stride, rows, lockedA, lockedB)
            if (f != null) { misses = 0; return f.rect }
            if (++misses < 3) return null
            lockedA = -1
            misses = 0
        }
        val counts = HashMap<Int, Int>()
        var total = 0
        var y = 0
        while (y < rows) {
            val base = y * stride
            var x = 0
            while (x < stride) {
                val k = px[base + x] and 0xFFFFFF
                counts[k] = (counts[k] ?: 0) + 1
                total++
                x += 3
            }
            y += 3
        }
        val cand = ArrayList<Int>()
        for (e in counts.entries.sortedByDescending { it.value }.take(14)) {
            if (e.value < total * 0.002) break
            if (cand.none { near(e.key, it, 8) }) cand.add(e.key)
        }
        var best: Found? = null
        var ba = 0
        var bb = 0
        for (i in cand.indices) for (j in i + 1 until cand.size) {
            val f = findBoard(px, stride, rows, cand[i], cand[j]) ?: continue
            if (best == null || f.score > best.score) { best = f; ba = cand[i]; bb = cand[j] }
        }
        val found = best ?: return null
        lockedA = ba
        lockedB = bb
        darkTh = (0.75f * min(lum(ba), lum(bb))).toInt()
        return found.rect
    }

    // ---------- признаки клетки ----------

    private fun feature(px: IntArray, stride: Int, r: BoardRect, row: Int, col: Int): Feat {
        val rows = px.size / stride
        val cell = r.size / 8f
        val x0 = r.x + col * cell
        val y0 = r.y + row * cell
        val th = darkTh
        val raw = FloatArray(N * N)
        var mass = 0f
        for (i in 0 until N) for (j in 0 until N) {
            var c = 0
            for (si in 0 until SS) for (sj in 0 until SS) {
                val fx = 0.06f + 0.88f * (j + (sj + 0.5f) / SS) / N
                val fy = 0.06f + 0.88f * (i + (si + 0.5f) / SS) / N
                val x = (x0 + fx * cell).toInt().coerceIn(0, stride - 1)
                val y = (y0 + fy * cell).toInt().coerceIn(0, rows - 1)
                if (lum(px[y * stride + x]) < th) c++
            }
            val v = c / (SS * SS).toFloat()
            raw[i * N + j] = v
            mass += v
        }
        mass /= (N * N)
        // сглаживание [1 2 1]x[1 2 1]/16 — терпимость к сдвигу на пару пикселей
        val k = floatArrayOf(0.25f, 0.5f, 0.25f)
        val tmp = FloatArray(N * N)
        val out = FloatArray(N * N)
        for (i in 0 until N) for (j in 0 until N) {
            var s = 0f
            for (d in -1..1) { val jj = j + d; if (jj in 0 until N) s += k[d + 1] * raw[i * N + jj] }
            tmp[i * N + j] = s
        }
        for (i in 0 until N) for (j in 0 until N) {
            var s = 0f
            for (d in -1..1) { val ii = i + d; if (ii in 0 until N) s += k[d + 1] * tmp[ii * N + j] }
            out[i * N + j] = s
        }
        return Feat(out, mass)
    }

    private fun dist(a: FloatArray, b: FloatArray): Float {
        var num = 0f
        var den = 0f
        for (i in a.indices) { num += abs(a[i] - b[i]); den += max(a[i], b[i]) }
        return if (den < 1e-6f) 1f else num / den
    }

    private fun classify(f: Feat): Char {
        if (f.mass < OCC_MASS) return '.'
        var best = '?'
        var bd = Float.MAX_VALUE
        for ((ch, list) in templates) for (t in list) {
            val d = dist(f.v, t)
            if (d < bd) { bd = d; best = ch }
        }
        if (bd > MAX_DIST) return if (f.mass < DOT_MASS) '.' else '?'
        return best
    }

    /** grid[row][col] — как на экране. '.' пусто, '?' не распознано. */
    fun readGrid(px: IntArray, stride: Int, r: BoardRect): Array<CharArray> =
        Array(8) { row -> CharArray(8) { col -> classify(feature(px, stride, r, row, col)) } }

    // ---------- калибровка по начальной позиции ----------

    fun calibrate(px: IntArray, stride: Int, r: BoardRect, whiteAtBottom: Boolean): Boolean {
        val fresh = HashMap<Char, MutableList<FloatArray>>()
        for (row in 0..7) for (col in 0..7) {
            val file = if (whiteAtBottom) col else 7 - col
            val rank = if (whiteAtBottom) 7 - row else row
            val ch = when (rank) {
                0 -> "RNBQKBNR"[file]
                1 -> 'P'
                6 -> 'p'
                7 -> "rnbqkbnr"[file]
                else -> continue
            }
            val f = feature(px, stride, r, row, col)
            if (f.mass < OCC_MASS) return false
            val list = fresh.getOrPut(ch) { mutableListOf() }
            if (list.size < 4) list.add(f.v)
        }
        templates.clear()
        templates.putAll(fresh)
        save()
        return true
    }

    private fun save() {
        val sb = StringBuilder()
        for ((ch, list) in templates) for (t in list) {
            sb.append(ch).append(' ').append(t.joinToString(",") { it.toString() }).append('\n')
        }
        file.writeText(sb.toString())
    }

    private fun load() {
        try {
            if (!file.exists()) return
            file.forEachLine { line ->
                if (line.length > 2) {
                    val v = line.substring(2).split(",").map { it.toFloat() }.toFloatArray()
                    if (v.size == N * N) templates.getOrPut(line[0]) { mutableListOf() }.add(v)
                }
            }
        } catch (_: Exception) { templates.clear() }
    }
}
