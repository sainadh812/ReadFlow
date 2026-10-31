package app.readflow.ui

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Prevent idle dimming only while the reader is on screen and the activity is resumed. */
@Composable internal fun KeepReaderScreenAwake() {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(view, lifecycle) {
        val awake = ReaderScreenAwake(view, lifecycle)
        onDispose { awake.close() }
    }
}

internal class ReaderScreenAwake(private val view: View, private val lifecycle: Lifecycle) : DefaultLifecycleObserver {
    private val previous = view.keepScreenOn

    init {
        lifecycle.addObserver(this)
        view.keepScreenOn = previous || lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }

    override fun onResume(owner: LifecycleOwner) { view.keepScreenOn = true }
    override fun onPause(owner: LifecycleOwner) { view.keepScreenOn = previous }
    override fun onDestroy(owner: LifecycleOwner) { close() }

    fun close() {
        lifecycle.removeObserver(this)
        view.keepScreenOn = previous
    }
}
