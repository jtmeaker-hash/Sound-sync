package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import com.example.MainActivity
import com.example.audio.DjAudioEngine
import com.example.model.Track
import com.example.util.AlbumArtHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel

/**
 * Foreground Media Playback Service providing native Android system media controls,
 * lock-screen controls, notification shade transport buttons, and Bluetooth/headset integration.
 */
class MediaPlaybackService : Service() {

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationManager: NotificationManager
    private lateinit var audioEngine: DjAudioEngine

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var progressJob: Job? = null
    private var currentArtwork: Bitmap? = null
    private var lastNotifiedPlaying: Boolean? = null
    private var lastNotifiedTrackId: String? = null
    private var lastArtworkTrackId: String? = null

    private var isForegroundActive = false

    // Broadcast receiver for noisy output (unplugging headphones)
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.d(TAG, "Audio becoming noisy (headphones unplugged) - pausing playback.")
                audioEngine.pause()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MediaPlaybackService onCreate")

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        audioEngine = DjAudioEngine.getInstance(applicationContext)

        val mediaButtonReceiver = ComponentName(this, MediaButtonReceiver::class.java)
        mediaSession = MediaSessionCompat(this, TAG, mediaButtonReceiver, null).apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )

            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    Log.d(TAG, "MediaSession: onPlay")
                    audioEngine.play()
                }

                override fun onPause() {
                    Log.d(TAG, "MediaSession: onPause")
                    audioEngine.pause()
                }

                override fun onSkipToNext() {
                    Log.d(TAG, "MediaSession: onSkipToNext")
                    audioEngine.onNextTrackCallback?.invoke()
                }

                override fun onSkipToPrevious() {
                    Log.d(TAG, "MediaSession: onSkipToPrevious")
                    audioEngine.onPreviousTrackCallback?.invoke()
                }

                override fun onSeekTo(pos: Long) {
                    Log.d(TAG, "MediaSession: onSeekTo $pos ms")
                    audioEngine.seekToMs(pos)
                }

                override fun onStop() {
                    Log.d(TAG, "MediaSession: onStop")
                    audioEngine.pause()
                    stopForegroundService()
                }

                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
            })

            isActive = true
        }

        registerReceiver(
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        )

        observeAudioEngine()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)

        when (intent?.action) {
            ACTION_PLAY -> audioEngine.play()
            ACTION_PAUSE -> audioEngine.pause()
            ACTION_TOGGLE_PLAY_PAUSE -> audioEngine.togglePlayPause()
            ACTION_NEXT -> audioEngine.onNextTrackCallback?.invoke()
            ACTION_PREVIOUS -> audioEngine.onPreviousTrackCallback?.invoke()
            ACTION_STOP -> {
                audioEngine.pause()
                stopForegroundService()
            }
        }

        return START_STICKY
    }

    private fun observeAudioEngine() {
        // 1. Observe current track changes
        serviceScope.launch {
            audioEngine.currentTrack.collectLatest { track ->
                if (track != null) {
                    updateTrackMetadata(track)
                } else {
                    mediaSession.setMetadata(null)
                    if (!audioEngine.isPlaying.value) {
                        stopForegroundService()
                    }
                }
            }
        }

        // 2. Observe play/pause state. Notification content changes only when the
        // transport state changes; position updates are kept in MediaSession only.
        serviceScope.launch {
            audioEngine.isPlaying.collectLatest { isPlaying ->
                updatePlaybackState(isPlaying, audioEngine.currentPositionMs.value)
                val track = audioEngine.currentTrack.value
                if (track != null && lastNotifiedPlaying != isPlaying) {
                    lastNotifiedPlaying = isPlaying
                    val notification = buildNotification(track, isPlaying)
                    if (isPlaying) {
                        startForegroundWithNotification(notification)
                    } else {
                        notificationManager.notify(NOTIFICATION_ID, notification)
                        if (isForegroundActive) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                stopForeground(STOP_FOREGROUND_DETACH)
                            } else {
                                @Suppress("DEPRECATION")
                                stopForeground(false)
                            }
                            isForegroundActive = false
                        }
                    }
                }
            }
        }

        // 3. Observe playback position for lock screen / system media scrubber
        serviceScope.launch {
            audioEngine.currentPositionMs.collectLatest { positionMs ->
                updatePlaybackState(audioEngine.isPlaying.value, positionMs)
            }
        }
    }

    private fun startForegroundWithNotification(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isForegroundActive = true
    }

    private suspend fun updateTrackMetadata(track: Track) {
        if (lastArtworkTrackId != track.id) {
            lastArtworkTrackId = track.id
            currentArtwork = withContext(Dispatchers.IO) {
                AlbumArtHelper.getArtworkForTrack(applicationContext, track, sizePx = 512)
            }
        }

        val durationMs = if (track.durationSeconds > 0) track.durationSeconds * 1000L else -1L

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.album)
            .putString(MediaMetadataCompat.METADATA_KEY_GENRE, track.genre)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)

        if (currentArtwork != null) {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentArtwork)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, currentArtwork)
        }

        mediaSession.setMetadata(metadataBuilder.build())

        val isPlaying = audioEngine.isPlaying.value
        updatePlaybackState(isPlaying, audioEngine.currentPositionMs.value)

        lastNotifiedPlaying = isPlaying
        lastNotifiedTrackId = track.id
        val notification = buildNotification(track, isPlaying)
        if (isPlaying) {
            startForegroundWithNotification(notification)
        } else {
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun updatePlaybackState(isPlaying: Boolean, positionMs: Long) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val speed = if (isPlaying) (1.0f + audioEngine.pitchPercent.value / 100.0f) else 0.0f

        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_STOP

        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, positionMs, speed)

        mediaSession.setPlaybackState(stateBuilder.build())
    }

    private fun buildNotification(track: Track, isPlaying: Boolean): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Previous Action
        val prevIntent = Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_PREVIOUS }
        val prevPendingIntent = PendingIntent.getService(
            this,
            1,
            prevIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val prevAction = NotificationCompat.Action(
            android.R.drawable.ic_media_previous,
            "Previous",
            prevPendingIntent
        )

        // Play/Pause Action
        val playPauseIntent = Intent(this, MediaPlaybackService::class.java).apply {
            action = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
        }
        val playPausePendingIntent = PendingIntent.getService(
            this,
            2,
            playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseTitle = if (isPlaying) "Pause" else "Play"
        val playPauseAction = NotificationCompat.Action(
            playPauseIcon,
            playPauseTitle,
            playPausePendingIntent
        )

        // Next Action
        val nextIntent = Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_NEXT }
        val nextPendingIntent = PendingIntent.getService(
            this,
            3,
            nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextAction = NotificationCompat.Action(
            android.R.drawable.ic_media_next,
            "Next",
            nextPendingIntent
        )

        // Stop Action (dismissal)
        val stopIntent = Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            4,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mediaStyle = MediaStyle()
            .setMediaSession(mediaSession.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)
            .setShowCancelButton(true)
            .setCancelButtonIntent(stopPendingIntent)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setStyle(mediaStyle)
            .setContentTitle(track.title)
            .setContentText(track.artist)
            .setSubText(track.album.ifBlank { "SoundSync DJ" })
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentPendingIntent)
            .setDeleteIntent(stopPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)

        if (currentArtwork != null) {
            builder.setLargeIcon(currentArtwork)
        }

        return builder.build()
    }

    private fun stopForegroundService() {
        if (isForegroundActive) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            isForegroundActive = false
        }
        notificationManager.cancel(NOTIFICATION_ID)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SoundSync Media Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows now-playing track info, playback state, and system transport controls."
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "MediaPlaybackService onDestroy")
        try {
            unregisterReceiver(noisyReceiver)
        } catch (ignored: Exception) {}

        progressJob?.cancel()
        serviceScope.coroutineContext.cancel()
        currentArtwork = null
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MediaPlaybackService"
        const val CHANNEL_ID = "soundsync_media_channel"
        const val NOTIFICATION_ID = 4096

        const val ACTION_PLAY = "com.example.service.action.PLAY"
        const val ACTION_PAUSE = "com.example.service.action.PAUSE"
        const val ACTION_TOGGLE_PLAY_PAUSE = "com.example.service.action.TOGGLE_PLAY_PAUSE"
        const val ACTION_NEXT = "com.example.service.action.NEXT"
        const val ACTION_PREVIOUS = "com.example.service.action.PREVIOUS"
        const val ACTION_STOP = "com.example.service.action.STOP"

        fun startService(context: Context) {
            val intent = Intent(context, MediaPlaybackService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not start MediaPlaybackService: ${e.message}")
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
