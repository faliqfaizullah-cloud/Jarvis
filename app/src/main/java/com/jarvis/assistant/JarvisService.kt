package com.jarvis.assistant

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
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
import java.util.concurrent.atomic.AtomicInteger

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
        @Volatile var onHeard: ((String) -> Unit)? = null
        @Volatile var onLevel: ((Float) -> Unit)? = null
        @Volatile var onOverlayReply: ((String) -> Unit)? = null
        @Volatile var onCall: ((Boolean) -> Unit)? = null
        @Volatile var inCallNow = false
    }
    private lateinit var skills: Skills
    private lateinit var overlay: OrbOverlay
    private lateinit var bubble: CallBubble
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    @Volatile private var inCall = false
    private val history = ArrayList<Pair<String, String>>()
    private var aiBusy = false
    private var appliedVoiceKey = ""
    private val pending = AtomicInteger(0)
    @Volatile private var streamActive = false
    private var pendingPartial: Runnable? = null
    @Volatile private var preferOffline = true
    private var uttCounter = 0

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

    private fun recIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 650L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 450L)
        if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    private fun prefs() = getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        skills = Skills(this)
        overlay = OrbOverlay(this)
        bubble = CallBubble(this) { runTyped(it) }
        onOverlayReply = { t -> bubble.showReply(t) }
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
                override fun onError(id: String?) { finishUtterance() }
                override fun onDone(id: String?) { finishUtterance() }
            })
            ttsReady = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running = false
            overlay.cancel()
            bubble.hide()
            handler.removeCallbacksAndMessages(null)
            resetRecognizer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_COMMAND) {
            runTyped(intent.getStringExtra("text").orEmpty())
            return START_STICKY
        }
        ServiceCompat.startForeground(this, 1, notif("Listening for \"Hey Jarvis\"…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (!running) { running = true; listen(0) }
        startCallWatch()
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
        if (!running || speaking || (inCall && Build.VERSION.SDK_INT < 29)) return@Runnable
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return@Runnable
        try { ensureRecognizer().startListening(recIntent()) } catch (e: Exception) { resetRecognizer(); listen(1000) }
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
            pendingPartial?.let { handler.removeCallbacks(it) }; pendingPartial = null
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
            val heard = list.firstOrNull { it.contains("jarvis", true) } ?: list.firstOrNull()
            if (heard != null) handle(heard)
            if (!speaking) listen(50)
        }
        override fun onError(error: Int) {
            pendingPartial?.let { handler.removeCallbacks(it) }; pendingPartial = null
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> return
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listen(if (inCall) 800 else 50)
                12, 13 -> { preferOffline = false; resetRecognizer(); listen(200) }   // offline language unavailable
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> { resetRecognizer(); listen(1200) }
                else -> { resetRecognizer(); listen(if (inCall) 2000 else 600) }
            }
        }
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(r: Float) { onLevel?.invoke(((r + 2f) / 12f).coerceIn(0f, 1f)) }
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        // Act early: an exact "open <app>" / "close <app>" fires ~0.3s after you stop talking, without waiting for the final result.
        override fun onPartialResults(p: Bundle?) {
            val t = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            pendingPartial?.let { handler.removeCallbacks(it) }; pendingPartial = null
            val low = t.lowercase(Locale.getDefault()).trim()
            val awaiting = SystemClock.elapsedRealtime() < awaitingUntil
            if (!low.contains("jarvis") && !awaiting) return
            onHeard?.invoke(t)
            val cmd = low.substringAfter("jarvis", low).replace(wakeRegex, "").replace(Regex("^[,.\\s]+"), "").trim()
            if (!isQuickCommand(cmd)) return
            val r = Runnable { pendingPartial = null; recognizer?.cancel(); handle(t); if (!speaking) listen(50) }
            pendingPartial = r
            handler.postDelayed(r, 320)
        }
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
        vibrate()
        onHeard?.invoke(raw)
        updateNotif("Heard: $raw")
        process(cmd)
    }

    private fun process(cmd: String) {
        if (cmd.isEmpty()) {
            awaitingUntil = SystemClock.elapsedRealtime() + 8000
            reply("Yes boss, I'm listening."); return
        }
        if (Regex("^(check for updates?|check for an update|update (yourself|jarvis|the app)|any updates?)$").matches(cmd)) {
            reply("Checking for updates, boss.")
            try {
                startActivity(Intent(this, MainActivity::class.java).putExtra("check_update", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            } catch (e: Exception) { }
            return
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

    // ---------- On-call mode ----------
    // Detects phone calls and VoIP calls (WhatsApp, Meet, ...) through the audio mode; no extra permission needed.
    private val callPoll = object : Runnable {
        override fun run() {
            if (!running) return
            val m = audio.mode
            val now = m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION
            if (now != inCall) setInCall(now)
            handler.postDelayed(this, 2000)
        }
    }

    private fun startCallWatch() { handler.removeCallbacks(callPoll); handler.post(callPoll) }

    private fun setInCall(now: Boolean) {
        inCall = now; inCallNow = now
        if (now) {
            updateNotif("On a call · tap the bubble to give commands")
            if (prefs().getBoolean("bubble_on", true)) bubble.show()
            if (Build.VERSION.SDK_INT < 29) { handler.removeCallbacks(startRunnable); recognizer?.cancel() }
        } else {
            bubble.hide()
            updateNotif("Listening for \"Hey Jarvis\"…")
            listen(300)
        }
        onCall?.invoke(now)
    }

    /** A command typed in the app or in the call bubble (no wake word needed). */
    private fun runTyped(raw: String) {
        val t = raw.lowercase(Locale.getDefault()).trim().replace(Regex("^(hey\\s+)?jarvis[,\\s]*"), "")
        if (!running || t.isEmpty()) return
        updateNotif("Typed: $t")
        process(t)
    }

    private fun vibrate() { Haptics.click(this) }

    // An exact, unambiguous app name: safe to act on before the sentence is finished.
    private fun findExact(q: String): Boolean =
        q.isNotEmpty() && appCache.any { it.first == q } && appCache.none { it.first != q && it.first.startsWith(q) }

    private fun isQuickCommand(cmd: String): Boolean {
        val o = openRegex.find(cmd)
        if (o != null) return findExact(norm(o.groupValues[3]))
        val c = closeRegex.find(cmd)
        if (c != null) { val t = c.groupValues[4].trim(); return t.isNotEmpty() && !itRegex.matches(t) && findExact(norm(t)) }
        return false
    }

    private fun finishUtterance() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            if (!streamActive) { speaking = false; listen(60) }
        }
    }

    // Speak one chunk; the first flushes, the rest queue behind it (used while an AI answer streams in).
    private fun speakChunk(chunk: String) {
        if (!prefs().getBoolean("voice_on", true) || !ttsReady || chunk.isBlank() || (inCall && !prefs().getBoolean("speak_in_call", false))) return
        applyVoice()
        uttCounter++
        if (!speaking) {
            speaking = true
            handler.removeCallbacks(startRunnable)
            recognizer?.cancel()
            pending.set(1)
            tts?.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, "ai$uttCounter")
        } else {
            pending.incrementAndGet()
            tts?.speak(chunk, TextToSpeech.QUEUE_ADD, null, "ai$uttCounter")
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
        val target = pkg
        // Speak right away while the frosted-orb animation plays; the app opens at its peak.
        reply(listOf("Alright boss, I opened it up for you.",
            "Done, boss. It's open.", "Right away, boss. Opening it now.").random())
        overlay.play {
            try {
                Haptics.heavy(this)
                startActivity(launch)
                lastOpened = target
            } catch (e: Exception) {
                reply("I couldn't open it, boss. Please allow display over other apps in my settings.")
            }
        }
    }

    // ---------- AI brain (Claude API, streamed: starts speaking after the first sentence) ----------
    private val memoryRule = " When the user shares a lasting personal fact or preference (name, family, job, place, likes, routines), " +
        "call save_memory with a short fact and say briefly that you'll remember it. Never save passwords, card numbers or other secrets."

    private fun memoryTools() = JSONArray().put(JSONObject()
        .put("name", "save_memory")
        .put("description", "Save a durable fact about the user so you remember it in future conversations. Only for lasting facts, not passing remarks.")
        .put("input_schema", JSONObject().put("type", "object")
            .put("properties", JSONObject().put("fact", JSONObject().put("type", "string")
                .put("description", "Short fact, e.g. Wife's name is Sara, or Prefers tea over coffee")))
            .put("required", JSONArray().put("fact"))))

    private val sentenceRegex = Regex("^(.{15,}?[.!?])\\s", RegexOption.DOT_MATCHES_ALL)

    private fun askAi(q: String) {
        val key = prefs().getString("api_key", "")?.trim().orEmpty()
        if (key.isEmpty()) {
            reply("I need an AI key to answer that, boss. Add it in my settings."); return
        }
        if (aiBusy) { reply("One moment, boss. I'm still thinking."); return }
        aiBusy = true
        streamActive = true
        updateNotif("Thinking…"); onReply?.invoke("Thinking…"); onOverlayReply?.invoke("Thinking…")
        val past = history.toList()
        val learn = prefs().getBoolean("learn_on", true)
        Thread {
            val sb = StringBuilder()
            val unspoken = StringBuilder()
            var err: String? = null
            var inTool = false
            var saved = false
            val toolBuf = StringBuilder()
            try {
                val msgs = JSONArray()
                for ((role, t) in past) msgs.put(JSONObject().put("role", role).put("content", t))
                msgs.put(JSONObject().put("role", "user").put("content", q))
                val sys = SYSTEM + " Current date and time: " +
                    SimpleDateFormat("EEEE, MMMM d, yyyy h:mm a", Locale.US).format(Date()) + "." +
                    (if (learn) memoryRule else "") + Memory.promptBlock(this)
                val body = JSONObject().put("model", MODEL).put("max_tokens", 350).put("stream", true)
                    .put("system", sys).put("messages", msgs)
                if (learn) body.put("tools", memoryTools())
                val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 10000; conn.readTimeout = 40000; conn.doOutput = true
                conn.setRequestProperty("content-type", "application/json")
                conn.setRequestProperty("x-api-key", key)
                conn.setRequestProperty("anthropic-version", "2023-06-01")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    err = when (code) {
                        401 -> "Your AI key doesn't look right, boss. Please check it in my settings."
                        429 -> "I'm being rate limited right now, boss. Try again in a moment."
                        else -> "My AI brain returned an error, boss."
                    }
                } else {
                    conn.inputStream.bufferedReader().use { rd ->
                        while (true) {
                            val line = rd.readLine() ?: break
                            if (!line.startsWith("data:")) continue
                            val j = try { JSONObject(line.substring(5).trim()) } catch (e: Exception) { continue }
                            val type = j.optString("type")
                            if (type == "content_block_start") {
                                val cb = j.optJSONObject("content_block")
                                if (cb != null && cb.optString("type") == "tool_use" && cb.optString("name") == "save_memory") {
                                    inTool = true; toolBuf.setLength(0)
                                }
                                continue
                            }
                            if (type == "content_block_stop") {
                                if (inTool) {
                                    inTool = false
                                    val fact = try { JSONObject(toolBuf.toString()).optString("fact") } catch (e: Exception) { "" }
                                    if (fact.isNotBlank()) { saved = true; handler.post { Memory.add(this, fact) } }
                                }
                                continue
                            }
                            if (type != "content_block_delta") continue
                            val delta = j.optJSONObject("delta") ?: continue
                            if (delta.optString("type") == "input_json_delta") {
                                if (inTool) toolBuf.append(delta.optString("partial_json"))
                                continue
                            }
                            val tx = delta.optString("text")
                            if (tx.isEmpty()) continue
                            sb.append(tx); unspoken.append(tx)
                            val snap = sb.toString()
                            handler.post { if (running) onReply?.invoke(snap); onOverlayReply?.invoke(snap) }
                            var mm = sentenceRegex.find(unspoken)
                            while (mm != null) {
                                val chunk = mm.groupValues[1]
                                unspoken.delete(0, mm.range.last + 1)
                                handler.post { if (running) speakChunk(chunk) }
                                mm = sentenceRegex.find(unspoken)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (sb.isEmpty()) err = "I couldn't reach my brain, boss. Please check your internet."
            }
            val full = sb.toString().trim()
            val rest = unspoken.toString().trim()
            val e = err
            val didSave = saved
            handler.post {
                aiBusy = false
                streamActive = false
                if (!running) return@post
                if (full.isEmpty()) {
                    if (didSave) { Haptics.confirm(this); reply("Got it, boss. I'll remember that.") }
                    else { Haptics.error(this); reply(e ?: "I don't have an answer for that, boss.") }
                    return@post
                }
                history.add("user" to q); history.add("assistant" to full)
                while (history.size > 8) history.removeAt(0)
                skills.lastAnswer = full
                if (rest.isNotEmpty()) speakChunk(rest)
                Haptics.confirm(this)
                onReply?.invoke(full); onOverlayReply?.invoke(full)
                postReply(full)
                if (pending.get() <= 0) { speaking = false; listen(50) }
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
        onOverlayReply?.invoke(text)
        if (inCall) Haptics.confirm(this)
        if (!prefs().getBoolean("voice_on", true) || !ttsReady || (inCall && !prefs().getBoolean("speak_in_call", false))) return
        speaking = true
        handler.removeCallbacks(startRunnable)
        recognizer?.cancel()
        pending.set(1)
        applyVoice()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_reply")
    }

    override fun onDestroy() {
        running = false
        overlay.cancel()
        bubble.hide()
        onOverlayReply = null
        inCallNow = false
        handler.removeCallbacksAndMessages(null)
        resetRecognizer()
        tts?.shutdown()
        super.onDestroy()
    }
}
