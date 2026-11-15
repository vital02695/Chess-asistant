package com.example.chessassistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

/** Стрелка между клетками (row/col — как на экране). */
data class Arrow(
    val fromRow: Int, val fromCol: Int, val toRow: Int, val toCol: Int,
    val color: Int, val widthScale: Float = 1f, val label: String? = null
)

class OverlayView(ctx: Context) : View(ctx) {

    @Volatile var rect: BoardRect? = null
    @Volatile var grid: Array<CharArray>? = null
    @Volatile var arrows: List<Arrow> = emptyList()
    @Volatile var evalWhiteCp: Int? = null
    @Volatile var mateWhite: Int? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val loc = IntArray(2)

    fun hasContent(): Boolean =
        rect != null && (arrows.isNotEmpty() || grid != null || evalWhiteCp != null || mateWhite != null)

    override fun onDraw(c: Canvas) {
        // выравниваем координаты окна с координатами экрана (вырез, статус-бар)
        getLocationOnScreen(loc)
        c.translate(-loc[0].toFloat(), -loc[1].toFloat())
        val r = rect ?: return
        val cell = r.size / 8f

        drawEvalBar(c, r)

        val g = grid
        if (g != null) {
            p.textSize = cell * 0.26f
            p.textAlign = Paint.Align.CENTER
            for (row in 0..7) for (col in 0..7) {
                val ch = g[row][col]
                if (ch == '.') continue
                val cx = r.x + col * cell + cell * 0.2f
                val cy = r.y + row * cell + cell * 0.8f
                p.style = Paint.Style.FILL
                p.color = when {
                    ch == '?' -> 0xCCF9A825.toInt()
                    ch.isUpperCase() -> 0xCC2E7D32.toInt()
                    else -> 0xCCC62828.toInt()
                }
                c.drawCircle(cx, cy, cell * 0.16f, p)
                p.color = Color.WHITE
                c.drawText(ch.uppercaseChar().toString(), cx, cy + p.textSize * 0.35f, p)
            }
        }
        // сначала красные (чем вероятнее, тем выше), зелёные поверх
        for (a in arrows.sortedBy { if (Color.green(it.color) > Color.red(it.color)) 1 else 0 }) drawArrow(c, r, a)
    }

    private fun winPct(cp: Float) = 50f + 50f * (2f / (1f + exp(-0.00368208f * cp)) - 1f)

    private fun drawEvalBar(c: Canvas, r: BoardRect) {
        val cp = evalWhiteCp
        val mate = mateWhite
        if (cp == null && mate == null) return
        val pct = if (mate != null) (if (mate > 0) 100f else 0f) else winPct(cp!!.toFloat())
        val bh = 18f
        val y = when {
            r.y >= bh + 6 -> r.y - bh - 4
            height - (r.y + r.size) >= bh + 6 -> r.y + r.size + 4
            else -> r.y
        }
        p.style = Paint.Style.FILL
        p.color = 0xE6262626.toInt()
        c.drawRect(r.x, y, r.x + r.size, y + bh, p)
        p.color = 0xE6F2F2F2.toInt()
        c.drawRect(r.x, y, r.x + r.size * pct / 100f, y + bh, p)
        p.color = 0xFFE65100.toInt()
        p.textAlign = Paint.Align.CENTER
        p.textSize = bh * 0.75f
        val t = if (mate != null) "M${abs(mate)}" else "%+.1f".format(java.util.Locale.US, cp!! / 100f)
        c.drawText(t, r.x + r.size / 2, y + bh * 0.8f, p)
    }

    private fun drawArrow(c: Canvas, r: BoardRect, a: Arrow) {
        val cell = r.size / 8f
        val x1 = r.x + (a.fromCol + .5f) * cell
        val y1 = r.y + (a.fromRow + .5f) * cell
        val x2 = r.x + (a.toCol + .5f) * cell
        val y2 = r.y + (a.toRow + .5f) * cell
        val len = hypot(x2 - x1, y2 - y1)
        if (len < 1f) return
        val ux = (x2 - x1) / len
        val uy = (y2 - y1) / len
        val head = cell * 0.42f

        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = cell * 0.13f * a.widthScale
        p.color = a.color
        c.drawLine(x1, y1, x2 - ux * head * 0.6f, y2 - uy * head * 0.6f, p)

        val path = Path().apply {
            moveTo(x2, y2)
            lineTo(x2 - ux * head - uy * head * 0.5f, y2 - uy * head + ux * head * 0.5f)
            lineTo(x2 - ux * head + uy * head * 0.5f, y2 - uy * head - ux * head * 0.5f)
            close()
        }
        p.style = Paint.Style.FILL
        c.drawPath(path, p)

        a.label?.let {
            p.textSize = cell * 0.22f
            p.textAlign = Paint.Align.CENTER
            val tx = x2 - ux * cell * 0.55f
            val ty = y2 - uy * cell * 0.55f
            val w = p.measureText(it)
            p.color = 0xCC000000.toInt()
            c.drawRoundRect(tx - w / 2 - 6, ty - p.textSize, tx + w / 2 + 6, ty + p.textSize * 0.3f, 8f, 8f, p)
            p.color = Color.WHITE
            c.drawText(it, tx, ty, p)
        }
    }
}
