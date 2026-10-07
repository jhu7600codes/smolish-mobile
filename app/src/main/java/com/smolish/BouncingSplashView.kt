package com.smolish

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The little rounded square with eyes that hops while the site loads.
 * Plain View + postInvalidateOnAnimation, so it runs at display refresh rate with no extra libs.
 *
 * - hops off an invisible floor line, bounces off the screen edges
 * - turns a quarter while falling, turns back upright on the next jump
 * - squash on landing / stretch in the air (damped spring, so it jiggles)
 * - the eyes lag behind the movement: up while rising, down while falling
 */
class BouncingSplashView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private val size = 84 * dp

    // proportions measured from the app icon's body so it looks like the same character
    private val stroke = size * 0.12f
    private val corner = size * 0.2f
    private val eyeR = size * 0.076f
    private val eyeDx = size * 0.19f

    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = stroke
    }
    private val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = body.color }
    private val rect = RectF()

    private val gravity = 3200 * dp
    private val fallTurn = 90f // how far it turns while falling

    // x = center, y = bottom edge ("feet")
    private var x = Float.NaN
    private var y = 0f
    private var vx = 0f
    private var vy = 0f
    private var grounded = false
    private var rising = false // true from a jump until the top of the arc (landing rebounds don't count)
    private var restLeft = 0.4f

    private var angle = 0f
    private var turnTo = fallTurn // where it turns to on the way down, picked per jump

    // squash > 0 = flattened, < 0 = stretched
    private var squash = 0f
    private var squashVel = 0f

    // eye offset in screen space, follows velocity with a delay
    private var eyeX = 0f
    private var eyeY = 0f

    private var lastFrame = 0L
    private var running = true

    private val floor get() = height * 0.62f // the invisible line

    fun stop() {
        running = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0) return
        if (x.isNaN()) {
            x = width / 2f
            y = floor - 260 * dp
        }
        val now = System.nanoTime()
        // clamp dt so a hiccup doesn't teleport it through the floor
        val dt = if (lastFrame == 0L) 0f else min((now - lastFrame) / 1e9f, 1 / 30f)
        lastFrame = now
        step(dt)

        // stretch with speed in the air, keep the area roughly the same
        val airStretch = if (grounded) 0f else (abs(vy) / (5000 * dp)).coerceAtMost(0.15f)
        val sy = (1f - squash) * (1f + airStretch)
        val sx = 1f / sy

        canvas.save()
        canvas.translate(x, y)
        canvas.scale(sx, sy) // squash in screen space, pivot at the feet so it stays on the line
        canvas.translate(0f, -size / 2)
        canvas.rotate(angle)

        val h = (size - stroke) / 2
        rect.set(-h, -h, h, h)
        canvas.drawRoundRect(rect, corner - stroke / 2, corner - stroke / 2, body)

        // eyes move in screen space ("up" stays up), so undo the body rotation for the offset
        val rad = Math.toRadians(-angle.toDouble())
        val ex = (eyeX * cos(rad) - eyeY * sin(rad)).toFloat()
        val ey = (eyeX * sin(rad) + eyeY * cos(rad)).toFloat()
        canvas.drawCircle(-eyeDx + ex, ey, eyeR, eye)
        canvas.drawCircle(eyeDx + ex, ey, eyeR, eye)
        canvas.restore()

        if (running) postInvalidateOnAnimation()
    }

    private fun step(dt: Float) {
        if (dt == 0f) return
        val left = size / 2 + 16 * dp
        val right = width - size / 2 - 16 * dp

        var squashTarget = 0f
        if (grounded) {
            vx *= 1f - min(1f, 10f * dt) // friction
            restLeft -= dt
            if (restLeft < 0.12f) squashTarget = 0.18f // crouch before jumping
            if (restLeft <= 0f) hop(left, right)
        } else {
            vy += gravity * dt
            if (vy >= 0) rising = false
        }

        x += vx * dt
        y += vy * dt

        if (x < left) { x = left; vx = abs(vx) * 0.7f }
        if (x > right) { x = right; vx = -abs(vx) * 0.7f }

        if (!grounded && y >= floor && vy > 0) {
            y = floor
            // landing impact kicks the squash spring
            squashVel += (vy / (900 * dp)).coerceAtMost(3.5f) * 3f
            if (vy > 700 * dp) {
                vy = -vy * 0.3f // small rebound
            } else {
                vy = 0f
                grounded = true
                restLeft = Random.nextFloat() * 0.45f + 0.2f
            }
            vx *= 0.6f
        }

        // rotation: back upright while rising, quarter turn while falling,
        // and on the ground settle flat on whichever side is closest
        val angleTarget = when {
            grounded -> (angle / 90f).roundToInt() * 90f
            rising -> 0f
            else -> turnTo
        }
        angle += (angleTarget - angle) * min(1f, (if (grounded) 18f else 7f) * dt)

        // damped spring, underdamped so it jiggles a bit
        val accel = -260f * (squash - squashTarget) - 13f * squashVel
        squashVel += accel * dt
        squash = (squash + squashVel * dt).coerceIn(-0.3f, 0.35f)

        // eyes trail the momentum: rising -> look up, falling -> look down
        val maxEye = size * 0.14f
        val eyeTargetX = (vx / (2500 * dp) * size).coerceIn(-maxEye, maxEye)
        val eyeTargetY = if (grounded) 0f else (vy / (1600 * dp) * size * 0.5f).coerceIn(-maxEye, maxEye)
        val k = min(1f, 12f * dt)
        eyeX += (eyeTargetX - eyeX) * k
        eyeY += (eyeTargetY - eyeY) * k
    }

    private fun hop(left: Float, right: Float) {
        grounded = false
        rising = true
        // mostly little jumps, sometimes a big one
        val big = Random.nextFloat() < 0.25f
        vy = -(if (big) 1350f else 650f + Random.nextFloat() * 400f) * dp
        // random direction, nudged back toward the middle near the edges
        val center = (x - left) / (right - left) - 0.5f
        vx = ((Random.nextFloat() - 0.5f) * 2f - center * 1.5f) * 420f * dp
        // turn the way it's moving on the way down
        turnTo = if (vx >= 0) fallTurn else -fallTurn
        squashVel -= 2.5f // push off: stretch upward
    }
}
