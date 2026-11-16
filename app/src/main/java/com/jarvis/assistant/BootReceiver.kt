package com.jarvis.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false)) return
        try {
            context.startForegroundService(Intent(context, JarvisService::class.java))
        } catch (e: Exception) {
            // Android 14+ may block mic services at boot; open the app once to restart Jarvis.
        }
    }
}
