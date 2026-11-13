package app.readflow.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import app.readflow.data.Preferences
import app.readflow.data.ReaderLightingSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal val ReaderWarmLightColor = Color(0xFFFFC85A)

/** Apply outside PageTurnViewport so both live pages and captured curls receive one wash. */
internal fun Modifier.readerWarmLight(settings: ReaderLightingSettings): Modifier {
    val alpha = settings.warmOverlayAlpha
    return if (alpha == 0f) this else drawWithContent {
        drawContent()
        drawRect(ReaderWarmLightColor, alpha = alpha)
    }
}

/** The reader and its sheet share a live draft; disk writes happen on release/toggle. */
@Stable
internal class ReaderLightingState(
    initial: ReaderLightingSettings,
    private val scope: CoroutineScope,
    private val save: suspend (ReaderLightingSettings) -> Unit,
    private val onSaveError: (Throwable) -> Unit,
) {
    var settings by mutableStateOf(initial.normalized())
        private set
    private var draft = false
    private var pending: ReaderLightingSettings? = null

    fun acceptStored(stored: ReaderLightingSettings) {
        val value = stored.normalized()
        // An older DataStore emission must not make a live slider jump backwards.
        if (!draft && (pending == null || pending == value)) {
            pending = null
            settings = value
        }
    }

    fun usePhoneBrightness(enabled: Boolean) {
        settings = settings.copy(usePhoneBrightness = enabled)
        commit()
    }

    fun enableWarmLight(enabled: Boolean) {
        settings = settings.copy(warmLightEnabled = enabled)
        commit()
    }

    fun previewBrightness(percent: Int) {
        draft = true
        settings = settings.copy(brightnessPercent = percent).normalized()
    }

    fun previewWarmth(strength: Int) {
        draft = true
        settings = settings.copy(warmLightStrength = strength).normalized()
    }

    fun commit() {
        draft = false
        val requested = settings
        pending = requested
        scope.launch {
            try {
                save(requested)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (pending == requested) pending = null
                onSaveError(error)
            }
        }
    }

    fun saveDraft() { if (draft) commit() }
}

@Composable internal fun rememberReaderLighting(vm: ReaderViewModel, prefs: Preferences): ReaderLightingState {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lighting = remember(vm) {
        ReaderLightingState(ReaderLightingSettings.from(prefs), vm.viewModelScope,
            save = vm.app.preferences::setReaderLighting,
            onSaveError = { vm.message.value = "Could not save reading light settings" })
    }
    SideEffect { lighting.acceptStored(ReaderLightingSettings.from(prefs)) }
    DisposableEffect(lighting, lifecycle) {
        val observer = object : DefaultLifecycleObserver {
            override fun onPause(owner: LifecycleOwner) { lighting.saveDraft() }
        }
        lifecycle.addObserver(observer)
        // Also save a slider interrupted by navigating away; the ViewModel owns the write.
        onDispose {
            lifecycle.removeObserver(observer)
            lighting.saveDraft()
        }
    }
    return lighting
}

/** Owns only this reader's window attribute, including a sheet's separate dialog window. */
@Composable internal fun ReaderBrightnessEffect(settings: ReaderLightingSettings) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val window = remember(view) {
        (view.parent as? DialogWindowProvider)?.window ?: view.context.readerActivityWindow()
    }
    val brightness = remember(window) {
        window?.let {
            ReaderWindowBrightness(
                read = { it.attributes.screenBrightness },
                write = { value -> it.attributes = it.attributes.apply { screenBrightness = value } },
            )
        }
    }
    SideEffect { brightness?.update(settings) }
    DisposableEffect(brightness, lifecycle) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) { brightness?.setForeground(true) }
            override fun onPause(owner: LifecycleOwner) { brightness?.setForeground(false) }
            override fun onDestroy(owner: LifecycleOwner) { brightness?.close() }
        }
        lifecycle.addObserver(observer)
        brightness?.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            lifecycle.removeObserver(observer)
            brightness?.close()
        }
    }
}

private fun Context.readerActivityWindow(): Window? {
    var context = this
    while (context is ContextWrapper && context !is Activity) context = context.baseContext
    return (context as? Activity)?.window
}

/** Kept independent of Android window/lifecycle classes so restoration can be verified. */
internal class ReaderWindowBrightness(
    private val read: () -> Float,
    private val write: (Float) -> Unit,
) {
    private var settings = ReaderLightingSettings()
    private var previous: Float? = null
    private var foreground = false

    fun update(value: ReaderLightingSettings) {
        settings = value.normalized()
        apply()
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (value) apply() else restore()
    }

    private fun apply() {
        if (!foreground) return
        if (previous == null) previous = read()
        val value = settings.windowBrightness
        if (read() != value) write(value)
    }

    private fun restore() {
        previous?.let { if (read() != it) write(it) }
        previous = null
    }

    fun close() {
        foreground = false
        restore()
    }
}
