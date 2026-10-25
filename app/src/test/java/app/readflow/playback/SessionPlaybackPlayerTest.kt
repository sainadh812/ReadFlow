package app.readflow.playback

import androidx.media3.common.Player
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SessionPlaybackPlayerTest {
    @Test fun externalPauseReachesCoordinatorEvenWhenPlayerWasAlreadyPaused() {
        val raw = PlayerProbe()
        var reservePending = true
        var cancellationRequests = 0
        val controls = SessionPlaybackPlayer(raw.player, {}, {
            cancellationRequests++
            reservePending = false
            raw.player.pause()
        }, {})

        assertFalse(raw.player.playWhenReady)
        controls.pause()
        assertFalse(reservePending)
        assertEquals(1, cancellationRequests)
        assertEquals(1, raw.calls("pause"))
        assertFalse(raw.player.playWhenReady)
    }

    @Test fun booleanControllerCommandsUseTheSameExplicitPlayAndPauseRoutes() {
        val raw = PlayerProbe()
        val requests = mutableListOf<String>()
        val controls = SessionPlaybackPlayer(raw.player,
            { requests += "play"; raw.player.play() },
            { requests += "pause"; raw.player.pause() }, {})

        controls.play()
        controls.playWhenReady = true
        controls.pause()
        controls.playWhenReady = false

        assertEquals(listOf("play", "play", "pause", "pause"), requests)
        assertEquals(2, raw.calls("play"))
        assertEquals(2, raw.calls("pause"))
        assertFalse(raw.player.playWhenReady)
    }

    @Test fun internalCoordinatorCommandsNeverReenterExternalControlCallbacks() {
        val raw = PlayerProbe()
        val requests = mutableListOf<String>()
        val controls = SessionPlaybackPlayer(raw.player,
            { requests += "play" }, { requests += "pause" }, { requests += "stop" })

        raw.player.play()
        raw.player.pause()
        raw.player.playWhenReady = false
        raw.player.stop()

        assertTrue(requests.isEmpty())
        assertFalse(controls.playWhenReady)
        assertEquals(1, raw.calls("stop"))
    }

    @Test fun seekingStillTargetsUnderlyingPlayerWithoutRequestingPlaybackOrCancellation() {
        val raw = PlayerProbe()
        val requests = mutableListOf<String>()
        val controls = SessionPlaybackPlayer(raw.player,
            { requests += "play" }, { requests += "pause" }, { requests += "stop" })

        controls.seekTo(2, 12_345)
        controls.seekBack()
        controls.seekToDefaultPosition()

        assertEquals(2, raw.lastSeekArguments!![0])
        assertEquals(12_345L, raw.lastSeekArguments!![1])
        assertEquals(1, raw.calls("seekTo"))
        assertEquals(1, raw.calls("seekBack"))
        assertEquals(1, raw.calls("seekToDefaultPosition"))
        assertTrue(requests.isEmpty())
    }

    @Test fun externalStopCancelsThroughCoordinatorAndReleaseHasSingleOwnership() {
        val raw = PlayerProbe()
        var cancellationRequests = 0
        val controls = SessionPlaybackPlayer(raw.player, {}, {}, {
            cancellationRequests++
            raw.player.stop()
        })

        controls.stop()
        controls.release()

        assertEquals(1, cancellationRequests)
        assertEquals(1, raw.calls("stop"))
        assertEquals(1, raw.calls("release"))
    }

    /** Exercises the real ForwardingPlayer without creating an Android ExoPlayer or service. */
    private class PlayerProbe : InvocationHandler {
        private val calls = mutableMapOf<String, Int>()
        private var playWhenReady = false
        var lastSeekArguments: List<Any?>? = null
            private set
        val player = Proxy.newProxyInstance(Player::class.java.classLoader,
            arrayOf(Player::class.java), this) as Player

        fun calls(method: String): Int = calls[method] ?: 0

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            calls[method.name] = calls(method.name) + 1
            return when (method.name) {
                "play" -> { playWhenReady = true; null }
                "pause", "stop" -> { playWhenReady = false; null }
                "setPlayWhenReady" -> { playWhenReady = args!![0] as Boolean; null }
                "getPlayWhenReady" -> playWhenReady
                "seekTo" -> { lastSeekArguments = args!!.toList(); null }
                "seekBack", "seekToDefaultPosition", "release" -> null
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "PlayerProbe"
                else -> error("Unexpected Player call: ${method.name}")
            }
        }
    }
}
