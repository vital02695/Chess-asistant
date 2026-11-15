package com.example.chessassistant

import kotlin.math.abs

/**
 * board[rank][file]: rank 0 = первая горизонталь (белые), file 0 = колонка a. '.' = пусто.
 * grid[row][col] — то, что на экране (row 0 = верх).
 */
object Rules {
    private const val BACK = "RNBQKBNR"

    fun hasUnknown(grid: Array<CharArray>) = grid.any { r -> r.any { it == '?' } }

    fun toBoard(grid: Array<CharArray>, whiteAtBottom: Boolean): Array<CharArray> =
        Array(8) { rank ->
            CharArray(8) { file ->
                val row = if (whiteAtBottom) 7 - rank else rank
                val col = if (whiteAtBottom) file else 7 - file
                grid[row][col]
            }
        }

    fun screenRow(rank: Int, whiteAtBottom: Boolean) = if (whiteAtBottom) 7 - rank else rank
    fun screenCol(file: Int, whiteAtBottom: Boolean) = if (whiteAtBottom) file else 7 - file

    fun isStart(b: Array<CharArray>): Boolean =
        (0..7).all { f ->
            b[0][f] == BACK[f] && b[1][f] == 'P' && b[6][f] == 'p' &&
                b[7][f] == BACK[f].lowercaseChar()
        } && (2..5).all { r -> b[r].all { it == '.' } }

    fun valid(b: Array<CharArray>): Boolean {
        var wk = 0; var bk = 0; var wp = 0; var bp = 0
        for (r in 0..7) for (f in 0..7) {
            when (b[r][f]) {
                'K' -> wk++
                'k' -> bk++
                'P' -> { wp++; if (r == 0 || r == 7) return false }
                'p' -> { bp++; if (r == 0 || r == 7) return false }
            }
        }
        return wk == 1 && bk == 1 && wp <= 8 && bp <= 8
    }

    fun fen(b: Array<CharArray>, whiteToMove: Boolean): String {
        val sb = StringBuilder()
        for (rank in 7 downTo 0) {
            var e = 0
            for (f in 0..7) {
                val ch = b[rank][f]
                if (ch == '.') e++ else {
                    if (e > 0) { sb.append(e); e = 0 }
                    sb.append(ch)
                }
            }
            if (e > 0) sb.append(e)
            if (rank > 0) sb.append('/')
        }
        val c = StringBuilder()
        if (b[0][4] == 'K' && b[0][7] == 'R') c.append('K')
        if (b[0][4] == 'K' && b[0][0] == 'R') c.append('Q')
        if (b[7][4] == 'k' && b[7][7] == 'r') c.append('k')
        if (b[7][4] == 'k' && b[7][0] == 'r') c.append('q')
        sb.append(if (whiteToMove) " w " else " b ")
        sb.append(if (c.isEmpty()) "-" else c.toString())
        sb.append(" - 0 1")
        return sb.toString()
    }

    /** Кто только что сходил: true = белые, false = чёрные, null = непонятно. */
    fun moverOf(prev: Array<CharArray>, cur: Array<CharArray>): Boolean? {
        var white = 0; var black = 0
        for (r in 0..7) for (f in 0..7) {
            val n = cur[r][f]
            if (n != '.' && n != prev[r][f]) { if (n.isUpperCase()) white++ else black++ }
        }
        return when {
            white > 0 && black == 0 -> true
            black > 0 && white == 0 -> false
            else -> null
        }
    }

    fun apply(b: Array<CharArray>, uci: String): Array<CharArray> {
        val n = Array(8) { b[it].clone() }
        val f1 = uci[0] - 'a'; val r1 = uci[1] - '1'
        val f2 = uci[2] - 'a'; val r2 = uci[3] - '1'
        var p = n[r1][f1]
        if (p.lowercaseChar() == 'p' && f1 != f2 && n[r2][f2] == '.') n[r1][f2] = '.' // en passant
        if (p.lowercaseChar() == 'k' && abs(f2 - f1) == 2) {                            // рокировка
            val rf = if (f2 > f1) 7 else 0
            val rt = if (f2 > f1) 5 else 3
            n[r1][rt] = n[r1][rf]; n[r1][rf] = '.'
        }
        if (uci.length >= 5) p = if (p.isUpperCase()) uci[4].uppercaseChar() else uci[4].lowercaseChar()
        n[r2][f2] = p
        n[r1][f1] = '.'
        return n
    }

    /** Короткая запись хода вида Nf3, exd5, O-O (без пометок шаха и без уточнений). */
    fun label(b: Array<CharArray>, uci: String): String {
        val f1 = uci[0] - 'a'; val r1 = uci[1] - '1'
        val f2 = uci[2] - 'a'; val r2 = uci[3] - '1'
        val piece = b[r1][f1]
        val low = piece.lowercaseChar()
        if (low == 'k' && abs(f2 - f1) == 2) return if (f2 > f1) "O-O" else "O-O-O"
        val sq = "${'a' + f2}${r2 + 1}"
        val capture = b[r2][f2] != '.' || (low == 'p' && f1 != f2)
        val promo = if (uci.length >= 5) "=" + uci[4].uppercaseChar() else ""
        return if (low == 'p') (if (capture) "${'a' + f1}x" else "") + sq + promo
        else piece.uppercaseChar().toString() + (if (capture) "x" else "") + sq
    }
}
