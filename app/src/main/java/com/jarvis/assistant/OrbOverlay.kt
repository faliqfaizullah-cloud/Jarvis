package com.jarvis.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout

/**
 * Full-screen frosted-glass blur of whatever is behind, with a glossy glowing sphere that
 * swells in and bursts open as the app launches. (Real blur needs Android 12+; older phones get a frosted tint.)
 */
class OrbOverlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var orbView: OrbView? = null
    private var blurAnim: ValueAnimator? = null

    private fun blurSupported(): Boolean = Build.VERSION.SDK_INT >= 31 && try { wm.isCrossWindowBlurEnabled } catch (e: Exception) { false }

    private fun animateBlur(lp: WindowManager.LayoutParams, from: Int, to: Int, ms: Long) {
        if (Build.VERSION.SDK_INT < 31) return
        blurAnim?.cancel()
        blurAnim = ValueAnimator.ofInt(from, to).apply {
            duration = ms
            addUpdateListener {
                val v = root ?: return@addUpdateListener
                lp.blurBehindRadius = it.animatedValue as Int
                try { wm.updateViewLayout(v, lp) } catch (e: Exception) { }
            }
            start()
        }
    }

    /** Plays the animation and calls onPeak() at the moment the app should open. */
    fun play(onPeak: () -> Unit) {
        if (root != null || !Settings.canDrawOverlays(ctx)) { onPeak(); return }
        val blur = blurSupported()
        val frost = View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                if (blur) intArrayOf(col("#40E8F2FF"), col("#402A5BB0"), col("#600A1F4A"))
                else intArrayOf(col("#F0243F7A"), col("#F0122A55"), col("#F0081A40")))
            alpha = 0f
        }
        val orb = OrbView(ctx).apply {
            glossy = true; active = true; setSpinDuration(1500)
            alpha = 0f; scaleX = 0.2f; scaleY = 0.2f
        }
        val r = FrameLayout(ctx)
        r.addView(frost, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val size = ctx.dp(280)
        r.addView(orb, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        if (blur && Build.VERSION.SDK_INT >= 31) {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            lp.blurBehindRadius = 0
        }
        try { wm.addView(r, lp) } catch (e: Exception) { onPeak(); return }
        root = r; orbView = orb
        orb.start()
        if (blur) animateBlur(lp, 0, 100, 260)
        frost.animate().alpha(1f).setDuration(260).start()
        orb.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(380)
            .setInterpolator(OvershootInterpolator(1.3f)).start()
        handler.postDelayed({
            onPeak()
            orb.animate().scaleX(7f).scaleY(7f).alpha(0f).setDuration(380)
                .setInterpolator(AccelerateInterpolator()).start()
            frost.animate().alpha(0f).setDuration(380).start()
            if (blur) animateBlur(lp, 100, 0, 380)
        }, 520)
        handler.postDelayed({ cancel() }, 950)
    }

    fun cancel() {
        handler.removeCallbacksAndMessages(null)
        blurAnim?.cancel(); blurAnim = null
        orbView?.stop(); orbView = null
        root?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        root = null
    }
}
