package com.example.booklibrary.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import com.example.booklibrary.R
import com.example.booklibrary.ReaderActivity
import com.example.booklibrary.util.SafeLog

class TtsForegroundService : Service() {

    companion object {
        private const val TAG = "TtsForegroundService"
        const val CHANNEL_ID = "tts_playback"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.booklibrary.tts.START"
        const val ACTION_PLAY = "com.example.booklibrary.tts.PLAY"
        const val ACTION_PAUSE = "com.example.booklibrary.tts.PAUSE"
        const val ACTION_STOP = "com.example.booklibrary.tts.STOP"
        const val ACTION_NEXT = "com.example.booklibrary.tts.NEXT"
        const val ACTION_PREVIOUS = "com.example.booklibrary.tts.PREVIOUS"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_CHAPTER = "extra_chapter"
        const val EXTRA_EPUB_PATH = "extra_epub_path"
        const val EXTRA_BOOK_ID = "extra_book_id"
    }

    interface Callback {
        fun onPlay()
        fun onPause()
        fun onStop()
        fun onNext()
        fun onPrevious()
    }

    inner class LocalBinder : Binder() {
        val service: TtsForegroundService get() = this@TtsForegroundService
    }

    private val binder = LocalBinder()
    var callback: Callback? = null

    private var mediaSession: MediaSessionCompat? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    private var currentTitle: String = ""
    private var currentChapter: String? = null
    private var currentEpubPath: String? = null
    private var currentBookId: Int = -1
    private var isPlaying: Boolean = false
    private var pausedByAudioFocusLoss: Boolean = false

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        setupMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
                currentTitle = title
                currentChapter = intent.getStringExtra(EXTRA_CHAPTER)
                currentEpubPath = intent.getStringExtra(EXTRA_EPUB_PATH)
                currentBookId = intent.getIntExtra(EXTRA_BOOK_ID, -1)
                isPlaying = true
                pausedByAudioFocusLoss = false
                requestAudioFocus()
                startForeground(NOTIFICATION_ID, buildNotification(title, true))
                updateMediaSessionState(true)
            }
            ACTION_PLAY -> callback?.onPlay()
            ACTION_PAUSE -> callback?.onPause()
            ACTION_STOP -> callback?.onStop()
            ACTION_NEXT -> callback?.onNext()
            ACTION_PREVIOUS -> callback?.onPrevious()
            else -> {
                // Safety: always call startForeground to avoid crash
                startForeground(NOTIFICATION_ID, buildNotification(currentTitle, isPlaying))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        callback = null
        abandonAudioFocus()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.tts_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.tts_notification_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "BookLibraryTts").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { callback?.onPlay() }
                override fun onPause() { callback?.onPause() }
                override fun onStop() { callback?.onStop() }
                override fun onSkipToNext() { callback?.onNext() }
                override fun onSkipToPrevious() { callback?.onPrevious() }
            })
            isActive = true
        }
    }

    fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true

        val result: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
            audioFocusRequest = request
            result = audioManager?.requestAudioFocus(request) ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
        } else {
            @Suppress("DEPRECATION")
            result = audioManager?.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }

        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(audioFocusListener)
        }
        hasAudioFocus = false
    }

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                SafeLog.d(TAG, "Audio focus lost, pausing TTS")
                if (isPlaying) pausedByAudioFocusLoss = true
                callback?.onPause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                SafeLog.d(TAG, "Audio focus gained")
                if (pausedByAudioFocusLoss) {
                    pausedByAudioFocusLoss = false
                    callback?.onPlay()
                }
            }
        }
    }

    fun showNotification(title: String, playing: Boolean) {
        currentTitle = title
        isPlaying = playing
        if (playing) pausedByAudioFocusLoss = false
        val notification = buildNotification(title, playing)
        startForeground(NOTIFICATION_ID, notification)
        updateMediaSessionState(playing)
    }

    fun updateNotification(playing: Boolean) {
        isPlaying = playing
        if (playing) pausedByAudioFocusLoss = false
        val notification = buildNotification(currentTitle, playing)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
        updateMediaSessionState(playing)
    }

    fun updateChapter(chapter: String?) {
        currentChapter = chapter
        val notification = buildNotification(currentTitle, isPlaying)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    fun stopService() {
        callback = null
        abandonAudioFocus()
        mediaSession?.isActive = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun updateMediaSessionState(playing: Boolean) {
        val state = if (playing) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build()
        )
        mediaSession?.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
                .build()
        )
    }

    private fun buildNotification(title: String, playing: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, ReaderActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                currentEpubPath?.let { putExtra(ReaderActivity.EXTRA_EPUB_PATH, it) }
                if (currentBookId > 0) putExtra(ReaderActivity.EXTRA_BOOK_ID, currentBookId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val prevIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TtsForegroundService::class.java).apply { action = ACTION_PREVIOUS },
            PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIntent = PendingIntent.getService(
            this, if (playing) 20 else 21,
            Intent(this, TtsForegroundService::class.java).apply {
                action = if (playing) ACTION_PAUSE else ACTION_PLAY
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val nextIntent = PendingIntent.getService(
            this, 3,
            Intent(this, TtsForegroundService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 4,
            Intent(this, TtsForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (playing) R.drawable.ic_pause else R.drawable.ic_play
        val playPauseLabel = if (playing) getString(R.string.pause) else getString(R.string.read_aloud)

        val subtitle = when {
            currentChapter != null && playing -> currentChapter
            currentChapter != null && !playing -> "${getString(R.string.tts_paused_label)} \u00b7 $currentChapter"
            playing -> getString(R.string.tts_reading)
            else -> getString(R.string.tts_paused_label)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSmallIcon(R.drawable.ic_headphones)
            .setContentIntent(contentIntent)
            .setOngoing(playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_skip_previous, getString(R.string.tts_previous), prevIntent)
            .addAction(playPauseIcon, playPauseLabel, playPauseIntent)
            .addAction(R.drawable.ic_skip_next, getString(R.string.tts_next), nextIntent)
            .addAction(R.drawable.ic_stop, getString(R.string.tts_stop), stopIntent)
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }
}
