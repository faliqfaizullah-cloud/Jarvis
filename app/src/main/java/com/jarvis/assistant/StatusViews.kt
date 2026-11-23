package com.jarvis.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A glowing sphere made of hundreds of tiny radial light streaks that slowly rotate and twinkle, with a bright irregular core. */
class ParticleOrbView(c: Context) : View(c) {
    private val n = 900
    private val px = FloatArray(n); private val py = FloatArray(n); private val pz = FloatArray(n)
    private val lenR = FloatArray(n); private val ph = FloatArray(n); private val wd = FloatArray(n)
    private val buckets = Array(8) { FloatArray(n * 4) }
    private val counts = IntArray(8)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = col("#EAF2FF")
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val core2 = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cx = 0f
    private var cy = 0f
    private var r = 0f
    private val anim: ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { postInvalidateOnAnimation() }
    }

    init {
        val rnd = java.util.Random(11)
        for (i in 0 until n) {
            var x = rnd.nextGaussian().toFloat(); var y = rnd.nextGaussian().toFloat(); var z = rnd.nextGaussian().toFloat()
            var l = sqrt(x * x + y * y + z * z)
            x /= l; y /= l; z /= l
            if (rnd.nextFloat() < 0.28f) {            // extra particles facing us -> dense bright clumps near the core
                z = abs(z) + 0.6f
                l = sqrt(x * x + y * y + z * z); x /= l; y /= l; z /= l
            }
            px[i] = x; py[i] = y; pz[i] = z
            lenR[i] = rnd.nextFloat(); ph[i] = rnd.nextFloat() * 6.2832f; wd[i] = rnd.nextFloat()
        }
    }

    fun start() { if (!anim.isRunning) anim.start() }
    fun stop() { anim.cancel() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); start() }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        cx = w / 2f; cy = h / 2f; r = min(w, h) / 2f / 1.7f
        fun a(v: Float) = Color.argb((v * 255).toInt(), 140, 175, 225)
        halo.shader = RadialGradient(cx, cy, r * 1.7f,
            intArrayOf(a(0.05f), a(0.07f), a(0.30f), a(0.12f), a(0f)),
            floatArrayOf(0f, 0.53f, 0.59f, 0.71f, 1f), Shader.TileMode.CLAMP)
        core.shader = RadialGradient(cx, cy, r * 0.5f,
            intArrayOf(col("#FFFFFFFF"), col("#BFFFFFFF"), col("#38FFFFFF"), col("#00FFFFFF")),
            floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP)
        core2.shader = RadialGradient(cx, cy, r * 0.36f,
            intArrayOf(col("#80FFFFFF"), col("#00FFFFFF")), null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        if (r <= 0f) return
        val t = (SystemClock.uptimeMillis() % 600000L) / 1000f
        canvas.drawCircle(cx, cy, r * 1.7f, halo)

        val ay = t * 0.6f; val ax = t * 0.33f
        val cyA = cos(ay); val syA = sin(ay); val cxA = cos(ax); val sxA = sin(ax)
        java.util.Arrays.fill(counts, 0)
        for (i in 0 until n) {
            val x1 = px[i] * cyA + pz[i] * syA
            val z1 = -px[i] * syA + pz[i] * cyA
            val y2 = py[i] * cxA - z1 * sxA
            val z2 = py[i] * sxA + z1 * cxA
            val rp = sqrt(x1 * x1 + y2 * y2)
            if (rp < 0.02f) continue
            val dx = x1 / rp; val dy = y2 / rp
            val tw = 0.5f + 0.5f * sin(ph[i] + t * (4f + 5f * lenR[i]))
            val al = (0.30f + 0.70f * tw) * (if (z2 > 0f) 1f else 0.35f)
            val lvl = (al * 3.999f).toInt()
            val b = lvl + (if (wd[i] > 0.5f) 4 else 0)
            val r0 = r * rp * 0.97f
            var r1 = min(r, r0 + r * (0.03f + 0.085f * rp * lenR[i]))
            if (r1 - r0 < 0.03f * r) r1 = r0 + 0.03f * r
            val k = counts[b] * 4
            val arr = buckets[b]
            arr[k] = cx + dx * r0; arr[k + 1] = cy + dy * r0
            arr[k + 2] = cx + dx * r1; arr[k + 3] = cy + dy * r1
            counts[b]++
        }
        for (pass in 0..1) {                                   // pass 0 = soft bloom, pass 1 = crisp streaks
            for (b in 0 until 8) {
                val cnt = counts[b]
                if (cnt == 0) continue
                val baseAlpha = 70 + (b % 4) * 62
                val thick = if (b >= 4) r * 0.026f else r * 0.014f
                line.strokeWidth = if (pass == 0) thick * 3f else thick
                line.alpha = if (pass == 0) (baseAlpha * 0.22f).toInt() else min(255, baseAlpha)
                canvas.drawLines(buckets[b], 0, cnt * 4, line)
            }
        }
        canvas.drawCircle(cx, cy, r * 0.5f, core)
        canvas.save()
        canvas.translate(0.10f * r * sin(t * 2f), 0.08f * r * cos(t * 1.7f))
        canvas.drawCircle(cx, cy, r * 0.36f, core2)
        canvas.restore()
    }
}

