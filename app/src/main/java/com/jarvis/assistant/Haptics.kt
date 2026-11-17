package com.jarvis.assistant

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/** Small haptic vocabulary: tick (light), click, heavy (big actions), confirm (success), error. Can be turned off in Settings. */
object Haptics {
    private fun on(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getBoolean("haptics_on", true)

    private fun play(c: Context, e: VibrationEffect) {
        if (!on(c)) return
        try { c.getSystemService(Vibrator::class.java)?.vibrate(e) } catch (ex: Exception) { }
    }

    fun tick(c: Context) = play(c,
        if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        else VibrationEffect.createOneShot(10, 80))

    fun click(c: Context) = play(c,
        if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        else VibrationEffect.createOneShot(20, 150))

    fun heavy(c: Context) = play(c,
        if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        else VibrationEffect.createOneShot(40, 255))

    fun confirm(c: Context) = play(c,
        if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
        else VibrationEffect.createWaveform(longArrayOf(0, 20, 50, 30), intArrayOf(0, 160, 0, 220), -1))

    fun error(c: Context) = play(c,
        VibrationEffect.createWaveform(longArrayOf(0, 45, 60, 45), intArrayOf(0, 255, 0, 255), -1))
}
