package com.smolish

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

/**
 * The little clapperboard that hops around while the site loads.
 * Plain View + postInvalidateOnAnimation, so it runs at display refresh rate with no extra libs.
 * Physics: gravity, wall bounces, a damped spring for squash and stretch, random hops.
 */
class BouncingSplashView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private val logo = ContextCompat.getDrawable(context, R.drawable.splash_logo)!!
    private val logoH = 88 * dp
    private val logoW = logoH * logo.intrinsicWidth / logo.intrinsicHeight
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 }

    private val gravity = 3200 * dp

    // x = center of the logo, y = its bottom edge ("feet")
    private var x = Float.NaN
    private var y = 0f
    private var vx = 0f
    private var vy = 0f
    private var grounded = false
    private var restLeft = 0.4f

    // squash > 0 = flattened, < 0 = stretched; driven by a spring so it wobbles back
    private var squash = 0f
    private var squashVel = 0f
    private var tilt = 0f

    private var lastFrame = 0L
    private var running = true

    private val floor get() = height * 0.62f

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
        // clamp dt so a hiccup doesn't teleport the logo through the floor
        val dt = if (lastFrame == 0L) 0f else min((now - lastFrame) / 1e9f, 1 / 30f)
        lastFrame = now
        step(dt)

        // shadow shrinks the higher it jumps
        val height01 = ((floor - y) / (300 * dp)).coerceIn(0f, 1f)
        val sw = logoW * 0.45f * (1f - 0.6f * height01)
        canvas.drawOval(x - sw, floor - 5 * dp, x + sw, floor + 5 * dp, shadow)

        // stretch in the air depending on speed, keep area roughly constant
        val airStretch = if (grounded) 0f else (abs(vy) / (5000 * dp)).coerceAtMost(0.18f)
        val sy = (1f - squash) * (1f + airStretch)
        val sx = 1f / sy

        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(tilt)
        canvas.scale(sx, sy) // pivot at the feet, so squashing keeps it on the floor
        logo.setBounds((-logoW / 2).toInt(), (-logoH).toInt(), (logoW / 2).toInt(), 0)
        logo.draw(canvas)
        canvas.restore()

        if (running) postInvalidateOnAnimation()
    }

    private fun step(dt: Float) {
        if (dt == 0f) return
        val left = logoW / 2 + 16 * dp
        val right = width - logoW / 2 - 16 * dp

        var squashTarget = 0f
        if (grounded) {
            vx *= 1f - min(1f, 10f * dt) // friction
            restLeft -= dt
            if (restLeft < 0.12f) squashTarget = 0.18f // crouch before jumping
            if (restLeft <= 0f) hop(left, right)
        } else {
            vy += gravity * dt
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

        // damped spring for squash, underdamped so it jiggles a bit
        val accel = -260f * (squash - squashTarget) - 13f * squashVel
        squashVel += accel * dt
        squash = (squash + squashVel * dt).coerceIn(-0.3f, 0.42f)

        val tiltTarget = if (grounded) 0f else (vx / (30 * dp)).coerceIn(-14f, 14f)
        tilt += (tiltTarget - tilt) * min(1f, 8f * dt)
    }

    private fun hop(left: Float, right: Float) {
        grounded = false
        // mostly little jumps, sometimes a big one
        val big = Random.nextFloat() < 0.25f
        vy = -(if (big) 1350f else 650f + Random.nextFloat() * 400f) * dp
        // random direction, nudged back toward the middle near the edges
        val center = (x - left) / (right - left) - 0.5f
        vx = ((Random.nextFloat() - 0.5f) * 2f - center * 1.5f) * 420f * dp
        squashVel -= 2.5f // push off: stretch upward
    }
}