/** Light grey text with a bright wave sweeping left to right across the letters (about 1s sweep, 1s rest). */
class ShimmerTextView(c: Context) : View(c) {
    var text: String = ""
        set(v) { field = v; requestLayout(); invalidate() }
    private val dim = col("#4A4F58")
    private val bright = col("#E1E6F0")
    private var phase = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 17f, c.resources.displayMetrics)
        color = dim
    }
    private val anim: ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; postInvalidateOnAnimation() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (!anim.isRunning) anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val fm = p.fontMetrics
        setMeasuredDimension(Math.ceil(p.measureText(text).toDouble()).toInt() + 2,
            Math.ceil((fm.descent - fm.ascent).toDouble()).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val tw = p.measureText(text)
        val f = phase / 0.5f
        if (f <= 1f) {
            val center = (-0.25f + 1.5f * f) * tw
            p.shader = LinearGradient(center - 0.3f * tw, 0f, center + 0.3f * tw, 0f,
                intArrayOf(dim, bright, dim), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        } else {
            p.shader = null; p.color = dim
        }
        canvas.drawText(text, 0f, -p.fontMetrics.ascent, p)
    }
}

/** The "Calculating." / "Analyzing." / "Opening WhatsApp." status: glowing particle sphere + shimmering label, floating bottom-left. */
class StatusOverlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var label: ShimmerTextView? = null
    private var hideRunnable: Runnable? = null

    fun show(msg: String) {
        if (!Settings.canDrawOverlays(ctx)) return
        hideRunnable?.let { handler.removeCallbacks(it) }; hideRunnable = null
        val existing = root
        if (existing != null) {
            label?.text = msg
            existing.animate().cancel(); existing.alpha = 1f
            return
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(4), 0, ctx.dp(26), 0)
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(34).toFloat(); setColor(col("#F00C0D10")); setStroke(ctx.dp(1), col("#FF1A1C21"))
            }
            alpha = 0f
        }
        card.addView(ParticleOrbView(ctx), LinearLayout.LayoutParams(ctx.dp(88), ctx.dp(88)))
        val tv = ShimmerTextView(ctx)
        tv.text = msg
        card.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = ctx.dp(14); y = ctx.dp(110)
        }
        try { wm.addView(card, lp) } catch (e: Exception) { return }
        root = card; label = tv
        card.animate().alpha(1f).setDuration(220).start()
    }

    fun hide(delayMs: Long = 0L) {
        hideRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            val v = root ?: return@Runnable
            v.animate().alpha(0f).setDuration(220).withEndAction { remove() }.start()
        }
        hideRunnable = r
        handler.postDelayed(r, delayMs)
    }

    private fun remove() {
        root?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        root = null; label = null
    }

    fun cancel() {
        handler.removeCallbacksAndMessages(null)
        root?.animate()?.cancel()
        remove()
    }
}
