package com.tulik.player

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Base64
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updater. On launch it checks the user's own hub for a newer build
 * (a tiny version manifest published next to the APK at deploy time). If one
 * exists it offers a one-tap update: download the APK itself, then launch
 * Android's package-installer prompt. Because every build is signed with the
 * same stable key, it installs in place over the running app.
 *
 * Not fully silent by design — Android requires the user to confirm the install
 * for a sideloaded app, and to allow this app as an install source once.
 */
object Updater {
    private const val MANIFEST_PATH = "/hub/TulikPlayer.version.json"
    private const val APK_FALLBACK = "/hub/TulikPlayer.apk"

    private var downloadId = -1L
    private var receiver: BroadcastReceiver? = null
    private var pending: Info? = null            // waiting on the "install unknown apps" grant

    data class Info(val code: Int, val name: String, val apkUrl: String, val notes: String)

    private fun authHeader(): String? {
        if (ServerConfig.authPw.isEmpty()) return null
        val cred = Base64.encodeToString(
            "${ServerConfig.authUser}:${ServerConfig.authPw}".toByteArray(), Base64.NO_WRAP)
        return "Basic $cred"
    }

    /** Background check; prompts (on the UI thread) only if a newer build is published. */
    fun check(activity: Activity) {
        Thread {
            try {
                val base = ServerConfig.baseUrl.trimEnd('/')
                val conn = (URL(base + MANIFEST_PATH).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 8000
                    authHeader()?.let { setRequestProperty("Authorization", it) }
                }
                if (conn.responseCode != 200) { conn.disconnect(); return@Thread }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val j = JSONObject(text)
                val code = j.optInt("versionCode", 0)
                if (code <= BuildConfig.VERSION_CODE) return@Thread     // already current
                val apk = j.optString("apk", APK_FALLBACK)
                val apkUrl = if (apk.startsWith("http")) apk else base + apk
                val info = Info(code, j.optString("versionName", ""), apkUrl, j.optString("notes", ""))
                activity.runOnUiThread { if (!activity.isFinishing) promptUpdate(activity, info) }
            } catch (e: Exception) { /* offline / no manifest yet → silently skip */ }
        }.start()
    }

    private fun promptUpdate(activity: Activity, info: Info) {
        val msg = StringBuilder("Version ${info.name} is available.")
        if (info.notes.isNotEmpty()) msg.append("\n\n").append(info.notes)
        msg.append("\n\nUpdate now? You'll get a quick install prompt.")
        AlertDialog.Builder(activity)
            .setTitle("Update available")
            .setMessage(msg.toString())
            .setPositiveButton("Update") { _, _ -> startUpdate(activity, info) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun startUpdate(activity: Activity, info: Info) {
        // Android O+: the app must be an allowed install source. If not, send the
        // user to enable it; we resume the download when they come back (onResume).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()) {
            pending = info
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")))
                Toast.makeText(activity, "Allow this app to install updates — then it continues automatically.",
                    Toast.LENGTH_LONG).show()
            } catch (e: Exception) { pending = null }
            return
        }
        download(activity, info)
    }

    /** Called from MainActivity.onResume — resumes after the unknown-sources grant. */
    fun resumeIfPending(activity: Activity) {
        val info = pending ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()) return   // still not granted
        pending = null
        download(activity, info)
    }

    private fun download(activity: Activity, info: Info) {
        try {
            val req = DownloadManager.Request(Uri.parse(info.apkUrl))
                .setTitle("Updating TulikPlayer…")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS,
                    "TulikPlayer-update.apk")
            authHeader()?.let { req.addRequestHeader("Authorization", it) }
            val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            registerReceiver(activity, dm)
            downloadId = dm.enqueue(req)
            Toast.makeText(activity, "Downloading update…", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(activity, "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun registerReceiver(activity: Activity, dm: DownloadManager) {
        unregister(activity)
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return
                unregister(activity)
                val uri = dm.getUriForDownloadedFile(id) ?: return
                val install = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try { activity.startActivity(install) } catch (e: Exception) {
                    Toast.makeText(activity, "Could not open the installer.", Toast.LENGTH_LONG).show()
                }
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            activity.registerReceiver(receiver, filter)
        }
    }

    fun unregister(activity: Activity) {
        receiver?.let { try { activity.unregisterReceiver(it) } catch (e: Exception) {} }
        receiver = null
    }
}
