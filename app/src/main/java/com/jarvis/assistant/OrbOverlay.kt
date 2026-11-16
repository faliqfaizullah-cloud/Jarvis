package com.jarvis.assistant

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout

/** Full-screen glossy blue glowing sphere that appears, then bursts open as the app launches. */
class OrbOverlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var orbView: OrbView? = null

    /** Plays the animation and calls onPeak() at the moment the app should open. */
    fun play(onPeak: () -> Unit) {
        if (root != null || !Settings.canDrawOverlays(ctx)) { onPeak(); return }
        val dim = View(ctx).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
        val orb = OrbView(ctx).apply {
            glossy = true; active = true; setSpinDuration(1800)
            alpha = 0f; scaleX = 0.2f; scaleY = 0.2f
        }
        val size = ctx.dp(260)
        val r = FrameLayout(ctx)
        r.addView(dim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        r.addView(orb, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        try { wm.addView(r, lp) } catch (e: Exception) { onPeak(); return }
        root = r; orbView = orb
        orb.start()
        dim.animate().alpha(0.72f).setDuration(300).start()
        orb.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(520)
            .setInterpolator(OvershootInterpolator(1.4f)).start()
        handler.postDelayed({
            onPeak()
            orb.animate().scaleX(7f).scaleY(7f).alpha(0f).setDuration(480)
                .setInterpolator(AccelerateInterpolator()).start()
            dim.animate().alpha(0f).setDuration(480).start()
        }, 950)
        handler.postDelayed({ cancel() }, 1500)
    }

    fun cancel() {
        handler.removeCallbacksAndMessages(null)
        orbView?.stop(); orbView = null
        root?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        root = null
    }
}
