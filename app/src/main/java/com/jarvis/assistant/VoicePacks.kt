package com.jarvis.assistant

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/** Voice presets: each picks a language/accent plus pitch and speed on the phone's text-to-speech engine. */
object VoicePacks {
    class Pack(val name: String, val desc: String, val locale: Locale, val pitch: Float, val rate: Float)

    val all = listOf(
        Pack("Jarvis", "Deep British butler", Locale.UK, 0.75f, 0.95f),
        Pack("Deep Boss", "Low and powerful", Locale.US, 0.55f, 0.90f),
        Pack("Friday", "Bright, quick American", Locale.US, 1.10f, 1.05f),
        Pack("Calm", "Soft, slow British", Locale.UK, 1.00f, 0.82f),
        Pack("Aussie", "Relaxed Australian", Locale("en", "AU"), 0.90f, 1.00f),
        Pack("Desi", "Indian English", Locale("en", "IN"), 0.95f, 1.00f),
        Pack("Robot", "Metallic droid", Locale.US, 0.45f, 1.10f),
        Pack("Speedy", "Fast and efficient", Locale.US, 0.90f, 1.35f)
    )

    fun get(i: Int): Pack = all[i.coerceIn(0, all.size - 1)]

    /** Installed voices that match the pack's accent, in a stable order. */
    fun voicesFor(t: TextToSpeech, loc: Locale): List<Voice> =
        (t.voices ?: emptySet<Voice>())
            .filter {
                it.locale.language == loc.language &&
                    (loc.country.isEmpty() || it.locale.country == loc.country) &&
                    !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }
            .sortedBy { it.name }

    fun apply(t: TextToSpeech, p: Pack, voiceName: String) {
        val v = voicesFor(t, p.locale).let { list -> list.firstOrNull { it.name == voiceName } ?: list.firstOrNull() }
        if (v != null) {
            t.setVoice(v)
        } else {
            val r = t.setLanguage(p.locale)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) t.setLanguage(Locale.US)
        }
        t.setPitch(p.pitch)
        t.setSpeechRate(p.rate)
    }

    fun preview(t: TextToSpeech, p: Pack, voiceName: String) {
        apply(t, p, voiceName)
        t.speak("Alright boss, I opened it up for you.", TextToSpeech.QUEUE_FLUSH, null, "preview")
    }
}
