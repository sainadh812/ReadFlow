package app.readflow.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.readflow.ReadFlowApp

/** Routes explicit controller commands even when the underlying player is already paused. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class SessionPlaybackPlayer(
    player: Player,
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onStop: () -> Unit,
) : ForwardingPlayer(player) {
    override fun play() { onPlay() }
    override fun pause() { onPause() }
    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) onPlay() else onPause()
    }
    override fun stop() { onStop() }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ReadingService : MediaSessionService() {
    private var session: MediaSession? = null
    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).setUsage(C.USAGE_MEDIA).build(), true)
            setHandleAudioBecomingNoisy(true); setWakeMode(C.WAKE_MODE_LOCAL)
        }
        val playback = (application as ReadFlowApp).playback
        // During preparation the underlying player is already paused. A controller's pause
        // would otherwise be a no-op, leaving the coordinator free to start audio later.
        // Wrap only the session player: internal coordinator commands still use ExoPlayer
        // directly and must never be mistaken for an external cancellation or play request.
        val controls = SessionPlaybackPlayer(player, playback::playFromControls,
            playback::pauseFromControls, { playback.stop() })
        session = MediaSession.Builder(this, controls).build()
        playback.attach(player)
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() {
        (application as ReadFlowApp).playback.detach()
        // ForwardingPlayer.release() releases the single underlying ExoPlayer instance.
        session?.run { player.release(); release() }; session = null
        super.onDestroy()
    }
}
