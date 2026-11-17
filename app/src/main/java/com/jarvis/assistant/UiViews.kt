package com.jarvis.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import kotlin.math.min
import kotlin.math.sin

fun col(s: String): Int = Color.parseColor(s)
fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

/** Black -> deep blue glow rising from the bottom, plus a soft haze behind the orb. */
class BackgroundView(c: Context) : View(c) {
    private val base = Paint()
    private val haze = Paint()
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        base.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(col("#050505"), col("#050505"), col("#04122E"), col("#0A3A8A"), col("#2B7BE0")),
            floatArrayOf(0f, 0.50f, 0.72f, 0.90f, 1f), Shader.TileMode.CLAMP
        )
        haze.shader = RadialGradient(
            w / 2f, h * 0.24f, w * 0.80f,
            intArrayOf(col("#66184F9F"), col("#22103A7A"), col("#00000000")),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
    }
    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), base)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), haze)
    }
}

/** Glowing glass orb with luminous swirling ribbons. */
class OrbView(c: Context) : View(c) {
    var active = false
        set(v) { field = v; invalidate() }
    /** Glossy bright-blue sphere with a strong highlight (used by the launch animation). */
    var glossy = false
    private val glossBody = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glossShine = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lv = 0f
    private var angle = 0f
    private var cx = 0f
    private var cy = 0f
    private var r = 0f
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ribbon = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ribbonGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = col("#4D6FB8FF") }
    private val oval = RectF()
    private val anim: ValueAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 9000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { angle = it.animatedValue as Float; postInvalidateOnAnimation() }
    }

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    fun start() { if (!anim.isRunning) anim.start() }
    fun stop() { anim.cancel() }
    fun setSpinDuration(ms: Long) { anim.duration = ms }
    /** Mic loudness 0..1: makes the orb swell while you speak. */
    fun pushLevel(v: Float) { lv = maxOf(lv * 0.6f, v) }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        cx = w / 2f; cy = h / 2f; r = min(w, h) * 0.26f
        glowPaint.shader = RadialGradient(cx, cy, r * 1.9f,
            intArrayOf(col("#7A3A8CFF"), col("#553070E8"), col("#00000000")),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        bodyPaint.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 1.4f,
            intArrayOf(col("#0E2F6E"), col("#07163A"), col("#02060F")),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        glossBody.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 1.3f,
            intArrayOf(col("#8FCBFF"), col("#2F86FF"), col("#0B3CA8"), col("#04123A")),
            floatArrayOf(0f, 0.35f, 0.8f, 1f), Shader.TileMode.CLAMP)
        glossShine.shader = RadialGradient(cx - r * 0.38f, cy - r * 0.45f, r * 0.5f,
            intArrayOf(col("#D9FFFFFF"), col("#00FFFFFF")), null, Shader.TileMode.CLAMP)
        shine.shader = RadialGradient(cx - r * 0.35f, cy - r * 0.4f, r * 0.7f,
            intArrayOf(col("#2EFFFFFF"), col("#00FFFFFF")), null, Shader.TileMode.CLAMP)
        val sweep = SweepGradient(cx, cy,
            intArrayOf(col("#00000000"), col("#2B6BFF"), col("#8FE8FF"), col("#FFFFFF"), col("#4F8CFF"),
                col("#00000000"), col("#00000000"), col("#3A5BFF"), col("#9AD8FF"), col("#00000000"), col("#00000000")),
            floatArrayOf(0f, 0.10f, 0.20f, 0.26f, 0.36f, 0.46f, 0.58f, 0.68f, 0.78f, 0.90f, 1f))
        ribbon.shader = sweep
        ribbonGlow.shader = sweep
        ribbonGlow.maskFilter = BlurMaskFilter(r * 0.16f, BlurMaskFilter.Blur.NORMAL)
        rim.strokeWidth = resources.displayMetrics.density * 1.5f
    }

    override fun onDraw(canvas: Canvas) {
        lv *= 0.9f
        val s = if (active) 1f + 0.03f * sin(Math.toRadians((angle * 2f).toDouble())).toFloat() + lv * 0.08f else 1f
        canvas.save()
        canvas.scale(s, s, cx, cy)
        glowPaint.alpha = if (active) 255 else 130
        canvas.drawCircle(cx, cy, r * 1.9f, glowPaint)
        if (glossy) {
            canvas.drawCircle(cx, cy, r, glossBody)
            canvas.drawCircle(cx, cy, r * 0.92f, shine)
            canvas.drawCircle(cx - r * 0.38f, cy - r * 0.45f, r * 0.5f, glossShine)
        } else {
            canvas.drawCircle(cx, cy, r, bodyPaint)
            canvas.drawCircle(cx, cy, r * 0.92f, shine)
        }

        val a = if (active) 255 else 150
        ribbon.alpha = a; ribbonGlow.alpha = (a * 0.6f).toInt()

        // outer rim ribbon
        canvas.save(); canvas.rotate(angle, cx, cy)
        ribbon.strokeWidth = r * 0.085f; ribbonGlow.strokeWidth = r * 0.22f
        canvas.drawCircle(cx, cy, r * 0.95f, ribbonGlow)
        canvas.drawCircle(cx, cy, r * 0.95f, ribbon)
        canvas.restore()

        // inner swirl
        canvas.save(); canvas.rotate(35f - angle * 0.6f, cx, cy)
        oval.set(cx - r * 0.82f, cy - r * 0.55f, cx + r * 0.82f, cy + r * 0.55f)
        ribbon.strokeWidth = r * 0.05f; ribbonGlow.strokeWidth = r * 0.14f
        canvas.drawOval(oval, ribbonGlow)
        canvas.drawOval(oval, ribbon)
        canvas.restore()

        canvas.drawCircle(cx, cy, r, rim)
        canvas.restore()
    }
}

