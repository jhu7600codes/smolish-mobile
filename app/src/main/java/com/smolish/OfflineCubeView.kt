package com.smolish

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The cube on the "can't reach Smolish" screen.
 * - SLEEP: internet works but the site doesn't answer. eyes closed, breathing, z's floating up.
 * - SEARCH: no internet at all. it holds a shake flashlight up with its circle hands and sweeps
 *   the beam around. shaking the phone charges the light, it slowly runs down and flickers when
 *   it's almost empty. when wifi shows up, a wifi icon pops up right where it's pointing.
 */
class OfflineCubeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs), SensorEventListener {

    enum class Mode { NONE, SLEEP, SEARCH }

    var mode = Mode.NONE
        private set

    private val dp = resources.displayMetrics.density
    private val size = 72 * dp
    private val stroke = size * 0.12f
    private val corner = size * 0.25f
    private val eyeR = size * 0.076f
    private val eyeDx = size * 0.19f
    private val eyeDy = size * 0.17f
    private val handR = eyeR * 1.45f

    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val zPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD
    }
    private val torch = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC9CCD3.toInt() }
    private val lens = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFE9A8.toInt() }
    private val beam = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wifi = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.brand); style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeWidth = 4 * dp
    }
    private val rect = RectF()
    private val path = Path()

    private var t = 0f
    private var lastFrame = 0L

    // search
    private var charge = 0.6f       // 0..1, how bright the flashlight is
    private var shakeJitter = 0f    // makes the torch wobble while you shake
    private var aim = -90f          // beam direction in degrees (-90 = straight up)
    private var found = -1f         // time the wifi was found, -1 = not yet
    private var onFound: (() -> Unit)? = null
    private var foundCalled = false

    // sleep -> wake up hop
    private var wake = -1f
    private var onWake: (() -> Unit)? = null
    private var wakeCalled = false

    private val sensors get() = context.getSystemService<SensorManager>()

    fun setMode(m: Mode) {
        if (m == mode) return
        mode = m
        found = -1f; foundCalled = false; onFound = null
        wake = -1f; wakeCalled = false; onWake = null
        charge = 0.6f
        updateSensor()
        lastFrame = 0L
        invalidate()
    }

    /** Sleep mode: wakes up, hops, then runs [then]. */
    fun wakeUp(then: () -> Unit) {
        if (mode != Mode.SLEEP || wake >= 0) return then()
        wake = t
        onWake = then
    }

    /** Search mode: the wifi icon appears where the beam points, then [then] runs. */
    fun foundWifi(then: () -> Unit) {
        if (mode != Mode.SEARCH || found >= 0) return
        found = t
        charge = 1f
        onFound = then
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateSensor()
    }

    override fun onDetachedFromWindow() {
        sensors?.unregisterListener(this)
        super.onDetachedFromWindow()
    }

    private fun updateSensor() {
        val sm = sensors ?: return
        sm.unregisterListener(this)
        if (mode == Mode.SEARCH && isAttachedToWindow) {
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }
    }

    // shake = how far the acceleration is from plain gravity; strong shakes charge the light
    override fun onSensorChanged(e: SensorEvent) {
        val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
        val jolt = abs(g - SensorManager.GRAVITY_EARTH)
        if (jolt > 3f) {
            charge = min(1f, charge + jolt * 0.004f)
            shakeJitter = min(1f, shakeJitter + jolt * 0.03f)
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mode == Mode.NONE || width == 0) return
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else min((now - lastFrame) / 1e9f, 1 / 30f)
        lastFrame = now
        t += dt
        if (mode == Mode.SLEEP) drawSleep(canvas) else drawSearch(canvas, dt)
        if (isShown) postInvalidateOnAnimation()
    }

    // --- sleeping
    private fun drawSleep(c: Canvas) {
        val cx = width / 2f
        val floor = height * 0.82f
        var lift = 0f
        var squash = 0.03f * sin(t * 1.6f) // breathing
        val awake = wake >= 0
        if (awake) {
            val w = t - wake
            // short surprise, then a hop: up and down in 0.45s, squash on landing
            val hop = ((w - 0.25f) / 0.45f).coerceIn(0f, 1f)
            lift = 70 * dp * 4 * hop * (1 - hop)
            squash = if (w in 0.7f..0.9f) 0.18f * (1 - (w - 0.7f) / 0.2f) else if (w < 0.25f) 0.1f else -0.06f * sin(hop * 3.14f)
            if (w > 1.0f && !wakeCalled) { wakeCalled = true; onWake?.invoke() }
        }
        val bob = if (awake) 0f else 3 * dp * sin(t * 1.6f)
        val feet = floor - lift + bob
        drawBody(c, cx, feet, 1f - squash)
        // nightcap, swaying a little with the breathing; it jumps off his head a bit when he wakes
        val capLift = if (awake) lift * 0.25f else 0f
        Decor.hat(c, "sleep", cx + size * 0.14f, feet - size * (1f - squash) + stroke * 0.3f - capLift,
            -8f + 3f * sin(t * 1.6f), size * 12.19f / 442f * 1.3f)
        val eyeY = feet - size / 2 * (1f - squash) + eyeDy
        if (awake) {
            c.drawCircle(cx - eyeDx, eyeY, eyeR * 1.15f, white)
            c.drawCircle(cx + eyeDx, eyeY, eyeR * 1.15f, white)
        } else {
            // closed eyes: little downward curves
            line.strokeWidth = eyeR * 0.75f
            for (ex in floatArrayOf(cx - eyeDx, cx + eyeDx)) {
                rect.set(ex - eyeR * 1.3f, eyeY - eyeR * 1.6f, ex + eyeR * 1.3f, eyeY + eyeR * 0.6f)
                c.drawArc(rect, 30f, 120f, false, line)
            }
            // z's drifting up and to the right, a new one every 1.1s
            val top = feet - size
            for (i in 0 until 3) {
                val p = ((t + i * 1.1f) % 3.3f) / 3.3f
                zPaint.textSize = (12 + 14 * p) * dp
                zPaint.alpha = (255 * sin(p * 3.14f)).toInt().coerceIn(0, 255)
                c.drawText("z", cx + size * 0.45f + p * 40 * dp, top - p * 70 * dp, zPaint)
            }
        }
    }

    // --- searching with the flashlight
    private fun drawSearch(c: Canvas, dt: Float) {
        val cx = width / 2f
        val feet = height * 0.86f
        val isFound = found >= 0
        val ft = if (isFound) t - found else 0f
        if (!isFound) {
            charge = (charge - dt * 0.05f).coerceAtLeast(0f) // runs down
            // wander around the sky in an uneven way, like it's really looking
            aim = -85f + 40f * sin(t * 0.7f) + 10f * sin(t * 1.9f)
        }
        shakeJitter = (shakeJitter - dt * 2.5f).coerceAtLeast(0f)
        val hop = if (isFound) { val h = (ft / 0.45f).coerceIn(0f, 1f); 40 * dp * 4 * h * (1 - h) } else 0f
        val bodyFeet = feet - hop
        val top = bodyFeet - size

        // the torch is held in one hand out to the cube's right side, pivoting around the grip
        val jx = if (shakeJitter > 0) (Random.nextFloat() - 0.5f) * 8 * dp * shakeJitter else 0f
        val jy = if (shakeJitter > 0) (Random.nextFloat() - 0.5f) * 8 * dp * shakeJitter else 0f
        val px = cx + size * 0.68f + jx
        val py = top + size * 0.15f + jy
        val rad = Math.toRadians(aim.toDouble())
        val dx = cos(rad).toFloat()
        val dy = sin(rad).toFloat()
        val torchLen = 42 * dp
        val lx = px + dx * torchLen * 0.55f
        val ly = py + dy * torchLen * 0.55f

        // beam: a cone that gets longer and brighter with charge, flickers when nearly empty
        var bright = charge
        if (!isFound && charge < 0.18f) bright *= if (Random.nextFloat() < 0.35f) 0.2f else 1f
        if (bright > 0.02f) {
            val len = (110 + 170 * charge) * dp
            val spread = Math.toRadians(17.0)
            val ex1 = lx + cos(rad - spread).toFloat() * len
            val ey1 = ly + sin(rad - spread).toFloat() * len
            val ex2 = lx + cos(rad + spread).toFloat() * len
            val ey2 = ly + sin(rad + spread).toFloat() * len
            beam.shader = LinearGradient(lx, ly, lx + dx * len, ly + dy * len,
                Color.argb((190 * bright).toInt(), 255, 236, 170), Color.argb(0, 255, 236, 170), Shader.TileMode.CLAMP)
            path.reset(); path.moveTo(lx, ly); path.lineTo(ex1, ey1); path.lineTo(ex2, ey2); path.close()
            c.drawPath(path, beam)
        }

        // wifi found: icon pops up in the beam, ~60% of the way out
        if (isFound) {
            val pop = (ft / 0.35f).coerceIn(0f, 1f)
            val s = pop * (1.25f - 0.25f * pop)
            // in the beam, but never outside the view
            val wx = (lx + dx * 150 * dp).coerceIn(24 * dp, width - 24 * dp)
            val wy = (ly + dy * 150 * dp).coerceAtLeast(24 * dp)
            for (i in 1..3) {
                val r = i * 9 * dp * s
                rect.set(wx - r, wy - r, wx + r, wy + r)
                c.drawArc(rect, -135f, 90f, false, wifi)
            }
            wifi.style = Paint.Style.FILL
            c.drawCircle(wx, wy, 3.5f * dp * s, wifi)
            wifi.style = Paint.Style.STROKE
            if (ft > 1.4f && !foundCalled) { foundCalled = true; onFound?.invoke() }
        }

        drawBody(c, cx, bodyFeet, 1f)

        // torch: square-ended handle, a head that flares out, the lens at the front
        // (drawn pointing "up", then rotated to the aim)
        c.save()
        c.rotate(aim + 90f, px, py)
        val L = torchLen
        rect.set(px - 5.5f * dp, py - L * 0.15f, px + 5.5f * dp, py + L * 0.45f)
        c.drawRoundRect(rect, 2 * dp, 2 * dp, torch)
        path.reset()
        path.moveTo(px - 5.5f * dp, py - L * 0.15f)
        path.lineTo(px - 10f * dp, py - L * 0.55f)
        path.lineTo(px + 10f * dp, py - L * 0.55f)
        path.lineTo(px + 5.5f * dp, py - L * 0.15f)
        path.close()
        c.drawPath(path, torch)
        rect.set(px - 10f * dp, py - L * 0.55f - 3 * dp, px + 10f * dp, py - L * 0.55f + 3 * dp)
        lens.alpha = (120 + 135 * bright).toInt().coerceIn(0, 255)
        c.drawRoundRect(rect, 2 * dp, 2 * dp, lens)
        c.restore()
        // one circle hand gripping the handle
        c.drawCircle(px, py, handR, white)

        // eyes follow the beam; happy ^ ^ once wifi is found
        val eyeY = top + size / 2 + eyeDy
        if (isFound) {
            line.strokeWidth = eyeR * 0.75f
            for (ex in floatArrayOf(cx - eyeDx, cx + eyeDx)) {
                rect.set(ex - eyeR * 1.3f, eyeY - eyeR * 0.6f, ex + eyeR * 1.3f, eyeY + eyeR * 1.6f)
                c.drawArc(rect, 210f, 120f, false, line)
            }
        } else {
            val look = eyeR * 0.9f
            c.drawCircle(cx - eyeDx + dx * look, eyeY + dy * look * 0.6f, eyeR, white)
            c.drawCircle(cx + eyeDx + dx * look, eyeY + dy * look * 0.6f, eyeR, white)
        }
    }

    private fun drawBody(c: Canvas, cx: Float, feet: Float, sy: Float) {
        c.save()
        c.scale(1f / sy, sy, cx, feet)
        val h = (size - stroke) / 2
        val cy = feet - size / 2
        rect.set(cx - h, cy - h, cx + h, cy + h)
        line.strokeWidth = stroke
        c.drawRoundRect(rect, corner - stroke / 2, corner - stroke / 2, line)
        c.restore()
    }
}
