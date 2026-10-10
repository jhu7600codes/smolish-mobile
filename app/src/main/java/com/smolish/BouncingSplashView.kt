package com.smolish

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/**
 * The little rounded square with eyes that hops while the site loads.
 * Plain View + postInvalidateOnAnimation, so it runs at display refresh rate with no extra libs.
 *
 * Intro (picks up exactly where the system splash icon was):
 * the clapper top from the icon winds up, snaps shut, gets shoved down into the body,
 * the wide body morphs into the square, and the square drops to the floor.
 *
 * Then:
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
    private val corner = size * 0.25f // outer corner radius, about 2x the line width like the icon
    private val eyeR = size * 0.076f
    private val eyeDx = size * 0.19f
    private val eyeDy = size * 0.17f // eyes sit low, same distance from the bottom as in the logo

    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
    }
    private val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val armPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    // fill + a little stroke so it overlaps the arm's edge and no seam shows between them
    private val web = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 4f
    }
    private val rect = RectF()
    private val path = Path()
    private val radii = FloatArray(8)

    private val gravity = 3200 * dp
    private var fallTurn = 90f // how far it turns while falling

    // holiday look (see Icons / Decor): hat, eyes, background particles
    var theme = "default"
        set(v) {
            field = v
            fallTurn = if (v == "aprilfools") 180f else 90f // april fools: lands upside down
            particles.clear()
        }
    private val bgColor = 0xFF0E0F13.toInt()
    private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var spin: Float, var a: Float, val color: Int, val s: Float)
    private val particles = ArrayList<Particle>()
    private val partPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // x = center, y = bottom edge ("feet")
    private var x = 0f
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

    // --- intro, all in icon pixel units (the 442x512 clapperboard png)
    // the clapper top cut from the icon (everything above the body, y < 215)
    private val arm: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.splash_arm)
    // icon pixels -> screen pixels. the system splash draws the icon 288dp wide (no icon
    // background), the clapper is 50/108 of that, so starting at this size makes the handoff seamless
    private val k = 288f * 50f / 108f * dp / ICON_H
    private val squareIcon = size / k // the final square, in icon pixels
    private var introT = 0f
    private var introDone = false
    private var armAngle = 0f
    private var armShove = 0f // 0 = arm on top, 1 = pushed all the way into the body
    private var morph = 0f // 0 = icon body, 1 = the square

    private var lastFrame = 0L
    private var running = true

    /** Called once when the intro is over and the cube starts to fall. */
    var onIntroDone: (() -> Unit)? = null
    val introFinished get() = introDone

    private val floor get() = height * 0.62f // the invisible line

    fun stop() {
        running = false
    }

    /** Loader mode for later page loads: no intro, the cube just drops from the middle and hops. */
    fun startLoader() {
        introDone = true
        x = width / 2f
        y = height / 2f
        vx = 0f
        vy = 0f
        grounded = false
        rising = false
        angle = 0f
        squash = 0f
        squashVel = 0f
        eyeX = 0f
        eyeY = 0f
        turnTo = if (Random.nextBoolean()) fallTurn else -fallTurn
        lastFrame = 0L
        running = true
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0) return
        val now = System.nanoTime()
        // clamp dt so a hiccup doesn't teleport it through the floor
        val dt = if (lastFrame == 0L) 0f else min((now - lastFrame) / 1e9f, 1 / 30f)
        lastFrame = now

        drawParticles(canvas, dt)
        if (!introDone) {
            stepIntro(dt)
            drawIntro(canvas)
        } else {
            step(dt)
            drawSquare(canvas)
        }
        if (running) postInvalidateOnAnimation()
    }

    private fun drawSquare(canvas: Canvas) {
        // stretch with speed in the air, keep the area roughly the same
        val airStretch = if (grounded) 0f else (abs(vy) / (5000 * dp)).coerceAtMost(0.15f)
        val sy = (1f - squash) * (1f + airStretch)

        canvas.save()
        canvas.translate(x, y)
        canvas.scale(1f / sy, sy) // squash in screen space, pivot at the feet so it stays on the line
        canvas.translate(0f, -size / 2)
        canvas.rotate(angle)

        val h = (size - stroke) / 2
        rect.set(-h, -h, h, h)
        body.strokeWidth = stroke
        canvas.drawRoundRect(rect, corner - stroke / 2, corner - stroke / 2, body)

        // eyes move in screen space ("up" stays up), so undo the body rotation for the offset
        val rad = Math.toRadians(-angle.toDouble())
        val ex = (eyeX * cos(rad) - eyeY * sin(rad)).toFloat()
        val ey = (eyeX * sin(rad) + eyeY * cos(rad)).toFloat()
        if (!Decor.eyes(canvas, theme, ex, eyeDy + ey, eyeDx, eyeR, Color.WHITE, bgColor)) {
            canvas.drawCircle(-eyeDx + ex, eyeDy + ey, eyeR, eye)
            canvas.drawCircle(eyeDx + ex, eyeDy + ey, eyeR, eye)
        }
        // the hat lags behind the movement a little, like the eyes
        val hatAngle = HAT_ANGLE + (eyeY / (size * 0.1f)) * 10f
        Decor.hat(canvas, theme, size * 0.14f, -size / 2 + stroke * 0.3f, hatAngle, size * HAT_U / ICON_W)
        if (theme == "birthday") {
            // both circle hands up in the air, waving a bit
            val wave = sin(System.nanoTime() / 1e9 * 9).toFloat() * size * 0.03f
            canvas.drawCircle(-size * 0.595f, -size * 0.83f + wave, size * 0.118f, eye)
            canvas.drawCircle(size * 0.595f, -size * 0.83f - wave, size * 0.118f, eye)
        }
        canvas.restore()
    }

    /** Snow for new year, confetti for birthdays, floating hearts for valentine's. */
    private fun drawParticles(canvas: Canvas, dt: Float) {
        if (theme !in setOf("winter", "birthday", "valentine", "ramadan", "eid")) return
        if (particles.isEmpty()) repeat(if (theme == "valentine") 14 else if (theme == "ramadan") 40 else 46) { particles.add(spawn(true)) }
        for (q in particles) {
            q.x += q.vx * dt; q.y += q.vy * dt; q.a += q.spin * dt
            if (q.y > height + 30 * dp || q.y < -30 * dp) { val n = spawn(false); q.x = n.x; q.y = n.y }
            when (theme) {
                "winter" -> { partPaint.color = q.color; canvas.drawCircle(q.x + sin(q.a) * 6 * dp, q.y, q.s, partPaint) }
                "valentine" -> Decor.heart(canvas, q.x + sin(q.a) * 10 * dp, q.y, q.s, q.color)
                "ramadan" -> { // still night sky, stars twinkle in place
                    partPaint.color = q.color
                    partPaint.alpha = ((0.35f + 0.65f * (0.5f + 0.5f * sin(q.a))) * Color.alpha(q.color)).toInt()
                    canvas.drawCircle(q.x, q.y, q.s, partPaint)
                }
                "eid" -> { // gold sparkles drifting down, spinning
                    partPaint.color = q.color
                    canvas.save(); canvas.rotate(q.a * 40f, q.x, q.y)
                    canvas.drawRect(q.x - q.s, q.y - q.s * 0.28f, q.x + q.s, q.y + q.s * 0.28f, partPaint)
                    canvas.drawRect(q.x - q.s * 0.28f, q.y - q.s, q.x + q.s * 0.28f, q.y + q.s, partPaint)
                    canvas.restore()
                }
                else -> {
                    partPaint.color = q.color
                    canvas.save(); canvas.rotate(q.a * 57f, q.x, q.y)
                    canvas.drawRect(q.x - q.s, q.y - q.s * 0.45f, q.x + q.s, q.y + q.s * 0.45f, partPaint)
                    canvas.restore()
                }
            }
        }
    }

    private fun spawn(anywhere: Boolean): Particle {
        val w = width.toFloat(); val h = height.toFloat()
        return when (theme) {
            "valentine" -> Particle(Random.nextFloat() * w, if (anywhere) Random.nextFloat() * h else h + 20 * dp,
                0f, -(30 + Random.nextFloat() * 40) * dp, 1.5f + Random.nextFloat(), Random.nextFloat() * 6f,
                Color.argb(70 + Random.nextInt(80), 255, 79, 139), (7 + Random.nextFloat() * 8) * dp)
            "ramadan" -> Particle(Random.nextFloat() * w, Random.nextFloat() * h, 0f, 0f, 1.5f + Random.nextFloat() * 2f,
                Random.nextFloat() * 6f, Color.argb(150 + Random.nextInt(100), 255, 236, 170), (1f + Random.nextFloat() * 1.6f) * dp)
            "eid" -> Particle(Random.nextFloat() * w, if (anywhere) Random.nextFloat() * h else -10 * dp,
                (Random.nextFloat() - 0.5f) * 20 * dp, (35 + Random.nextFloat() * 45) * dp, (Random.nextFloat() - 0.5f) * 4f,
                Random.nextFloat() * 6f, Color.argb(160 + Random.nextInt(90), 255, 215, 100), (2.5f + Random.nextFloat() * 2.5f) * dp)
            "winter" -> Particle(Random.nextFloat() * w, if (anywhere) Random.nextFloat() * h else -10 * dp,
                0f, (30 + Random.nextFloat() * 50) * dp, 1f + Random.nextFloat(), Random.nextFloat() * 6f,
                Color.argb(110 + Random.nextInt(120), 255, 255, 255), (1.5f + Random.nextFloat() * 2.5f) * dp)
            else -> Particle(Random.nextFloat() * w, if (anywhere) Random.nextFloat() * h else -10 * dp,
                (Random.nextFloat() - 0.5f) * 30 * dp, (60 + Random.nextFloat() * 70) * dp, (Random.nextFloat() - 0.5f) * 8f,
                Random.nextFloat() * 6f, CONFETTI[Random.nextInt(CONFETTI.size)], (3 + Random.nextFloat() * 2.5f) * dp)
        }
    }

    private fun drawIntro(canvas: Canvas) {
        val m = morph
        val bw = lerp(ICON_W, squareIcon, m)
        val bh = lerp(ICON_H - BODY_TOP, squareIcon, m)
        val sw = lerp(53f, squareIcon * 0.12f, m)
        val cx = ICON_W / 2

        canvas.save()
        // icon centered on screen like the system splash; squash pivots on the body's bottom
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(k, k)
        canvas.translate(-cx, -ICON_H / 2)
        canvas.scale(1f / (1f - squash), 1f - squash, cx, ICON_H)

        // body: the icon's wide body (square top left where the arm joins) morphing into the square
        val sq = squareIcon * 0.25f
        val tl = lerp(0f, sq, m)
        val tr = lerp(30f, sq, m)
        val bottom = lerp(110f, sq, m)
        val inset = sw / 2
        rect.set(cx - bw / 2 + inset, ICON_H - bh + inset, cx + bw / 2 - inset, ICON_H - inset)
        radii[0] = max(0f, tl - inset); radii[1] = radii[0]
        radii[2] = max(0f, tr - inset); radii[3] = radii[2]
        radii[4] = max(0f, bottom - inset); radii[5] = radii[4]
        radii[6] = radii[4]; radii[7] = radii[4]
        path.reset()
        path.addRoundRect(rect, radii, Path.Direction.CW)
        body.strokeWidth = sw
        canvas.drawPath(path, body)

        val r = lerp(33.5f, squareIcon * 0.076f, m)
        val edx = lerp(84f, squareIcon * 0.19f, m)
        val ey = lerp(366f, ICON_H - squareIcon / 2 + squareIcon * 0.17f, m)
        if (!Decor.eyes(canvas, theme, cx, ey, edx, r, Color.WHITE, bgColor)) {
            canvas.drawCircle(cx - edx, ey, r, eye)
            canvas.drawCircle(cx + edx, ey, r, eye)
        }
        if (theme == "birthday") {
            // hands pop up next to the body once the hat has landed
            val pop = ((introT - 0.3f) / 0.15f).coerceIn(0f, 1f)
            val hr = 0.118f * bw * pop
            val hy = ICON_H - bh - 0.33f * bw
            canvas.drawCircle(cx - 0.595f * bw, hy, hr, eye)
            canvas.drawCircle(cx + 0.595f * bw, hy, hr, eye)
        }

        // the clapper top. its underside is a straight line that meets the body's top edge at
        // HINGE_X, so rotating around that point lays it exactly flush on the body
        if (armShove < 1f) {
            canvas.save()
            // shoving squeezes it down into the body's top line (white on white, so it just merges)
            canvas.scale(1f, 1f - armShove, 0f, BODY_TOP + 26f) // into the middle of the top line
            // tilt the arm with a vertical shear around the hinge instead of a rotation: the
            // underside still lands exactly flat, but vertical edges stay vertical, so the arm's
            // left side stays lined up with the body and its right end doesn't stick out
            val t = tan(Math.toRadians(armAngle.toDouble())).toFloat()
            // while closing the arm also sinks, so its bottom line ends up exactly on top of the
            // body's top line (one line, not two stacked)
            val drop = ARM_BAND * (armAngle / CLOSE_ANGLE).coerceIn(0f, 1f)
            if (t > 0f) {
                // closing lifts the solid left part of the arm off the body, fill that space so
                // the arm and body never come apart. its top edge is the arm's (sheared, sunk)
                // underside; wherever that is below the body's top it only paints white on white
                path.reset()
                path.moveTo(0f, BODY_TOP + 1f)
                path.lineTo(HINGE_X, BODY_TOP + 1f)
                path.lineTo(HINGE_X, BODY_TOP + drop)
                path.lineTo(0f, BODY_TOP + drop - HINGE_X * t)
                path.close()
                canvas.drawPath(path, web)
            }
            canvas.translate(0f, drop)
            canvas.translate(HINGE_X, BODY_TOP)
            canvas.skew(0f, t)
            canvas.translate(-HINGE_X, -BODY_TOP)
            canvas.drawBitmap(arm, 0f, 0f, armPaint)
            canvas.restore()
        }
        drawIntroHat(canvas, cx, bw, bh, sw)
        canvas.restore()
    }

    /**
     * The holiday hat during the intro: drops onto the clapper top, rides it while it snaps shut
     * and gets shoved in, then sits on the cube's top edge (same spot as while bouncing).
     */
    private fun drawIntroHat(canvas: Canvas, cx: Float, bw: Float, bh: Float, sw: Float) {
        if (!Decor.hasHat(theme)) return
        // on the arm: same point the seasonal icons use, moved like the arm (shear + sink + squeeze)
        val t = tan(Math.toRadians(armAngle.toDouble())).toFloat()
        val drop = ARM_BAND * (armAngle / CLOSE_ANGLE).coerceIn(0f, 1f)
        val pivot = BODY_TOP + 26f
        var ax = HAT_X
        var ay = HAT_Y + drop + (HAT_X - HINGE_X) * t
        ay = pivot + (ay - pivot) * (1f - armShove)
        val armAng = Math.toDegrees(atan((ARM_SLOPE + t).toDouble())).toFloat() * (1f - armShove)
        // on the cube: the same spot as drawSquare, in icon px
        val tx = cx + 0.14f * bw
        val ty = ICON_H - bh + sw * 0.3f
        val f = armShove
        val hx = lerp(ax, tx, f)
        var hy = lerp(ay, ty, f)
        val ang = lerp(armAng, HAT_ANGLE, f)
        // falls in from above at the start
        val fall = (introT / 0.3f).coerceIn(0f, 1f)
        hy -= 320f * (1f - fall * fall)
        Decor.hat(canvas, theme, hx, hy, ang, HAT_U * bw / ICON_W)
    }

    private fun stepIntro(dt: Float) {
        val before = introT
        introT += dt
        fun passed(t: Float) = before < t && introT >= t
        armAngle = when {
            introT < 0.15f -> 0f
            // open a bit more (wind up) ...
            introT < 0.35f -> -6f * ease((introT - 0.15f) / 0.2f)
            // ... then snap shut onto the body, accelerating
            introT < 0.47f -> lerp(-6f, CLOSE_ANGLE, ((introT - 0.35f) / 0.12f).let { it * it })
            else -> CLOSE_ANGLE
        }
        if (passed(0.3f) && Decor.hasHat(theme)) squashVel += 1.2f // hat lands
        if (passed(0.47f)) squashVel += 3f // clap
        armShove = if (introT < 0.56f) 0f else ease(((introT - 0.56f) / 0.2f).coerceAtMost(1f))
        if (passed(0.76f)) squashVel += 2.5f // arm merges into the body
        // only after the arm is fully merged, or its squished line would stick out of the shrinking body
        morph = if (introT < 0.78f) 0f else smooth(((introT - 0.78f) / 0.3f).coerceAtMost(1f))
        spring(dt, 0f)

        if (introT >= 1.12f) {
            // hand over to the physics from exactly where the morphed square is: it falls from here
            introDone = true
            x = width / 2f
            y = height / 2f + ICON_H / 2 * k
            grounded = false
            rising = false
            vy = 0f
            turnTo = if (Random.nextBoolean()) fallTurn else -fallTurn
            onIntroDone?.invoke()
        }
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

        spring(dt, squashTarget)

        // eyes trail the momentum: rising -> look up, falling -> look down
        val maxEye = size * 0.1f
        val eyeTargetX = (vx / (2500 * dp) * size).coerceIn(-maxEye, maxEye)
        val eyeTargetY = if (grounded) 0f else (vy / (1600 * dp) * size * 0.5f).coerceIn(-maxEye, maxEye)
        val e = min(1f, 12f * dt)
        eyeX += (eyeTargetX - eyeX) * e
        eyeY += (eyeTargetY - eyeY) * e
    }

    // damped spring, underdamped so it jiggles a bit
    private fun spring(dt: Float, target: Float) {
        val accel = -260f * (squash - target) - 13f * squashVel
        squashVel += accel * dt
        squash = (squash + squashVel * dt).coerceIn(-0.3f, 0.35f)
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

    private fun ease(t: Float) = 1f - (1f - t) * (1f - t)
    private fun smooth(t: Float) = t * t * (3f - 2f * t)
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private companion object {
        // the clapperboard png the icon is made from
        const val ICON_W = 442f
        const val ICON_H = 512f
        const val BODY_TOP = 215f // where the body's top edge is; the arm is everything above
        const val HINGE_X = 235.3f // where the arm's underside meets the body's top edge
        const val ARM_BAND = 53f // thickness of the arm's bottom line, same as the body's line
        const val ARM_SLOPE = -0.3015f // tan of the arm's top / underside angle (-16.8 deg)
        // hats: base point on the arm's top edge (same as the icons), units and tilt on the cube
        const val HAT_X = 250f
        const val HAT_Y = 29f
        const val HAT_U = 12.19f // icon px per hat unit (the icons draw the clapper 42 units tall)
        const val HAT_ANGLE = -8f
        val CONFETTI = intArrayOf(0xFFFFD34E.toInt(), 0xFFFF5C9A.toInt(), 0xFF78E68C.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF963C.toInt())
        const val CLOSE_ANGLE = 16.8f // angle of the arm's underside, shearing by it lays it flush
    }
}
