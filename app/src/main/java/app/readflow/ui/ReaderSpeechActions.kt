package app.readflow.ui

import app.readflow.playback.ReaderPlayback

/** Checks setup before starting speech; stopping existing work never depends on model files. */
internal class ReaderSpeechActions(
    private val installed: (String) -> Boolean,
    private val showSetup: () -> Unit,
) {
    fun ready(model: String): Boolean {
        if (installed(model) && installed("alignment")) return true
        showSetup()
        return false
    }

    fun primary(
        state: ReaderPlayback,
        model: String,
        notifications: () -> Unit,
        toggle: () -> Unit,
        startSelected: (String) -> Unit,
    ) {
        if (state.playing || state.wantsToPlay || state.preparing || state.fillingBuffer) {
            toggle()
            return
        }
        if (!ready(model)) return
        notifications()
        val selected = state.page?.words?.firstOrNull { it.id == state.selectedWordId }
        if (selected != null) startSelected(selected.id) else toggle()
    }

    fun sentence(
        state: ReaderPlayback,
        model: String,
        direction: Int,
        notifications: () -> Unit,
        move: (Int) -> Unit,
        skipBlocked: () -> Unit,
    ) {
        if (direction > 0 && state.blockedSentence != null) {
            this.skipBlocked(state, model, notifications, skipBlocked)
        } else if (ready(model)) move(direction)
    }

    fun skipBlocked(
        state: ReaderPlayback,
        model: String,
        notifications: () -> Unit,
        skip: () -> Unit,
    ) {
        val blocked = state.blockedSentence ?: return
        if (blocked.resume != null) {
            if (!ready(model)) return
            notifications()
        }
        // A final blocked sentence only stops playback; no model or permission is needed.
        skip()
    }
}
