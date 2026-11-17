package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import android.os.Handler
import android.os.Looper
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : Activity() {
    private lateinit var orb: OrbView
    private lateinit var wave: WaveView
    private lateinit var input: EditText
    private var previewTts: TextToSpeech? = null
    private var previewReady = false
    private var variantTts: TextToSpeech? = null
    private lateinit var greetingView: TextView
    private lateinit var headlineView: TextView
    private lateinit var answerCard: LinearLayout
    private lateinit var answerText: TextView

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
            setOnClickListener { Haptics.tick(ctx); showSettings() }
        }
        gear.addView(gearBtn, FrameLayout.LayoutParams(dp(40), dp(40)))
        bar.addView(gear, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(10) })

        // ---- orb + greeting + headline ----
        content.addView(Space(ctx), LinearLayout.LayoutParams(0, 0, 0.8f))
        orb = OrbView(ctx)
        orb.setOnClickListener { Haptics.heavy(ctx); setJarvis(!isOn()) }
        content.addView(orb, LinearLayout.LayoutParams(dp(260), dp(260)))
        greetingView = TextView(ctx).apply {
            text = "Hello boss"; textSize = 16f; setTextColor(col("#A9B4C8")); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        content.addView(greetingView, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })
        headlineView = TextView(ctx).apply {
            text = "How can I help\nyou today?"; textSize = 32f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(0f, 1.05f)
            setShadowLayer(28f, 0f, 0f, col("#885AA0FF"))
        }
        content.addView(headlineView, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(14) })

        // ---- answer card (shows Jarvis replies; tap to dismiss) ----
        answerText = TextView(ctx).apply { textSize = 18f; setTextColor(Color.WHITE); setLineSpacing(0f, 1.1f) }
        val answerScroll = MaxHeightScroll(ctx, dp(220)).apply { isVerticalScrollBarEnabled = false; addView(answerText) }
        answerCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(col("#55123068"), col("#7728559E"))).apply {
                cornerRadius = dp(26).toFloat(); setStroke(dp(1), col("#55FFFFFF"))
            }
            addView(answerScroll)
            setOnClickListener { Haptics.tick(ctx); hideAnswer() }
        }
        content.addView(answerCard, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(24), dp(10), dp(24), dp(6)) })
        content.addView(Space(ctx), LinearLayout.LayoutParams(0, 0, 1.3f))

        // ---- quick-action chips (tap to run) ----
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(20), 0, dp(12), 0) }
        listOf("🔋 Battery" to "battery level", "⏰ 5 min timer" to "set a timer for 5 minutes",
            "📝 Notes" to "read my notes", "☀️ Weather" to "weather", "🌅 Briefing" to "good morning",
            "🔦 Flashlight" to "flashlight").forEach { (label, cmd) ->
            chips.addView(TextView(ctx).apply {
                text = label; textSize = 14f; setTextColor(Color.WHITE)
                setPadding(dp(16), dp(9), dp(16), dp(9))
                background = GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat(); setColor(col("#26FFFFFF")); setStroke(dp(1), col("#44FFFFFF"))
                }
                setOnClickListener { Haptics.click(ctx); sendTyped(cmd) }
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })
        }
        content.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chips) },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(2) })

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
        wave.setOnClickListener { Haptics.heavy(ctx); setJarvis(!isOn()) }
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
        if (intent?.getBooleanExtra("check_update", false) == true) checkForUpdates(true)
        else if (System.currentTimeMillis() - prefs().getLong("last_update_check", 0L) > 6 * 3600 * 1000L) {
            prefs().edit().putLong("last_update_check", System.currentTimeMillis()).apply()
            checkForUpdates(false)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("check_update", false) == true) checkForUpdates(true)
    }

    private val ui = Handler(Looper.getMainLooper())
    private val resetGreeting = Runnable { greetingView.text = "Hello boss" }

    private fun showHeard(t: String) {
        if (answerCard.visibility == View.VISIBLE) return
        greetingView.text = "“" + t.trim() + "”"
        ui.removeCallbacks(resetGreeting); ui.postDelayed(resetGreeting, 4000)
    }

    private fun showAnswer(t: String) {
        orb.setSpinDuration(if (t == "Thinking…") 2500 else 9000)
        answerText.text = t
        answerCard.visibility = View.VISIBLE
        greetingView.visibility = View.GONE
        headlineView.visibility = View.GONE
    }

    private fun hideAnswer() {
        greetingView.text = "Hello boss"
        answerCard.visibility = View.GONE
        greetingView.visibility = View.VISIBLE
        headlineView.visibility = View.VISIBLE
    }

    // ---- typed commands go to the running service ----
    private fun sendTyped(t: String) {
        val text = t.trim()
        if (text.isEmpty()) return
        Haptics.click(this)
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
        if (code != 1) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startJarvis()
        else Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_LONG).show()
    }

    private fun refresh() {
        val on = isOn()
        orb.active = on
        wave.active = on
    }

    override fun onResume() {
        super.onResume(); orb.start(); wave.start(); refresh()
        JarvisService.onReply = { t: String -> showAnswer(t) }
        JarvisService.onHeard = { t: String -> showHeard(t) }
        JarvisService.onLevel = { v: Float -> orb.pushLevel(v) }
    }
    override fun onPause() { JarvisService.onReply = null; JarvisService.onHeard = null; JarvisService.onLevel = null; orb.stop(); wave.stop(); super.onPause() }
    override fun onDestroy() { previewTts?.shutdown(); variantTts?.shutdown(); super.onDestroy() }

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
        panel.addView(switchRow("Haptic feedback", prefs().getBoolean("haptics_on", true)) {
            prefs().edit().putBoolean("haptics_on", it).apply()
            if (it) Haptics.click(this)
        })
        panel.addView(glassButton("Voice pack: " + VoicePacks.get(prefs().getInt("voice_pack", 0)).name) { d.dismiss(); showVoicePacks() })
        panel.addView(glassButton("Check for updates (v" + Updater.installedVersion(this) + ")") { d.dismiss(); checkForUpdates(true) })
        panel.addView(glassButton("Set AI key (Claude)") { showKeyDialog() })
        panel.addView(glassButton("Allow contacts (call & text)") {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 2)
        })
        panel.addView(glassButton("What can I say?") { showHelp() })
        panel.addView(glassButton("Notification settings") {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        })
        panel.addView(glassButton("Allow display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        panel.addView(glassButton("Disable battery optimization") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        })
        panel.addView(glassButton("Close") { d.dismiss() })
        val scroll = MaxHeightScroll(this, (resources.displayMetrics.heightPixels * 0.85f).toInt())
        scroll.addView(panel)
        d.setContentView(scroll)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout((resources.displayMetrics.widthPixels * 0.9f).toInt(), WRAP)
        d.show()
    }

    // ---- in-app updates ----
    private fun checkForUpdates(manual: Boolean) {
        if (manual) Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show()
        Thread {
            var info: Updater.Info? = null
            var failed = false
            try { info = Updater.check(this) } catch (e: Exception) { failed = true }
            val found = info
            val bad = failed
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (bad) {
                    if (manual) {
                        Haptics.error(this)
                        Toast.makeText(this, "Couldn't check for updates. Check your internet.", Toast.LENGTH_LONG).show()
                    }
                } else if (found == null) {
                    if (manual) Toast.makeText(this, "You're on the latest version (v" + Updater.installedVersion(this) + ")", Toast.LENGTH_LONG).show()
                } else {
                    showUpdateDialog(found)
                }
            }
        }.start()
    }

    private fun showUpdateDialog(info: Updater.Info) {
        Haptics.confirm(this)
        AlertDialog.Builder(this)
            .setTitle("Update available: v" + info.version)
            .setMessage("You have v" + Updater.installedVersion(this) + ".\n\n" + info.notes +
                "\n\nJarvis will download the new version and install it over this one. Your settings, notes and AI key are kept.")
            .setPositiveButton("Update now") { _, _ -> startUpdate(info) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun startUpdate(info: Updater.Info) {
        if (!Updater.canInstall(this)) {
            Toast.makeText(this, "Allow “Install unknown apps” for Jarvis, then tap Update again", Toast.LENGTH_LONG).show()
            Updater.openInstallPermission(this)
            return
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Downloading v" + info.version + "…")
            .setView(bar).setCancelable(false).show()
        Thread {
            try {
                val f = Updater.download(this, info.apkUrl) { p -> runOnUiThread { bar.progress = p } }
                runOnUiThread { dlg.dismiss(); Haptics.heavy(this); Updater.install(this, f) }
            } catch (e: Exception) {
                runOnUiThread {
                    dlg.dismiss(); Haptics.error(this)
                    Toast.makeText(this, "Download failed. Please try again.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ---- voice packs ----
    private fun showVoicePacks() {
        previewReady = false
        previewTts?.shutdown()
        previewTts = TextToSpeech(this) { st -> previewReady = (st == TextToSpeech.SUCCESS) }
        val sel = prefs().getInt("voice_pack", 0).coerceIn(0, VoicePacks.all.size - 1)
        val names = VoicePacks.all.map { it.name + " - " + it.desc }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Voice pack")
            .setSingleChoiceItems(names, sel) { _, which ->
                Haptics.tick(this)
                prefs().edit().putInt("voice_pack", which).putString("voice_name", "").apply()
                val t = previewTts
                if (previewReady && t != null) VoicePacks.preview(t, VoicePacks.all[which], "")
            }
            .setNeutralButton("Voices…") { _, _ -> showVariants() }
            .setPositiveButton("Done", null)
            .setOnDismissListener { previewTts?.shutdown(); previewTts = null }
            .show()
    }

    private fun showVariants() {
        variantTts?.shutdown()
        variantTts = TextToSpeech(this) { st ->
            runOnUiThread {
                val t = variantTts
                if (st != TextToSpeech.SUCCESS || t == null) {
                    Toast.makeText(this, "Text-to-speech isn't available", Toast.LENGTH_LONG).show()
                } else presentVariants(t)
            }
        }
    }

    private fun openTtsSettings() {
        try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
        catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun presentVariants(t: TextToSpeech) {
        val pack = VoicePacks.get(prefs().getInt("voice_pack", 0))
        val voices = VoicePacks.voicesFor(t, pack.locale)
        val b = AlertDialog.Builder(this)
            .setNeutralButton("Get more voices") { _, _ -> openTtsSettings() }
            .setPositiveButton("Done", null)
            .setOnDismissListener { variantTts?.shutdown(); variantTts = null }
        if (voices.isEmpty()) {
            b.setTitle(pack.name + " voices")
                .setMessage("No extra " + pack.locale.displayName + " voices are installed yet. Tap Get more voices, open your speech engine and install voice data.")
        } else {
            val labels = voices.mapIndexed { i, v -> "Voice " + (i + 1) + (if (v.isNetworkConnectionRequired) " (online)" else "") }.toTypedArray()
            val cur = voices.indexOfFirst { it.name == prefs().getString("voice_name", "") }.coerceAtLeast(0)
            b.setTitle(pack.name + " voices")
                .setSingleChoiceItems(labels, cur) { _, w ->
                    prefs().edit().putString("voice_name", voices[w].name).apply()
                    VoicePacks.preview(t, pack, voices[w].name)
                }
        }
        b.show()
    }

    private fun showKeyDialog() {
        val et = EditText(this).apply {
            hint = "Paste your Claude API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs().getString("api_key", ""))
        }
        AlertDialog.Builder(this)
            .setTitle("AI key")
            .setMessage("Get a key at console.anthropic.com. It is stored only on this phone.")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                prefs().edit().putString("api_key", et.text.toString().trim()).apply()
                Toast.makeText(this, "AI key saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("What can I say?")
            .setMessage(
                "Hey Jarvis, then:\n\n" +
                "• open / close WhatsApp\n" +
                "• set a timer for 10 minutes\n" +
                "• remind me to call Sam in 30 minutes\n" +
                "• set an alarm for 7:30 am\n" +
                "• take a note: buy milk / read my notes\n" +
                "• call Mom / text Sam saying I'm late\n" +
                "• search for best laptops\n" +
                "• navigate to the airport\n" +
                "• play lofi music\n" +
                "• flashlight on / volume up\n" +
                "• change voice to Friday / next voice\n" +
                "• check for updates\n" +
                "• battery level / weather\n" +
                "• what is 15 percent of 240\n" +
                "• good morning (daily briefing)\n" +
                "• copy that / share that (last AI answer)\n" +
                "• anything else: Jarvis asks AI (needs your AI key)")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun switchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch =
        Switch(this).apply {
            text = label; setTextColor(Color.WHITE); textSize = 16f
            isChecked = checked
            setPadding(0, dp(14), 0, dp(6))
            setOnCheckedChangeListener { _, on -> Haptics.tick(context); onChange(on) }
        }

    private fun glassButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat(); setColor(col("#33FFFFFF")); setStroke(dp(1), col("#44FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }
            setOnClickListener { Haptics.tick(context); onClick() }
        }
}
