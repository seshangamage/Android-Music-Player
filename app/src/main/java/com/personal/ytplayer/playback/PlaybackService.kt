package com.personal.ytplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.personal.ytplayer.MainActivity
import com.personal.ytplayer.R
import com.personal.ytplayer.extractor.DownloaderImpl

/**
 * Plays the queue in the background and publishes a media session.
 * A Bluetooth watch does not need its own app: while this session is active,
 * the watch's play, pause, next, and previous buttons are delivered here and
 * applied to the playlist.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var lastErrorIndex = -1

    override fun onCreate() {
        super.onCreate()
        val notificationProvider = DefaultMediaNotificationProvider(this)
        notificationProvider.setSmallIcon(R.drawable.ic_stat_music)
        setMediaNotificationProvider(notificationProvider)

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(DownloaderImpl.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
        val dataSourceFactory = ResolvingDataSource.Factory(httpFactory, AudioStreamResolver())
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) lastErrorIndex = -1
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Playback failed", error)
                val index = player.currentMediaItemIndex
                if (player.hasNextMediaItem() && index != lastErrorIndex) {
                    lastErrorIndex = index
                    player.seekToNext()
                    player.prepare()
                    player.play()
                }
            }
        })

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(openAppIntent())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        const val TAG = "PlaybackService"
    }
}
