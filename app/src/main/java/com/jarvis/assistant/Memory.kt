package com.jarvis.assistant

import android.content.Context
import org.json.JSONArray

/** Jarvis's long-term memory: short facts about you, stored only on this phone. */
object Memory {
    private const val KEY = "memories"
    private fun prefs(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun all(c: Context): List<String> {
        val s = prefs(c).getString(KEY, "[]") ?: "[]"
        return try { val a = JSONArray(s); List(a.length()) { a.getString(it) } } catch (e: Exception) { emptyList() }
    }

    private fun save(c: Context, l: List<String>) {
        prefs(c).edit().putString(KEY, JSONArray(l).toString()).apply()
    }

    fun add(c: Context, fact: String): Boolean {
        val f = fact.trim().trimEnd('.').take(200).replaceFirstChar { it.uppercase() }
        if (f.length < 3) return false
        val l = all(c).toMutableList()
        if (l.any { it.equals(f, ignoreCase = true) }) return false
        l.add(f)
        while (l.size > 100) l.removeAt(0)
        save(c, l)
        return true
    }

    fun remove(c: Context, index: Int) {
        val l = all(c).toMutableList()
        if (index in l.indices) { l.removeAt(index); save(c, l) }
    }

    fun removeLast(c: Context): Boolean {
        val l = all(c).toMutableList()
        if (l.isEmpty()) return false
        l.removeAt(l.size - 1); save(c, l); return true
    }

    /** Removes every memory containing the keyword. Returns how many were removed. */
    fun forget(c: Context, keyword: String): Int {
        val k = keyword.lowercase().trim().removePrefix("my ").removePrefix("the ").trim()
        if (k.length < 2) return 0
        val l = all(c)
        val keep = l.filter { !it.lowercase().contains(k) }
        if (keep.size != l.size) save(c, keep)
        return l.size - keep.size
    }

    fun clear(c: Context) { prefs(c).edit().remove(KEY).apply() }

    /** Text appended to the AI's system prompt (newest first, capped). */
    fun promptBlock(c: Context): String {
        val l = all(c)
        if (l.isEmpty()) return ""
        val sb = StringBuilder("\n\nThings you remember about the user (\"I\" and \"my\" in them mean the user). " +
            "Use them naturally when relevant and never recite them unprompted:\n")
        for (m in l.reversed()) {
            if (sb.length + m.length > 1800) break
            sb.append("- ").append(m).append('\n')
        }
        return sb.toString()
    }
}
