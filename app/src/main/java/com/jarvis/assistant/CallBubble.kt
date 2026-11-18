package com.jarvis.assistant

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import kotlin.math.abs

/**
 * While you are on a call: a draggable glowing Jarvis bubble floats over the call screen.
 * Tap it to open a small command panel (type a command or tap a chip). Replies show in the panel.
 */
class CallBubble(private val ctx: Context, private val onCommand: (String) -> Unit) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    private var bubble: OrbView? = null
    private var panel: View? = null
    private var replyView: TextView? = null
    private var input: EditText? = null
    private var lastX = 0
    private var lastY = 0

    fun show() {
        if (bubble != null || panel != null || !Settings.canDrawOverlays(ctx)) return
        val size = ctx.dp(84)
        val orb = OrbView(ctx).apply { glossy = true; active = true; radiusFactor = 0.28f; setSpinDuration(5000) }
        val lp = WindowManager.LayoutParams(size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = lastX; y = if (lastY == 0) ctx.dp(180) else lastY
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        orb.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 14 || abs(dy) > 14) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        lastX = lp.x; lastY = lp.y
                        try { wm.updateViewLayout(v, lp) } catch (ex: Exception) { }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) { Haptics.click(ctx); openPanel() }; true }
                else -> false
            }
        }
        try { wm.addView(orb, lp) } catch (ex: Exception) { return }
        orb.start()
        bubble = orb
        Haptics.tick(ctx)
    }

    private fun openPanel() {
        bubble?.let { try { wm.removeView(it) } catch (e: Exception) { }; it.stop() }
        bubble = null
        val metrics = ctx.resources.displayMetrics
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(col("#F00C2250"), col("#F0050B1A"))).apply {
                cornerRadius = ctx.dp(24).toFloat(); setStroke(ctx.dp(1), col("#55FFFFFF"))
            }
        }
        val title = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title.addView(TextView(ctx).apply { text = "Jarvis · on a call"; textSize = 14f; setTextColor(col("#7DD3FC")) },
            LinearLayout.LayoutParams(0, ViewGroupWrap, 1f))
        title.addView(TextView(ctx).apply {
            text = "✕"; textSize = 18f; setTextColor(Color.WHITE); setPadding(ctx.dp(12), 0, 0, 0)
            setOnClickListener { Haptics.tick(ctx); closePanel() }
        })
        box.addView(title)
        replyView = TextView(ctx).apply {
            text = "Ready, boss. Type a command."; textSize = 16f; setTextColor(Color.WHITE)
            setPadding(0, ctx.dp(8), 0, ctx.dp(8)); maxLines = 6
        }
        box.addView(replyView)

        val field = EditText(ctx).apply {
            hint = "Type a command…"; setHintTextColor(col("#99B8D6FF")); setTextColor(Color.WHITE); textSize = 16f
            background = null; setSingleLine(true); imeOptions = EditorInfo.IME_ACTION_SEND
        }
        input = field
        fun send(t: String) {
            val s = t.trim()
            if (s.isEmpty()) return
            Haptics.click(ctx)
            replyView?.text = "…"
            field.setText("")
            onCommand(s)
        }
        field.setOnEditorActionListener { v, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(v.text.toString()); true } else false }

        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("⏰ 5 min timer" to "set a timer for 5 minutes", "📝 Note" to "NOTE", "🔦 Flashlight" to "flashlight",
            "🔋 Battery" to "battery level", "🧠 Memory" to "what do you remember").forEach { (label, cmd) ->
            chips.addView(TextView(ctx).apply {
                text = label; textSize = 13f; setTextColor(Color.WHITE)
                setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = ctx.dp(18).toFloat(); setColor(col("#26FFFFFF")); setStroke(ctx.dp(1), col("#44FFFFFF"))
                }
                setOnClickListener {
                    if (cmd == "NOTE") { field.setText("take a note: "); field.setSelection(field.text.length); focusInput() }
                    else send(cmd)
                }
            }, LinearLayout.LayoutParams(ViewGroupWrap, ViewGroupWrap).apply { marginEnd = ctx.dp(8) })
        }
        box.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(14), 0, ctx.dp(6), 0)
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(22).toFloat(); setColor(col("#33FFFFFF")); setStroke(ctx.dp(1), col("#44FFFFFF"))
            }
        }
        row.addView(field, LinearLayout.LayoutParams(0, ctx.dp(44), 1f))
        row.addView(TextView(ctx).apply {
            text = "Send"; textSize = 15f; setTextColor(col("#7DD3FC")); setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
            setOnClickListener { send(field.text.toString()) }
        })
        box.addView(row, LinearLayout.LayoutParams(ViewGroupMatch, ViewGroupWrap).apply { topMargin = ctx.dp(10) })

        val lp = WindowManager.LayoutParams((metrics.widthPixels * 0.94f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT, type,
            0, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = ctx.dp(48)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        try { wm.addView(box, lp) } catch (ex: Exception) { panel = null; show(); return }
        panel = box
        focusInput()
    }

    private fun focusInput() {
        val f = input ?: return
        f.postDelayed({
            f.requestFocus()
            try { ctx.getSystemService(InputMethodManager::class.java).showSoftInput(f, 0) } catch (e: Exception) { }
        }, 150)
    }

    private fun closePanel() {
        panel?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        panel = null; replyView = null; input = null
        show()
    }

    fun showReply(t: String) { replyView?.text = t }

    fun hide() {
        bubble?.let { try { wm.removeView(it) } catch (e: Exception) { }; it.stop() }
        panel?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        bubble = null; panel = null; replyView = null; input = null
    }

    private companion object {
        const val ViewGroupWrap = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        const val ViewGroupMatch = android.view.ViewGroup.LayoutParams.MATCH_PARENT
    }
}
