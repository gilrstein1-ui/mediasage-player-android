package com.tulik.player

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var bridgeJs: String = ""
    private var authTries = 0
    private var pendingMicRequest: android.webkit.PermissionRequest? = null
    // Pending <input type="file"> callback — the feedback form's "Add photo" picker.
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    companion object {
        // Lets the foreground service drive the page (single visible activity).
        var instance: MainActivity? = null
        private const val REQ_MIC = 2
        private const val REQ_FILE = 3
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            val req = pendingMicRequest
            pendingMicRequest = null
            if (req != null) {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    req.grant(arrayOf(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                } else {
                    req.deny()
                }
            }
        }
    }

    // Result of the file picker opened by <input type="file"> (feedback "Add photo").
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE) {
            val cb = fileCallback
            fileCallback = null
            cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        val baseUrl = ServerConfig.baseUrl   // baked in at build time (per-user installer)

        requestNotifPermissionIfNeeded()

        bridgeJs = try {
            assets.open("bridge.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = "$userAgentString MediaSageAndroid/${BuildConfig.VERSION_NAME}"
        }

        // Downloads (the ⬆ Update app APK, saved doodles, …): WebView ignores them by
        // default, and Android's DownloadManager doesn't have our login — attach the
        // baked basic-auth header and let DownloadManager fetch + notify. Tapping the
        // finished notification installs the update.
        webView.setDownloadListener { url, _, contentDisposition, mimetype, _ ->
            try {
                val fname = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
                val req = android.app.DownloadManager.Request(android.net.Uri.parse(url))
                    .setMimeType(mimetype)
                    .setTitle(fname)
                    .setNotificationVisibility(
                        android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    .setDestinationInExternalPublicDir(
                        android.os.Environment.DIRECTORY_DOWNLOADS, fname
                    )
                if (ServerConfig.authPw.isNotEmpty()) {
                    val cred = android.util.Base64.encodeToString(
                        "${ServerConfig.authUser}:${ServerConfig.authPw}".toByteArray(),
                        android.util.Base64.NO_WRAP
                    )
                    req.addRequestHeader("Authorization", "Basic $cred")
                }
                (getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager).enqueue(req)
                android.widget.Toast.makeText(
                    this, "Downloading $fname — tap the notification when it finishes to install",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    this, "Download failed: ${e.message}", android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }

        webView.addJavascriptInterface(NowPlayingBridge(), "AndroidBridge")
        // Mic access for the feedback form's 🎙 voice notes: grant the page's
        // audio-capture request once the app itself holds RECORD_AUDIO.
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                runOnUiThread {
                    val wantsAudio = request.resources.contains(
                        android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE
                    )
                    if (!wantsAudio) { request.deny(); return@runOnUiThread }
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        request.grant(arrayOf(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    } else {
                        pendingMicRequest = request
                        ActivityCompat.requestPermissions(
                            this@MainActivity, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC
                        )
                    }
                }
            }

            // Make <input type="file"> work — opens the system photo picker so the
            // feedback form's "📎 Add photo" button can attach a screenshot/photo.
            override fun onShowFileChooser(
                wv: WebView?, callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val intent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                return try {
                    startActivityForResult(Intent.createChooser(intent, "Select a photo"), REQ_FILE)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                authTries = 0   // fresh navigation → allow the saved password one shot again
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // Fallback injection if document-start scripts aren't supported.
                if (bridgeJs.isNotEmpty()) webView.evaluateJavascript(bridgeJs, null)
            }

            // The public address is HTTPS basic-auth. The user's credentials are
            // baked into THIS (per-user) build — sign in automatically, no prompt.
            override fun onReceivedHttpAuthRequest(
                view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?
            ) {
                if (handler == null) return
                authTries++
                if (authTries <= 2 && ServerConfig.authPw.isNotEmpty()) {
                    handler.proceed(ServerConfig.authUser, ServerConfig.authPw)
                } else {
                    handler.cancel()   // avoid an auth loop if the baked password is rejected
                }
            }
        }

        // Preferred: inject the bridge before the page's own scripts run.
        if (bridgeJs.isNotEmpty() &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            try {
                WebViewCompat.addDocumentStartJavaScript(webView, bridgeJs, setOf("*"))
            } catch (e: Exception) { /* fall back to onPageFinished */ }
        }

        webView.loadUrl("$baseUrl/player")
    }

    /** Called by the foreground service when notification/lock-screen buttons are pressed. */
    fun runMediaAction(action: String) {
        runOnUiThread {
            if (::webView.isInitialized) {
                webView.evaluateJavascript(
                    "window.__msAndroid && window.__msAndroid.action('$action');",
                    null
                )
            }
        }
    }

    inner class NowPlayingBridge {
        @JavascriptInterface
        fun updateNowPlaying(title: String, artist: String, artUrl: String, playing: Boolean) {
            val i = Intent(this@MainActivity, PlaybackService::class.java).apply {
                action = PlaybackService.ACTION_UPDATE
                putExtra(PlaybackService.EX_TITLE, title)
                putExtra(PlaybackService.EX_ARTIST, artist)
                putExtra(PlaybackService.EX_ART, artUrl)
                putExtra(PlaybackService.EX_PLAYING, playing)
            }
            ContextCompat.startForegroundService(this@MainActivity, i)
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        stopService(Intent(this, PlaybackService::class.java))
        super.onDestroy()
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
                )
            }
        }
    }
}
