package com.tulik.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import java.net.URL
import kotlin.concurrent.thread

/**
 * Foreground service whose only job is to keep the app process alive (and surface
 * media controls) so the WebView's audio is never throttled/killed in the
 * background. Audio itself plays inside the WebView; this just holds foreground
 * state and mirrors now-playing into a MediaStyle notification + MediaSession.
 */
class PlaybackService : Service() {

    private lateinit var session: MediaSessionCompat
    private var artBitmap: Bitmap? = null
    private var lastArtUrl: String = ""
    private var started = false

    // Pause (never reroute to the phone speaker) when audio output goes "noisy" —
    // e.g. car Bluetooth disconnects or headphones unplug. Without this, Android
    // keeps the WebView audio playing out loud on the device speaker (Rambo, 2026-06-28).
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) drive("pause")
        }
    }

    companion object {
        const val CHANNEL = "playback"
        const val NOTIF_ID = 42

        const val ACTION_UPDATE = "com.tulik.player.UPDATE"
        const val ACTION_PLAY = "com.tulik.player.PLAY"
        const val ACTION_PAUSE = "com.tulik.player.PAUSE"
        const val ACTION_NEXT = "com.tulik.player.NEXT"
        const val ACTION_PREV = "com.tulik.player.PREV"
        const val ACTION_STOP = "com.tulik.player.STOP"

        const val EX_TITLE = "title"
        const val EX_ARTIST = "artist"
        const val EX_ART = "art"
        const val EX_PLAYING = "playing"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        session = MediaSessionCompat(this, "TulikPlayer").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = drive("play")
                override fun onPause() = drive("pause")
                override fun onSkipToNext() = drive("next")
                override fun onSkipToPrevious() = drive("prev")
                override fun onStop() = drive("pause")
            })
            isActive = true
        }
        ContextCompat.registerReceiver(
            this, becomingNoisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> drive("play")
            ACTION_PAUSE -> drive("pause")
            ACTION_NEXT -> drive("next")
            ACTION_PREV -> drive("prev")
            ACTION_STOP -> {
                drive("pause")
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE -> {
                val title = intent.getStringExtra(EX_TITLE) ?: getString(R.string.app_name)
                val artist = intent.getStringExtra(EX_ARTIST) ?: ""
                val art = intent.getStringExtra(EX_ART) ?: ""
                val playing = intent.getBooleanExtra(EX_PLAYING, true)
                updateState(title, artist, playing)
                // Post synchronously to satisfy the startForeground deadline.
                postNotification(title, artist, playing)
                maybeLoadArt(art, title, artist, playing)
            }
            else -> {
                // Started with no usable action: post a placeholder to stay legal.
                if (!started) postNotification(getString(R.string.app_name), "", false)
            }
        }
        return START_STICKY
    }

    private fun drive(action: String) {
        // Ignore "play" while a phone call is active/ringing: some Bluetooth
        // devices fire AVRCP play around calls, which started music mid-call
        // (Rambo, 2026-06-13). Pause/next/prev stay allowed.
        if (action == "play") {
            try {
                val am = getSystemService(android.media.AudioManager::class.java)
                if (am != null && am.mode != android.media.AudioManager.MODE_NORMAL) return
            } catch (e: Exception) { /* never block playback on a check failure */ }
        }
        MainActivity.instance?.runMediaAction(action)
    }

    private fun updateState(title: String, artist: String, playing: Boolean) {
        session.isActive = true   // re-assert: keeps OUR metadata on car/Bluetooth displays
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .apply { artBitmap?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) } }
                .build()
        )
        val state = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
    }

    private fun maybeLoadArt(artUrl: String, title: String, artist: String, playing: Boolean) {
        if (artUrl.isEmpty() || artUrl == lastArtUrl) return
        lastArtUrl = artUrl
        thread {
            try {
                val bmp = BitmapFactory.decodeStream(URL(artUrl).openStream())
                if (bmp != null) {
                    artBitmap = bmp
                    updateState(title, artist, playing)
                    postNotification(title, artist, playing)
                }
            } catch (e: Exception) { /* ignore art failures */ }
        }
    }

    private fun postNotification(title: String, artist: String, playing: Boolean) {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            piFlags()
        )

        val toggleAction = if (playing) ACTION_PAUSE else ACTION_PLAY
        val toggleIcon = if (playing) R.drawable.ic_pause else R.drawable.ic_play
        val toggleLabel = if (playing) "Pause" else "Play"

        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(artist)
            .setLargeIcon(artBitmap)
            .setContentIntent(contentIntent)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_prev, "Prev", actionPi(ACTION_PREV))
            .addAction(toggleIcon, toggleLabel, actionPi(toggleAction))
            .addAction(R.drawable.ic_next, "Next", actionPi(ACTION_NEXT))
            .setStyle(
                MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        val notif = b.build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        started = true
    }

    private fun actionPi(action: String): PendingIntent {
        val i = Intent(this, PlaybackService::class.java).setAction(action)
        return PendingIntent.getService(this, action.hashCode(), i, piFlags())
    }

    private fun piFlags(): Int {
        var f = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) f = f or PendingIntent.FLAG_IMMUTABLE
        return f
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(becomingNoisyReceiver) } catch (e: Exception) { /* not registered */ }
        session.isActive = false
        session.release()
        super.onDestroy()
    }
}
