package com.jarvis.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** In-app updater: checks the latest GitHub Release, downloads Jarvis.apk and installs it over the current app. */
object Updater {
    const val REPO = "faliqfaizullah-cloud/Jarvis"

    class Info(val version: String, val notes: String, val apkUrl: String)

    fun installedVersion(ctx: Context): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0" } catch (e: Exception) { "0" }

    private fun parts(v: String): List<Int> =
        v.trim().removePrefix("v").substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }

    fun isNewer(remote: String, local: String): Boolean {
        val a = parts(remote); val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Blocking. Returns Info when a newer release exists, null when up to date. Throws on network errors. */
    fun check(ctx: Context): Info? {
        val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        conn.connectTimeout = 10000; conn.readTimeout = 15000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val code = conn.responseCode
        if (code == 404) return null
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val tag = j.optString("tag_name")
        val assets = j.optJSONArray("assets") ?: return null
        var url = ""
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break }
        }
        if (url.isEmpty() || !isNewer(tag, installedVersion(ctx))) return null
        return Info(tag.removePrefix("v"), j.optString("body").trim().take(500), url)
    }

    /** Blocking. Downloads the APK into the app cache, reporting 0..100. */
    fun download(ctx: Context, url: String, onProgress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "Jarvis-update.apk")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 30000
        val total = conn.contentLength
        conn.inputStream.use { ins ->
            FileOutputStream(out).use { os ->
                val buf = ByteArray(32768)
                var done = 0L
                var last = -1
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    os.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val p = (done * 100 / total).toInt()
                        if (p != last) { last = p; onProgress(p) }
                    }
                }
            }
        }
        return out
    }

    fun canInstall(a: Activity): Boolean = a.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(a: Activity) {
        a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
    }

    /** Hands the APK to Android's installer; it replaces the current app and keeps your data. */
    fun install(a: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(a, a.packageName + ".fileprovider", apk)
        a.startActivity(Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
