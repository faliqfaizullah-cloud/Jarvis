package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : Activity() {
    private lateinit var orb: OrbView
    private lateinit var wave: WaveView
    private lateinit var input: EditText

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun prefs() = getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    private fun isOn() = prefs().getBoolean("enabled", false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val ctx = this

        val root = FrameLayout(ctx)
        root.addView(BackgroundView(ctx), FrameLayout.LayoutParams(MATCH, MATCH))
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

        // ---- top bar with glowing settings button ----
        val bar = FrameLayout(ctx)
        content.addView(bar, LinearLayout.LayoutParams(MATCH, dp(64)))
        val gear = GlowBox(ctx, dp(12), dp(20).toFloat(), col("#553A8CFF"))
        val gearBtn = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(col("#33FFFFFF")); setStroke(dp(1), col("#55FFFFFF"))
            }
            addView(SlidersView(ctx), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            setOnClickListener { showSettings() }
        }
        gear.addView(gearBtn, FrameLayout.LayoutParams(dp(40), dp(40)))
        bar.addView(gear, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(10) })

        // ---- orb + greeting + headline ----
        content.addView(Space(ctx), LinearLayout.LayoutParams(0, 0, 0.8f))
        orb = OrbView(ctx)
        content.addView(orb, LinearLayout.LayoutParams(dp(260), dp(260)))
        content.addView(TextView(ctx).apply {
            text = "Hello boss"; textSize = 16f; setTextColor(col("#A9B4C8")); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })
        content.addView(TextView(ctx).apply {
            text = "How can I help\nyou today?"; textSize = 32f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(0f, 1.05f)
            setShadowLayer(28f, 0f, 0f, col("#885AA0FF"))
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(14) })
        content.addView(Space(ctx), LinearLayout.LayoutParams(0, 0, 1.3f))

        // ---- glowing glass "Ask anything..." pill ----
        val box = GlowBox(ctx, dp(22), dp(30).toFloat(), col("#663A8CFF"))
        val pill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), 0, dp(18), 0)
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(col("#884A86E0"), col("#AA9CCBFF"))).apply {
                cornerRadius = dp(30).toFloat(); setStroke(dp(1), col("#66FFFFFF"))
            }
        }
        input = EditText(ctx).apply {
            hint = "Ask anything…"
            setHintTextColor(col("#CCDCEBFF")); setTextColor(Color.WHITE); textSize = 17f
            background = null
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(0, 0, 0, 0)
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                    sendTyped(v.text.toString()); v.text = ""; true
                } else false
            }
        }
        pill.addView(input, LinearLayout.LayoutParams(0, WRAP, 1f))
        wave = WaveView(ctx)
        wave.setOnClickListener { setJarvis(!isOn()) }
        pill.addView(wave, LinearLayout.LayoutParams(dp(30), dp(26)))
        box.addView(pill, FrameLayout.LayoutParams(MATCH, dp(58)))
        content.addView(box, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(4) })

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            content.setPadding(0, sb.top, 0, maxOf(sb.bottom, ime.bottom))
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    // ---- typed commands go to the running service ----
    private fun sendTyped(t: String) {
        val text = t.trim()
        if (text.isEmpty()) return
        if (!isOn()) { Toast.makeText(this, "Tap the wave to wake Jarvis first", Toast.LENGTH_SHORT).show(); return }
        startService(Intent(this, JarvisService::class.java)
            .setAction(JarvisService.ACTION_COMMAND).putExtra("text", text))
    }

    // ---- start / stop ----
    private fun setJarvis(on: Boolean) {
        if (!on) {
            prefs().edit().putBoolean("enabled", false).apply()
            startService(Intent(this, JarvisService::class.java).setAction(JarvisService.ACTION_STOP))
            refresh(); return
        }
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { requestPermissions(missing.toTypedArray(), 1); return }
        startJarvis()
    }

    private fun startJarvis() {
        prefs().edit().putBoolean("enabled", true).apply()
        startForegroundService(Intent(this, JarvisService::class.java))
        refresh()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startJarvis()
        else Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_LONG).show()
    }

    private fun refresh() {
        val on = isOn()
        orb.active = on
        wave.active = on
    }

    override fun onResume() { super.onResume(); orb.start(); wave.start(); refresh() }
    override fun onPause() { orb.stop(); wave.stop(); super.onPause() }

    // ---- settings sheet ----
    private fun showSettings() {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(22))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(col("#0C2250"), col("#050B1A"))).apply {
                cornerRadius = dp(28).toFloat(); setStroke(dp(1), col("#44FFFFFF"))
            }
        }
        panel.addView(TextView(this).apply {
            text = "Settings"; textSize = 22f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setShadowLayer(20f, 0f, 0f, col("#885AA0FF"))
        })
        panel.addView(switchRow("Always-on Jarvis (listening)", isOn()) { setJarvis(it); if (it && !isOn()) d.dismiss() })
        panel.addView(switchRow("Jarvis voice replies", prefs().getBoolean("voice_on", true)) {
            prefs().edit().putBoolean("voice_on", it).apply()
        })
        panel.addView(glassButton("Allow display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        panel.addView(glassButton("Disable battery optimization") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        })
        panel.addView(glassButton("Close") { d.dismiss() })
        d.setContentView(panel)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout((resources.displayMetrics.widthPixels * 0.9f).toInt(), WRAP)
        d.show()
    }

    private fun switchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch =
        Switch(this).apply {
            text = label; setTextColor(Color.WHITE); textSize = 16f
            isChecked = checked
            setPadding(0, dp(14), 0, dp(6))
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }

    private fun glassButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat(); setColor(col("#33FFFFFF")); setStroke(dp(1), col("#44FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }
            setOnClickListener { onClick() }
        }
}
