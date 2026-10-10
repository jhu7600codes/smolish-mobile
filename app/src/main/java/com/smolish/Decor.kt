package com.smolish

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Holiday decorations for the cube: hats and eye styles, the same designs as the seasonal icons.
 * Hats are drawn in "hat units" (u) around their base point, y up is negative, like icons.py.
 */
object Decor {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    private const val PURPLE = 0xFF7B3FBF.toInt() // brighter than the icon, it sits on the dark splash
    private const val SLIME = 0xFF7CFC5A.toInt()
    private const val RED = 0xFFDE2834.toInt()
    private const val PINK = 0xFFFFA8C4.toInt()
    private const val YELLOW = 0xFFFFD34E.toInt()
    private const val HOT_PINK = 0xFFFF5C9A.toInt()
    private const val NIGHT_BLUE = 0xFF5B7BD5.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    fun hasHat(kind: String) = kind in setOf("halloween", "winter", "easter", "birthday", "sleep", "thanksgiving", "ramadan", "eid")
    private val path2 = Path()

    /** Draws the hat for [kind] with its base at x,y, tilted by [angle] degrees, [u] px per hat unit. */
    fun hat(c: Canvas, kind: String, x: Float, y: Float, angle: Float, u: Float) {
        if (!hasHat(kind)) return
        c.save()
        c.translate(x, y)
        c.rotate(angle)
        c.scale(u, u)
        when (kind) {
            "halloween" -> {
                oval(c, 0f, 0f, 12.5f, 2.6f, PURPLE)
                poly(c, PURPLE, -6.5f, -0.5f, 6.5f, -0.5f, 3f, -9f, 7.5f, -16f, 0.5f, -11f, -3.5f, -6f)
                poly(c, SLIME, -6.2f, -1f, 6.2f, -1f, 5.2f, -3.4f, -5.4f, -3.4f)
            }
            "winter" -> {
                poly(c, RED, -8.5f, -1f, 8.5f, -1f, 6f, -7.5f, 10.5f, -9.5f, 2f, -12f, -4f, -8.5f)
                poly(c, WHITE, -10.5f, 1.5f, 10.5f, 1.5f, 10f, -3f, -10f, -3f)
                circle(c, 10.5f, -9.5f, 2.6f, WHITE)
            }
            "easter" -> {
                for ((side, tilt) in listOf(-4.2f to -12f, 4.2f to 10f)) {
                    c.save()
                    c.rotate(tilt)
                    c.translate(side, 0f)
                    oval(c, 0f, -8.5f, 3.4f, 8.5f, WHITE)
                    oval(c, 0f, -8.5f, 1.7f, 6.2f, PINK)
                    c.restore()
                }
                poly(c, PINK, -9f, 1.2f, 9f, 1.2f, 9f, -1.6f, -9f, -1.6f)
            }
            "birthday" -> {
                poly(c, YELLOW, -7.5f, 0f, 7.5f, 0f, 0f, -17f)
                for (t0 in floatArrayOf(0.25f, 0.55f)) {
                    val a = t0; val b = t0 + 0.13f
                    poly(c, HOT_PINK, -7.5f * (1 - a), -17f * a, 7.5f * (1 - a), -17f * a, 7.5f * (1 - b), -17f * b, -7.5f * (1 - b), -17f * b)
                }
                circle(c, 0f, -17f, 2.8f, WHITE)
            }
            "thanksgiving" -> { // pilgrim hat with a gold buckle
                oval(c, 0f, 0f, 11f, 2.4f, 0xFF3A3A42.toInt())
                poly(c, 0xFF4A4A54.toInt(), -6.5f, -1f, 6.5f, -1f, 5f, -13f, -5f, -13f)
                poly(c, 0xFF8B5A2B.toInt(), -6.3f, -1.4f, 6.3f, -1.4f, 6f, -4.2f, -6f, -4.2f)
                poly(c, 0xFFFFC83C.toInt(), -2.2f, -1.2f, 2.2f, -1.2f, 2.2f, -4.4f, -2.2f, -4.4f)
                poly(c, 0xFF8B5A2B.toInt(), -1.1f, -2f, 1.1f, -2f, 1.1f, -3.6f, -1.1f, -3.6f)
            }
            "ramadan", "eid" -> { // golden crescent moon and star floating above (not a hat)
                p.color = 0xFFFFCD50.toInt()
                path.reset(); path.addCircle(4f, -13f, 6.2f, Path.Direction.CW)
                path2.reset(); path2.addCircle(7.4f, -14.4f, 6.2f, Path.Direction.CW)
                path.op(path2, Path.Op.DIFFERENCE)
                c.drawPath(path, p)
                path.reset()
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) 2.6f else 1.05f
                    val a = Math.toRadians(-90.0 + 36 * i)
                    val px = (13f + r * cos(a)).toFloat(); val py = (-18.5f + r * sin(a)).toFloat()
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                c.drawPath(path, p)
            }
            "sleep" -> {
                // floppy nightcap, the tip hangs down to the right with a pompom
                p.color = NIGHT_BLUE
                path.reset()
                path.moveTo(-8.5f, -1.5f)
                path.cubicTo(-7f, -10f, 3f, -14f, 10f, -11f)
                path.cubicTo(15f, -9f, 17.5f, -5f, 17f, -1.5f)
                path.cubicTo(15.5f, -4.5f, 12.5f, -6.5f, 8f, -6f)
                path.lineTo(8.5f, -1.5f)
                path.close()
                c.drawPath(path, p)
                // two pale stripes
                p.color = 0xFF9DB3EE.toInt()
                poly(c, 0xFF9DB3EE.toInt(), -6.9f, -5.5f, 7.6f, -4.6f, 7.3f, -6.6f, -6f, -7.4f)
                circle(c, 17f, -0.5f, 2.4f, WHITE)
                poly(c, WHITE, -10f, 1.8f, 10f, 1.8f, 9.6f, -2.6f, -9.6f, -2.6f)
            }
        }
        c.restore()
    }

    /**
     * Draws a pair of eyes centered at cx +- dx, cy with radius [r]. [cut] paints pupils / holes
     * (background color). Returns false for the plain round eyes (caller draws those).
     */
    fun eyes(c: Canvas, kind: String, cx: Float, cy: Float, dx: Float, r: Float, color: Int, cut: Int): Boolean {
        when (kind) {
            "halloween" -> { // jack-o-lantern triangles
                p.color = color
                for (ex in floatArrayOf(cx - dx, cx + dx)) {
                    path.reset()
                    path.moveTo(ex - r * 1.2f, cy + r * 0.85f)
                    path.lineTo(ex + r * 1.2f, cy + r * 0.85f)
                    path.lineTo(ex, cy - r * 1.1f)
                    path.close()
                    c.drawPath(path, p)
                }
            }
            "valentine" -> for (ex in floatArrayOf(cx - dx, cx + dx)) heart(c, ex, cy, r * 1.35f, color)
            "aprilfools" -> { // derpy: one big eye, one small one higher up, pupils looking apart
                circle(c, cx - dx * 0.95f, cy + r * 0.2f, r * 1.4f, color)
                circle(c, cx - dx * 0.95f + r * 0.35f, cy + r * 0.45f, r * 0.62f, cut)
                circle(c, cx + dx * 1.05f, cy - r * 0.45f, r * 0.8f, color)
                circle(c, cx + dx * 1.05f - r * 0.25f, cy - r * 0.6f, r * 0.36f, cut)
            }
            else -> return false
        }
        return true
    }

    fun heart(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        p.color = color
        path.reset()
        val n = 48
        for (i in 0 until n) {
            val t = 2 * PI * i / n
            val hx = 16 * sin(t).pow(3)
            val hy = -(13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t))
            val px = x + (hx * r / 16).toFloat()
            val py = y + (hy * r / 16).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        c.drawPath(path, p)
    }

    private fun circle(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        p.color = color
        c.drawCircle(x, y, r, p)
    }

    private fun oval(c: Canvas, x: Float, y: Float, rx: Float, ry: Float, color: Int) {
        p.color = color
        rect.set(x - rx, y - ry, x + rx, y + ry)
        c.drawOval(rect, p)
    }

    private fun poly(c: Canvas, color: Int, vararg xy: Float) {
        p.color = color
        path.reset()
        path.moveTo(xy[0], xy[1])
        var i = 2
        while (i < xy.size) { path.lineTo(xy[i], xy[i + 1]); i += 2 }
        path.close()
        c.drawPath(path, p)
    }
}
