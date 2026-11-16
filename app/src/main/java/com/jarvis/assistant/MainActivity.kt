package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var toggle: Button

    private fun prefs() = getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(p, p * 2, p, p)
        }
        fun text(t: String, size: Float, color: Int) = TextView(this).apply {
            this.text = t; textSize = size; setTextColor(color); gravity = Gravity.CENTER
            setPadding(0, p / 2, 0, p / 2)
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            layoutParams = LinearLayout.LayoutParams(p * 6, p * 6)
        }
        root.addView(logo)
        root.addView(text("J.A.R.V.I.S", 30f, Color.parseColor("#7DD3FC")))
        status = text("", 16f, Color.WHITE)
        root.addView(status)

        toggle = Button(this).apply { setOnClickListener { toggleJarvis() } }
        root.addView(toggle)

        val voice = Switch(this).apply {
            text = "Jarvis voice replies"
            setTextColor(Color.WHITE)
            isChecked = prefs().getBoolean("voice_on", true)
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean("voice_on", on).apply() }
            setPadding(0, p, 0, p)
        }
        root.addView(voice)

        root.addView(Button(this).apply {
            text = "Allow display over other apps (needed to open apps)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })
        root.addView(Button(this).apply {
            text = "Disable battery optimization (keeps Jarvis alive)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        })
        root.addView(text("Say: \"Hey Jarvis, open WhatsApp\"", 14f, Color.parseColor("#94A3B8")))
        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    private fun toggleJarvis() {
        if (prefs().getBoolean("enabled", false)) {
            prefs().edit().putBoolean("enabled", false).apply()
            startService(Intent(this, JarvisService::class.java).setAction(JarvisService.ACTION_STOP))
        } else {
            val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
            val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) { requestPermissions(missing.toTypedArray(), 1); return }
            startJarvis()
        }
        refresh()
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
        val on = prefs().getBoolean("enabled", false)
        status.text = if (on) "Jarvis is online and listening" else "Jarvis is offline"
        toggle.text = if (on) "Stop Jarvis" else "Start Jarvis"
    }

    override fun onResume() { super.onResume(); refresh() }
}