/** Container that paints a soft blurred glow around its (padded) child. */
class GlowBox(c: Context, private val pad: Int, private val radius: Float, glowColor: Int) : FrameLayout(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = glowColor
        maskFilter = BlurMaskFilter(pad * 0.7f, BlurMaskFilter.Blur.NORMAL)
    }
    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setPadding(pad, pad, pad, pad)
    }
    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(pad.toFloat(), pad.toFloat(), (width - pad).toFloat(), (height - pad).toFloat(), radius, radius, p)
        super.onDraw(canvas)
    }
}

/** Glowing audio-wave icon (animates while Jarvis is listening). */
class WaveView(c: Context) : View(c) {
    var active = false
        set(v) { field = v; invalidate() }
    private var phase = 0f
    private val bars = floatArrayOf(0.25f, 0.55f, 0.9f, 0.5f, 1f, 0.6f, 0.3f)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        color = col("#F2F7FF")
        setShadowLayer(10f, 0f, 0f, col("#9CC8FF"))
    }
    private val anim: ValueAnimator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
        duration = 1100
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; if (active) postInvalidateOnAnimation() }
    }
    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }
    fun start() { if (!anim.isRunning) anim.start() }
    fun stop() { anim.cancel() }
    override fun onDraw(canvas: Canvas) {
        val n = bars.size
        val step = width.toFloat() / n
        p.strokeWidth = step * 0.45f
        p.alpha = if (active) 255 else 170
        val cy = height / 2f
        for (i in 0 until n) {
            val m = if (active) 0.65f + 0.35f * sin(phase + i * 0.9f) else 1f
            val half = height * 0.45f * bars[i] * m
            val x = step * (i + 0.5f)
            canvas.drawLine(x, cy - half, x, cy + half, p)
        }
    }
}

/** Tiny "sliders" settings icon. */
class SlidersView(c: Context) : View(c) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col("#BFE0FF") }
    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        line.strokeWidth = 1.8f * d
        val w = width.toFloat(); val h = height.toFloat()
        val ys = floatArrayOf(h * 0.22f, h * 0.5f, h * 0.78f)
        val xs = floatArrayOf(w * 0.68f, w * 0.32f, w * 0.58f)
        for (i in 0..2) {
            canvas.drawLine(w * 0.08f, ys[i], w * 0.92f, ys[i], line)
            canvas.drawCircle(xs[i], ys[i], 3.2f * d, knob)
        }
    }
}

/** ScrollView that never grows taller than maxH. */
class MaxHeightScroll(c: Context, private val maxH: Int) : android.widget.ScrollView(c) {
    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
    }
}
