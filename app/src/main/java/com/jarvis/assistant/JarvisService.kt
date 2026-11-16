package com.jarvis.assistant

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.text.SimpleDateFormat
import java.util.*

class JarvisService : Service(), TextToSpeech.OnInitListener {
    companion object { const val ACTION_STOP = "com.jarvis.assistant.STOP"; const val CH = "jarvis" }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var running = false
    private var awaitingUntil = 0L

    private fun prefs() = getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Jarvis", NotificationManager.IMPORTANCE_LOW))
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setPitch(0.75f)
            tts?.setSpeechRate(0.95f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onError(id: String?) { speaking = false; listen() }
                override fun onDone(id: String?) { speaking = false; listen() }
            })
            ttsReady = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running = false
            recognizer?.destroy()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, 1, notif("Listening for \"Hey Jarvis\"…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (!running) { running = true; listen() }
        return START_STICKY
    }

    private fun notif(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH)
            .setContentTitle("Jarvis is online")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotif(t: String) =
        getSystemService(NotificationManager::class.java).notify(1, notif(t))

    // ---------- Listening ----------
    private fun listen(delay: Long = 400) {
        if (!running || speaking) return
        handler.postDelayed({
            if (!running || speaking) return@postDelayed
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return@postDelayed
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply { setRecognitionListener(listener) }
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            recognizer?.startListening(i)
        }, delay)
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
            val heard = list.firstOrNull { it.lowercase().contains("jarvis") } ?: list.firstOrNull()
            if (heard != null) handle(heard)
            if (!speaking) listen()
        }
        override fun onError(error: Int) { listen(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500 else 600) }
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
        val awaiting = System.currentTimeMillis() < awaitingUntil
        if (!text.contains("jarvis") && !awaiting) return
        val cmd = text.substringAfter("jarvis", text)
            .replace(Regex("^(hey|hi|ok|okay)\\s+"), "")
            .replace(Regex("^[,.\\s]+"), "")
            .trim()
        awaitingUntil = 0
        updateNotif("Heard: $raw")

        if (cmd.isEmpty()) {
            awaitingUntil = System.currentTimeMillis() + 8000
            reply("Yes boss, I'm listening."); return
        }
        val open = Regex("^(please\\s+)?(open|launch|start)\\s+(.+?)(\\s+please)?$").find(cmd)
        when {
            open != null -> openApp(open.groupValues[3])
            cmd.contains("time") -> reply("It's " + SimpleDateFormat("h:mm a", Locale.US).format(Date()) + ", boss.")
            cmd.contains("date") || cmd.contains("day is it") ->
                reply("Today is " + SimpleDateFormat("EEEE, MMMM d", Locale.US).format(Date()) + ", boss.")
            cmd.contains("how are you") -> reply("Fully operational and ready to work, boss.")
            cmd.contains("thank") -> reply("Anytime, boss.")
            else -> reply("Sorry boss, I can't do that yet. Try saying open and an app name.")
        }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun openApp(name: String) {
        val pm = packageManager
        val q = norm(name)
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val match = apps.firstOrNull { norm(it.loadLabel(pm).toString()) == q }
            ?: apps.firstOrNull { norm(it.loadLabel(pm).toString()).contains(q) && q.length >= 3 }
            ?: apps.firstOrNull { q.contains(norm(it.loadLabel(pm).toString())) && norm(it.loadLabel(pm).toString()).length >= 3 }
        val launch = match?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
        if (launch == null) { reply("Sorry boss, I couldn't find $name on your phone."); return }
        try {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
            val phrases = listOf("Alright boss, I opened it up for you.",
                "Done, boss. It's open.", "Right away, boss. Opening it now.")
            reply(phrases.random())
        } catch (e: Exception) {
            reply("I couldn't open it, boss. Please allow display over other apps in my settings.")
        }
    }

    private fun reply(text: String) {
        updateNotif(text)
        if (!prefs().getBoolean("voice_on", true) || !ttsReady) return
        speaking = true
        recognizer?.cancel()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_reply")
    }

    override fun onDestroy() {
        running = false
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
