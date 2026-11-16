package com.jarvis.assistant

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class JarvisService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val ACTION_STOP = "com.jarvis.assistant.STOP"
        const val ACTION_COMMAND = "com.jarvis.assistant.COMMAND"
        const val CH = "jarvis"
        const val CH_REPLY = "jarvis_replies"
        const val MODEL = "claude-haiku-5-5"
        const val SYSTEM = "You are Jarvis, a concise, warm voice assistant on the user's Android phone. " +
            "Address the user as boss. Answer in at most three short sentences unless asked to draft or write something, " +
            "then keep it under 150 words. Plain speakable text only: no markdown, no bullet symbols, no emojis."
        @Volatile var onReply: ((String) -> Unit)? = null
    }
    private lateinit var skills: Skills
    private lateinit var overlay: OrbOverlay
    private val history = ArrayList<Pair<String, String>>()
    private var aiBusy = false
    private var appliedVoiceKey = ""

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var speaking = false
    @Volatile private var running = false
    private var awaitingUntil = 0L
    private var lastNotif = ""
    private val wakeRegex = Regex("^(hey|hi|ok|okay)\\s+")
    private val openRegex = Regex("^(please\\s+)?(open|launch|start)\\s+(.+?)(\\s+please)?$")
    private val nonAlnum = Regex("[^a-z0-9]")
    private val closeRegex = Regex("^(please\\s+)?(close|quit|exit|kill)(\\s+(.+?))?(\\s+please)?$")
    private val itRegex = Regex("^(it|this|that|this app|that app|the app|app|current app|the current app)$")
    private var lastOpened: String? = null

    // Cached app list: normalized label -> package (rebuilt every 5 min or on a miss)
    @Volatile private var appCache: List<Pair<String, String>> = emptyList()
    @Volatile private var cacheTime = 0L

    private val recIntent by lazy {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
        }
    }

    private fun prefs() = getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        skills = Skills(this)
        overlay = OrbOverlay(this)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH, "Jarvis", NotificationManager.IMPORTANCE_LOW))
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH_REPLY, "Jarvis replies", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Jarvis's answers to your commands"
                enableVibration(true)
            })
        tts = TextToSpeech(this, this)
        Thread { loadApps() }.start()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            applyVoice()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onError(id: String?) { speaking = false; listen(100) }
                override fun onDone(id: String?) { speaking = false; listen(100) }
            })
            ttsReady = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running = false
            overlay.cancel()
            handler.removeCallbacksAndMessages(null)
            resetRecognizer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_COMMAND) {
            val t = intent?.getStringExtra("text")?.lowercase(Locale.getDefault())?.trim().orEmpty()
                .replace(Regex("^(hey\\s+)?jarvis[,\\s]*"), "")
            if (running && t.isNotEmpty()) { updateNotif("Typed: $t"); process(t) }
            return START_STICKY
        }
        ServiceCompat.startForeground(this, 1, notif("Listening for \"Hey Jarvis\"…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (!running) { running = true; listen(0) }
        return START_STICKY
    }

    private fun notif(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH)
            .setContentTitle("Jarvis is online")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotif(t: String) {
        if (t == lastNotif) return
        lastNotif = t
        getSystemService(NotificationManager::class.java).notify(1, notif(t))
    }

    // ---------- Voice packs ----------
    private fun applyVoice() {
        val t = tts ?: return
        val pack = VoicePacks.get(prefs().getInt("voice_pack", 0))
        val vn = prefs().getString("voice_name", "") ?: ""
        val key = pack.name + "|" + vn
        if (key == appliedVoiceKey) return
        VoicePacks.apply(t, pack, vn)
        appliedVoiceKey = key
    }

    private fun setPack(i: Int) { prefs().edit().putInt("voice_pack", i).putString("voice_name", "").apply() }

    private fun voiceCommand(cmd: String): String? {
        if (cmd == "next voice" || cmd == "change voice" || cmd == "change your voice") {
            val i = (prefs().getInt("voice_pack", 0) + 1) % VoicePacks.all.size
            setPack(i); return "Voice changed to ${VoicePacks.get(i).name}, boss."
        }
        val m = Regex("^(?:(?:change|switch|set)(?: your| the)? voice(?: to)? (.+)|use (?:the )?(.+) voice)$").matchEntire(cmd)
            ?: return null
        val want = m.groupValues[1].ifEmpty { m.groupValues[2] }.trim().removeSuffix(" voice")
        val i = VoicePacks.all.indexOfFirst { want.contains(it.name.lowercase()) || it.name.lowercase().contains(want) }
        if (i < 0) return "I don't know a voice called $want, boss. Try " + VoicePacks.all.joinToString(", ") { it.name } + "."
        setPack(i); return "Voice changed to ${VoicePacks.get(i).name}, boss."
    }

    // Normal (heads-up, with sound) notification for each reply. Skipped while the app is on screen.
    private fun postReply(text: String) {
        if (onReply != null) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CH_REPLY)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setOnlyAlertOnce(false)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        getSystemService(NotificationManager::class.java).notify(2, n)
    }

    // ---------- Listening (single reused recognizer = faster restarts) ----------
    private val startRunnable = Runnable {
        if (!running || speaking) return@Runnable
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return@Runnable
        try { ensureRecognizer().startListening(recIntent) } catch (e: Exception) { resetRecognizer(); listen(1000) }
    }

    private fun listen(delay: Long = 150) {
        handler.removeCallbacks(startRunnable)
        handler.postDelayed(startRunnable, delay)
    }

    private fun ensureRecognizer(): SpeechRecognizer {
        recognizer?.let { return it }
        val r: SpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        r.setRecognitionListener(listener)
        recognizer = r
        return r
    }

    private fun resetRecognizer() { recognizer?.destroy(); recognizer = null }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
            val heard = list.firstOrNull { it.contains("jarvis", true) } ?: list.firstOrNull()
            if (heard != null) handle(heard)
            if (!speaking) listen(100)
        }
        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> return
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listen(100)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> { resetRecognizer(); listen(1500) }
                else -> { resetRecognizer(); listen(800) }
            }
        }
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(r: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(p: Bundle?) {}
        override fun onEvent(t: Int, p: Bundle?) {}
    }

    // ---------- Commands ----------
    private fun handle(raw: String) {
        val text = raw.lowercase(Locale.getDefault()).trim()
        val awaiting = SystemClock.elapsedRealtime() < awaitingUntil
        if (!text.contains("jarvis") && !awaiting) return
        val cmd = text.substringAfter("jarvis", text)
            .replace(wakeRegex, "")
            .replace(Regex("^[,.\\s]+"), "")
            .trim()
        awaitingUntil = 0
        updateNotif("Heard: $raw")
        process(cmd)
    }

    private fun process(cmd: String) {
        if (cmd.isEmpty()) {
            awaitingUntil = SystemClock.elapsedRealtime() + 8000
            reply("Yes boss, I'm listening."); return
        }
        voiceCommand(cmd)?.let { reply(it); return }
        skills.tryHandle(cmd)?.let { reply(it); return }
        val open = openRegex.find(cmd)
        val close = closeRegex.find(cmd)
        when {
            open != null -> openApp(open.groupValues[3])
            close != null -> closeApp(close.groupValues[4].ifEmpty { "it" })
            Regex("^(what's the time|what is the time|what time is it|tell me the time|the time|time)$").matches(cmd) -> reply("It's " + SimpleDateFormat("h:mm a", Locale.US).format(Date()) + ", boss.")
            Regex("^(what's the date|what is the date|what's today's date|what day is it|what day is it today|the date|date)$").matches(cmd) ->
                reply("Today is " + SimpleDateFormat("EEEE, MMMM d", Locale.US).format(Date()) + ", boss.")
            cmd.startsWith("how are you") -> reply("Fully operational and ready to work, boss.")
            cmd.startsWith("thank") -> reply("Anytime, boss.")
            else -> askAi(cmd)
        }
    }

    private fun norm(s: String) = s.lowercase().replace(nonAlnum, "")

    private fun loadApps() {
        val pm = packageManager
        appCache = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { norm(it.loadLabel(pm).toString()) to it.activityInfo.packageName }
        cacheTime = SystemClock.elapsedRealtime()
    }

    private fun findPackage(q: String): String? {
        val apps = appCache
        return apps.firstOrNull { it.first == q }?.second
            ?: apps.firstOrNull { q.length >= 3 && it.first.contains(q) }?.second
            ?: apps.firstOrNull { it.first.length >= 3 && q.contains(it.first) }?.second
    }

    private fun openApp(name: String) {
        val q = norm(name)
        if (appCache.isEmpty() || SystemClock.elapsedRealtime() - cacheTime > 300_000) loadApps()
        var pkg = findPackage(q)
        if (pkg == null && SystemClock.elapsedRealtime() - cacheTime > 10_000) { loadApps(); pkg = findPackage(q) }
        val launch = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
        if (launch == null) { reply("Sorry boss, I couldn't find $name on your phone."); return }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val phrases = listOf("Alright boss, I opened it up for you.",
            "Done, boss. It's open.", "Right away, boss. Opening it now.")
        // Glossy glowing sphere animation, then the app opens at its peak.
        overlay.play {
            try {
                startActivity(launch)
                lastOpened = pkg
                reply(phrases.random())
            } catch (e: Exception) {
                reply("I couldn't open it, boss. Please allow display over other apps in my settings.")
            }
        }
    }

    // ---------- AI brain (Claude API) ----------
    private fun askAi(q: String) {
        val key = prefs().getString("api_key", "")?.trim().orEmpty()
        if (key.isEmpty()) {
            reply("I need an AI key to answer that, boss. Add it in my settings."); return
        }
        if (aiBusy) { reply("One moment, boss. I'm still thinking."); return }
        aiBusy = true
        updateNotif("Thinking…"); onReply?.invoke("Thinking…")
        val past = history.toList()
        Thread {
            var ok = false
            val ans = try {
                val msgs = JSONArray()
                for ((role, t) in past) msgs.put(JSONObject().put("role", role).put("content", t))
                msgs.put(JSONObject().put("role", "user").put("content", q))
                val body = JSONObject().put("model", MODEL).put("max_tokens", 500)
                    .put("system", SYSTEM).put("messages", msgs)
                val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000; conn.readTimeout = 40000; conn.doOutput = true
                conn.setRequestProperty("content-type", "application/json")
                conn.setRequestProperty("x-api-key", key)
                conn.setRequestProperty("anthropic-version", "2023-06-01")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
                when {
                    code == 401 -> "Your AI key doesn't look right, boss. Please check it in my settings."
                    code == 429 -> "I'm being rate limited right now, boss. Try again in a moment."
                    code !in 200..299 -> "My AI brain returned an error, boss."
                    else -> {
                        val arr = JSONObject(text).getJSONArray("content")
                        val sb = StringBuilder()
                        for (i in 0 until arr.length()) {
                            val b = arr.getJSONObject(i)
                            if (b.optString("type") == "text") sb.append(b.optString("text"))
                        }
                        ok = sb.isNotBlank()
                        if (ok) sb.toString().trim() else "I don't have an answer for that, boss."
                    }
                }
            } catch (e: Exception) {
                "I couldn't reach my brain, boss. Please check your internet."
            }
            handler.post {
                aiBusy = false
                if (!running) return@post
                if (ok) {
                    history.add("user" to q); history.add("assistant" to ans)
                    while (history.size > 8) history.removeAt(0)
                    skills.lastAnswer = ans
                }
                reply(ans)
            }
        }.start()
    }

    private fun closeApp(name: String) {
        val target = name.trim()
        if (appCache.isEmpty()) loadApps()
        val pkg = if (itRegex.matches(target)) lastOpened else findPackage(norm(target))
        if (pkg == null) { reply("Sorry boss, which app should I close?"); return }
        if (pkg == packageName) { reply("I can't close myself, boss. Use the wave button to stop me."); return }
        // Android only lets apps kill background processes, so go Home first, then kill.
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { }
        val am = getSystemService(ActivityManager::class.java)
        handler.postDelayed({ try { am.killBackgroundProcesses(pkg) } catch (e: Exception) { } }, 700)
        handler.postDelayed({ try { am.killBackgroundProcesses(pkg) } catch (e: Exception) { } }, 2000)
        if (pkg == lastOpened) lastOpened = null
        reply(listOf("Alright boss, I closed it.", "Done, boss. It's closed.", "Right away, boss. Closing it now.").random())
    }

    private fun reply(text: String) {
        postReply(text)
        onReply?.invoke(text)
        if (!prefs().getBoolean("voice_on", true) || !ttsReady) return
        speaking = true
        handler.removeCallbacks(startRunnable)
        recognizer?.cancel()
        applyVoice()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_reply")
    }

    override fun onDestroy() {
        running = false
        overlay.cancel()
        handler.removeCallbacksAndMessages(null)
        resetRecognizer()
        tts?.shutdown()
        super.onDestroy()
    }
}
