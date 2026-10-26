package app.readflow.speech

internal inline fun <T> speechNativeCall(action: () -> T): T = try {
    action()
} catch (error: LinkageError) {
    throw IllegalStateException("Speech runtime could not start. Copy playback diagnostics from Settings.", error)
}
