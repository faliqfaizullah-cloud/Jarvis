package com.jarvis.assistant

import android.content.*
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

/** Local (offline) skills. tryHandle returns the spoken reply, or null if the command isn't a skill. */
class Skills(private val ctx: Context) {
    var lastAnswer = ""
    private var torchOn = false
    private val prefs = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    private val sep = "\u0001"
    private val words = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30)
    private val jokes = listOf(
        "Why do programmers prefer dark mode? Because light attracts bugs, boss.",
        "I would tell you a UDP joke, but you might not get it, boss.",
        "There are only 10 kinds of people: those who understand binary and those who don't, boss.",
        "I told my phone I needed space. It opened the storage settings, boss.",
        "Why was the computer cold? It left its Windows open, boss.")

    companion object {
        const val HELP = "I can open and close apps, set timers, alarms and reminders, take notes, call or text your contacts, " +
            "search the web, navigate, play music, control the flashlight and volume, check your battery, do math, remember things about you, " +
            "and answer any question with AI, boss."
    }

    private fun launch(i: Intent): Boolean = try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i); true } catch (e: Exception) { false }

    fun tryHandle(raw: String): String? {
        val c = raw.trim().trimEnd('.', '?', '!').replace(Regex("\\s+"), " ")
        if (c.isEmpty()) return null
        var m: MatchResult?

        if (Regex("^(what can you do|help|help me|what are your features|what can i say)$").matches(c)) return HELP
        if (Regex("^(good morning|morning|brief me|my briefing|daily briefing)$").matches(c)) return briefing()

        // ---- memory ----
        if (Regex("^(forget everything|forget it all|forget all of it|forget all memories|clear (your )?memory|wipe (your )?memory)$").matches(c)) {
            val n = Memory.all(ctx).size
            Memory.clear(ctx)
            return if (n == 0) "My memory was already empty, boss." else "Done, boss. I've forgotten all $n memories."
        }
        if (Regex("^forget (that|the last one|the last thing|what i just said)$").matches(c))
            return if (Memory.removeLast(ctx)) "Forgotten, boss." else "I have nothing to forget, boss."
        m = Regex("^forget (?:about )?(?:that )?(.+)$").matchEntire(c)
        if (m != null) {
            val n = Memory.forget(ctx, m.groupValues[1])
            return if (n > 0) "Forgotten, boss." else "I don't have anything about that, boss."
        }
        if (Regex("^(what do you (remember|know)( about me)?|what have you remembered|list (my )?memories|show (my )?memories)$").matches(c)) return recall()
        m = Regex("^(?:my name is|i am called) (.+)$").matchEntire(c)
        if (m != null) { Memory.add(ctx, "Name is " + m.groupValues[1]); return "Nice to meet you, ${m.groupValues[1]}. I'll remember that, boss." }
        m = Regex("^call me (.+)$").matchEntire(c)
        if (m != null) { Memory.add(ctx, "Prefers to be called " + m.groupValues[1]); return "Got it, I'll remember that, boss." }
        m = Regex("^(?:remember|memorize|don't forget|do not forget)(?: that)? (.+)$").matchEntire(c)
        if (m != null) return if (Memory.add(ctx, m.groupValues[1])) "Got it, boss. I'll remember that." else "I already know that, boss."

        // ---- timers, reminders, alarms ----
        m = Regex("^remind me to (.+?) (?:in|after) (.+)$").matchEntire(c)
        if (m != null) return setTimer(parseSecs(m.groupValues[2]), m.groupValues[1], true)
        m = Regex("^remind me (?:in|after) (.+?) to (.+)$").matchEntire(c)
        if (m != null) return setTimer(parseSecs(m.groupValues[1]), m.groupValues[2], true)
        m = Regex("^(?:set|start)(?: a| an)? timer (?:for )?(.+)$").matchEntire(c)
        if (m != null) return setTimer(parseSecs(m.groupValues[1]), "Jarvis timer", false)
        m = Regex("^(?:set (?:an )?alarm|wake me up)(?: for| at)? (\\d{1,2})(?::(\\d{2}))? ?(am|pm)?$").matchEntire(c.replace(".", ""))
        if (m != null) return setAlarm(m.groupValues[1].toInt(), m.groupValues[2].toIntOrNull() ?: 0, m.groupValues[3])

        // ---- notes ----
        m = Regex("^(?:take a note|make a note|add a note|note)[: ]+(.+)$").matchEntire(c)
        if (m != null) { addNote(m.groupValues[1]); return "Noted, boss." }
        if (Regex("^(read|show|what are) (my )?notes$").matches(c)) return readNotes()
        if (Regex("^(clear|delete) (all )?(my )?notes$").matches(c)) { prefs.edit().remove("notes").apply(); return "All notes cleared, boss." }

        // ---- calls & texts ----
        m = Regex("^(?:text|sms|message) (.+?) (?:saying|that says|say) (.+)$").matchEntire(c)
        if (m != null) return textContact(m.groupValues[1], m.groupValues[2])
        m = Regex("^(?:text|sms|message) (.+)$").matchEntire(c)
        if (m != null) return textContact(m.groupValues[1], "")
        m = Regex("^(?:call|dial|phone) (.+)$").matchEntire(c)
        if (m != null) return callContact(m.groupValues[1])

        // ---- web, maps, media ----
        if (c.contains("weather") && c.length <= 40) {
            return if (launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + URLEncoder.encode(c, "UTF-8")))))
                "Here's the weather, boss." else "I couldn't open the browser, boss."
        }
        m = Regex("^(?:search for|search the web for|search|google|look up) (.+)$").matchEntire(c)
        if (m != null) {
            val q = m.groupValues[1]
            return if (launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8")))))
                "Searching for $q, boss." else "I couldn't open the browser, boss."
        }
        m = Regex("^(?:navigate to|navigate|directions to|take me to) (.+)$").matchEntire(c)
        if (m != null) {
            val d = m.groupValues[1]
            return if (launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(d)))))
                "Getting directions to $d, boss." else "I couldn't open maps, boss."
        }
        m = Regex("^play (.+)$").matchEntire(c)
        if (m != null) {
            val q = m.groupValues[1]
            return if (launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8")))))
                "Playing $q, boss." else "I couldn't open YouTube, boss."
        }

        // ---- phone controls ----
        if ((c.contains("flashlight") || c.contains("torch")) && c.length <= 40) {
            val on = when {
                Regex("\\boff\\b").containsMatchIn(c) -> false
                Regex("\\bon\\b").containsMatchIn(c) -> true
                else -> !torchOn
            }
            return torch(on)
        }
        if ((c.contains("volume") || c == "mute") && c.length <= 30) return volume(c)
        if (c.contains("battery") && c.length <= 40) return battery()
        if (Regex("(wi-?fi|bluetooth)").containsMatchIn(c) && c.length <= 40 &&
            Regex("(turn|switch|toggle|settings|open|enable|disable)").containsMatchIn(c)) {
            val bt = c.contains("bluetooth")
            val i = if (bt) Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    else if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                    else Intent(Settings.ACTION_WIFI_SETTINGS)
            return if (launch(i)) "Opening ${if (bt) "Bluetooth" else "Wi-Fi"} controls, boss. Android won't let me flip it directly."
                   else "I couldn't open settings, boss."
        }

        // ---- math, jokes, clipboard ----
        calc(c)?.let { return it }
        if (c.contains("joke") && c.length <= 30) return jokes.random()
        if (Regex("^(copy|copy that|copy this|copy the answer)$").matches(c)) {
            if (lastAnswer.isEmpty()) return "There's nothing to copy yet, boss."
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("Jarvis", lastAnswer))
            return "Copied to your clipboard, boss."
        }
        if (Regex("^(share|share that|share this|share the answer)$").matches(c)) {
            if (lastAnswer.isEmpty()) return "There's nothing to share yet, boss."
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, lastAnswer)
            return if (launch(Intent.createChooser(send, "Share"))) "Here you go, boss." else "I couldn't share that, boss."
        }
        return null
    }

    // ---------- helpers ----------
    private fun parseSecs(s: String): Int {
        var total = 0.0
        if (s.contains("half an hour")) total += 1800
        val r = Regex("(\\d+(?:\\.\\d+)?|an?|one|two|three|four|five|six|seven|eight|nine|ten|fifteen|twenty|thirty)\\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?)")
        for (x in r.findAll(s)) {
            val n = x.groupValues[1].toDoubleOrNull() ?: (words[x.groupValues[1]] ?: 1).toDouble()
            val u = x.groupValues[2]
            total += n * when { u.startsWith("h") -> 3600; u.startsWith("m") -> 60; else -> 1 }
        }
        return total.toInt()
    }

    private fun fmt(secs: Int): String {
        val h = secs / 3600; val m = (secs % 3600) / 60; val s = secs % 60
        val parts = ArrayList<String>()
        if (h > 0) parts.add("$h hour" + if (h > 1) "s" else "")
        if (m > 0) parts.add("$m minute" + if (m > 1) "s" else "")
        if (s > 0 || parts.isEmpty()) parts.add("$s second" + if (s != 1) "s" else "")
        return parts.joinToString(" and ")
    }

    private fun setTimer(secs: Int, msg: String, reminder: Boolean): String {
        if (secs <= 0) return "Sorry boss, how long should I set it for?"
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secs)
            .putExtra(AlarmClock.EXTRA_MESSAGE, msg)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (!launch(i)) return "I couldn't set that, boss."
        return if (reminder) "Okay boss, I'll remind you to $msg in ${fmt(secs)}." else "Timer set for ${fmt(secs)}, boss."
    }

    private fun setAlarm(h0: Int, min: Int, ap: String): String {
        var h = h0
        if (ap == "pm" && h < 12) h += 12
        if (ap == "am" && h == 12) h = 0
        if (h > 23 || min > 59) return "That time doesn't look right, boss."
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, min)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis").putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (!launch(i)) return "I couldn't set the alarm, boss."
        val t = SimpleDateFormat("h:mm a", Locale.US).format(Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, min) }.time)
        return "Alarm set for $t, boss."
    }

    private fun notes(): MutableList<String> =
        (prefs.getString("notes", "") ?: "").split(sep).filter { it.isNotBlank() }.toMutableList()

    private fun addNote(t: String) {
        val l = notes(); l.add(t); prefs.edit().putString("notes", l.joinToString(sep)).apply()
    }

    private fun readNotes(): String {
        val l = notes()
        if (l.isEmpty()) return "You have no notes, boss."
        val last = l.takeLast(5)
        return "You have ${l.size} note" + (if (l.size > 1) "s" else "") + ". " +
            last.mapIndexed { i, n -> "${i + 1}: $n" }.joinToString(". ")
    }

    private fun findNumber(name: String): Pair<String, String>? {
        if (name.all { it.isDigit() || it == '+' || it == ' ' }) return name to name
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null
        )?.use { cur -> if (cur.moveToFirst()) return cur.getString(0) to cur.getString(1) }
        return null
    }

    private fun hasContacts() =
        ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun callContact(name: String): String {
        val n = name.trim()
        if (!hasContacts() && !n.all { it.isDigit() || it == '+' || it == ' ' })
            return "I need contacts permission for that, boss. Allow it in my settings."
        val f = findNumber(n) ?: return "I couldn't find $n in your contacts, boss."
        return if (launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(f.second)))))
            "Calling ${f.first}, boss." else "I couldn't open the dialer, boss."
    }

    private fun textContact(name: String, body: String): String {
        val n = name.trim()
        if (!hasContacts() && !n.all { it.isDigit() || it == '+' || it == ' ' })
            return "I need contacts permission for that, boss. Allow it in my settings."
        val f = findNumber(n) ?: return "I couldn't find $n in your contacts, boss."
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(f.second))).putExtra("sms_body", body)
        return if (launch(i)) "Message to ${f.first} is ready, boss. Just tap send." else "I couldn't open messages, boss."
    }

    private fun torch(on: Boolean): String {
        return try {
            val cm = ctx.getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "This phone doesn't seem to have a flashlight, boss."
            cm.setTorchMode(id, on); torchOn = on
            if (on) "Flashlight on, boss." else "Flashlight off, boss."
        } catch (e: Exception) { "I couldn't control the flashlight, boss." }
    }

    private fun volume(c: String): String {
        val am = ctx.getSystemService(AudioManager::class.java)
        val s = AudioManager.STREAM_MUSIC
        val ui = AudioManager.FLAG_SHOW_UI
        return when {
            c.contains("max") || c.contains("full") -> { am.setStreamVolume(s, am.getStreamMaxVolume(s), ui); "Volume at maximum, boss." }
            c.contains("mute") || c.contains("zero") -> { am.setStreamVolume(s, 0, ui); "Muted, boss." }
            c.contains("down") || c.contains("lower") || c.contains("decrease") -> {
                repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, ui) }; "Volume down, boss." }
            else -> { repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, ui) }; "Volume up, boss." }
        }
    }

    private fun battery(): String {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val lvl = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "Your battery is at $lvl percent" + (if (bm.isCharging) " and charging" else "") + ", boss."
    }

    private fun briefing(): String {
        val now = Date()
        return "Good day, boss. It's " + SimpleDateFormat("h:mm a", Locale.US).format(now) + " on " +
            SimpleDateFormat("EEEE, MMMM d", Locale.US).format(now) + ". " + battery() + " You have " +
            notes().size + " saved note" + (if (notes().size == 1) "." else "s.")
    }

    private fun recall(): String {
        val l = Memory.all(ctx)
        if (l.isEmpty()) return "I don't have any memories saved yet, boss. Tell me to remember something."
        val last = l.takeLast(6).reversed()
        return "I remember ${l.size} thing" + (if (l.size == 1) "" else "s") + ", boss. " +
            last.joinToString(". ") + "." + (if (l.size > 6) " Open Memory in my settings to see the rest." else "")
    }

    /** The answer when the command is a maths question (lets the service show the Calculating status first). */
    fun calcResult(raw: String): String? {
        val c = raw.trim().trimEnd('.', '?', '!').replace(Regex("\\s+"), " ")
        return calc(c)
    }

    // ---------- calculator ----------
    private fun num(d: Double): String =
        if (d == Math.floor(d) && Math.abs(d) < 1e12) d.toLong().toString()
        else String.format(Locale.US, "%.4f", d).trimEnd('0').trimEnd('.')

    private fun calc(c: String): String? {
        val pm = Regex("^(?:what is |what's |calculate )?(\\d+(?:\\.\\d+)?) ?(?:percent|%) of (\\d+(?:\\.\\d+)?)$").matchEntire(c)
        if (pm != null) {
            val v = pm.groupValues[1].toDouble() * pm.groupValues[2].toDouble() / 100.0
            return "That's ${num(v)}, boss."
        }
        val e = c.replace(Regex("^(what is|what's|whats|calculate|compute|how much is) "), "")
            .replace("multiplied by", "*").replace("divided by", "/").replace("times", "*")
            .replace("plus", "+").replace("minus", "-").replace(" over ", "/")
            .replace("÷", "/").replace("×", "*")
            .replace(Regex("(?<=\\d) ?x ?(?=\\d)"), "*")
        if (!Regex("^[0-9+\\-*/(). ]+$").matches(e)) return null
        if (!e.any { it.isDigit() } || !e.any { it in "+-*/" }) return null
        val v = try { Parser(e).parse() } catch (ex: Exception) { return null }
        if (v.isNaN() || v.isInfinite()) return "I can't divide by zero, boss."
        return "That's ${num(v)}, boss."
    }

    private class Parser(s: String) {
        private val t = s.replace(" ", "")
        private var i = 0
        fun parse(): Double { val v = expr(); if (i != t.length) throw IllegalArgumentException(); return v }
        private fun expr(): Double {
            var v = term()
            while (i < t.length && (t[i] == '+' || t[i] == '-')) { val op = t[i++]; val r = term(); v = if (op == '+') v + r else v - r }
            return v
        }
        private fun term(): Double {
            var v = factor()
            while (i < t.length && (t[i] == '*' || t[i] == '/')) { val op = t[i++]; val r = factor(); v = if (op == '*') v * r else v / r }
            return v
        }
        private fun factor(): Double {
            if (i < t.length && t[i] == '-') { i++; return -factor() }
            if (i < t.length && t[i] == '(') {
                i++; val v = expr()
                if (i >= t.length || t[i] != ')') throw IllegalArgumentException()
                i++; return v
            }
            val st = i
            while (i < t.length && (t[i].isDigit() || t[i] == '.')) i++
            if (st == i) throw IllegalArgumentException()
            return t.substring(st, i).toDouble()
        }
    }
}
