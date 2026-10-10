package com.smolish

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Smol, the cube that walks you through the welcome screen. Drops in, waves, bobs while he talks,
 * looks up while he thinks and hops when you're done. Wears today's holiday hat / eyes.
 */
class SmolView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Mood { WAVE, TALK, THINK, HAPPY }

    var mood = Mood.WAVE
        set(v) {
            if (v == Mood.HAPPY && field != Mood.HAPPY) hopAt = t
            if (v == Mood.WAVE && field != Mood.WAVE) waveAt = t
            field = v
        }
    var talking = false // bobs to the words while the bubble types
    var theme = "default"

    private val dp = resources.displayMetrics.density
    private val size = 96 * dp
    private val stroke = size * 0.12f
    private val corner = size * 0.25f
    private val eyeR = size * 0.076f
    private val eyeDx = size * 0.19f
    private val eyeDy = size * 0.17f
    private val handR = eyeR * 1.45f
    private val bg = 0xFF0E0F13.toInt()

    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()

    private var t = 0f
    private var lastFrame = 0L
    private var waveAt = 0.55f  // waves right after landing
    private var hopAt = -10f
    private var nextBlink = 2.5f

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (width == 0) return
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else min((now - lastFrame) / 1e9f, 1 / 30f)
        lastFrame = now
        t += dt

        val cx = width / 2f
        val floor = height * 0.88f

        // drop in from above with a squash on landing, like the splash cube
        val fall = (t / 0.45f).coerceIn(0f, 1f)
        var lift = (1 - fall * fall) * (floor + size)
        var squash = 0f
        if (t > 0.45f) {
            val s = t - 0.45f
            squash = 0.22f * exp(-s * 7f) * cos(s * 22f)
        }
        // happy hops
        val h = t - hopAt
        if (h in 0f..1.2f) {
            val k = (h % 0.6f) / 0.6f
            lift += 36 * dp * 4 * k * (1 - k)
            if (k < 0.12f || k > 0.92f) squash += 0.08f
        }
        if (talking && t > 0.8f) squash += 0.035f * sin(t * 16f) // chatting
        else if (t > 1f) squash += 0.02f * sin(t * 2.2f)         // breathing

        val sy = 1f - squash
        val feet = floor - lift
        val top = feet - size * sy
        val half = size / sy / 2

        // body
        c.save()
        c.scale(1f / sy, sy, cx, feet)
        val r = (size - stroke) / 2
        rect.set(cx - r, feet - size / 2 - r, cx + r, feet - size / 2 + r)
        line.strokeWidth = stroke
        c.drawRoundRect(rect, corner - stroke / 2, corner - stroke / 2, line)
        c.restore()

        // left hand resting by the side
        c.drawCircle(cx - half - handR * 0.9f, feet - size * 0.28f, handR, white)
        // right hand: waves (a few swings), else rests; up high when happy
        val w = t - waveAt
        val waving = mood == Mood.WAVE && w in 0f..2.4f || mood == Mood.HAPPY
        val pivotX = cx + half
        val pivotY = feet - size * 0.45f * sy
        val ang = when {
            waving -> -60f + 28f * sin(w * 11f)              // up and swinging
            mood == Mood.THINK -> -105f                       // hand on the "chin"
            else -> 65f                                       // down by the side
        }
        val reach = size * 0.42f
        val a = Math.toRadians(ang.toDouble())
        val hx = pivotX + (reach * cos(a)).toFloat() * if (mood == Mood.THINK) 0.5f else 1f
        val hy = pivotY + (reach * sin(a)).toFloat() * if (mood == Mood.THINK) 0.35f else 1f
        c.drawCircle(if (mood == Mood.THINK) cx + half * 0.45f else hx, if (mood == Mood.THINK) feet - size * 0.12f else hy, handR, white)

        // holiday hat
        Decor.hat(c, theme, cx + size * 0.14f, top + stroke * 0.3f, -8f + 2f * sin(t * 2.2f), size * 12.19f / 442f * 1.3f)

        // eyes
        val look = if (mood == Mood.THINK) -0.7f else 0f
        val eyeY = feet - size / 2 * sy + eyeDy + look * eyeR
        val ex = if (mood == Mood.THINK) eyeR * 0.5f else 0f
        if (t > nextBlink) nextBlink = t + 2.5f + (t * 7.3f % 2.5f)
        val blink = nextBlink - t < 0.12f
        when {
            mood == Mood.HAPPY -> { // ^ ^
                line.strokeWidth = eyeR * 0.75f
                for (x in floatArrayOf(cx - eyeDx, cx + eyeDx)) {
                    rect.set(x - eyeR * 1.3f, eyeY - eyeR * 0.6f, x + eyeR * 1.3f, eyeY + eyeR * 1.6f)
                    c.drawArc(rect, 210f, 120f, false, line)
                }
            }
            blink -> {
                line.strokeWidth = eyeR * 0.6f
                for (x in floatArrayOf(cx - eyeDx, cx + eyeDx)) c.drawLine(x - eyeR, eyeY, x + eyeR, eyeY, line)
            }
            !Decor.eyes(c, theme, cx + ex, eyeY, eyeDx, eyeR, Color.WHITE, bg) -> {
                c.drawCircle(cx - eyeDx + ex, eyeY, eyeR, white)
                c.drawCircle(cx + eyeDx + ex, eyeY, eyeR, white)
            }
        }
        if (isShown) postInvalidateOnAnimation()
    }
}
