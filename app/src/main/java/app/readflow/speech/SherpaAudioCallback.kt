package app.readflow.speech

import androidx.annotation.Keep

/** Sherpa 1.13.8 looks up invoke(float[]): java.lang.Integer via JNI. */
@Keep
class SherpaAudioCallback(private val isCurrent: () -> Boolean) : (FloatArray) -> Int {
    // An indy lambda only exposes invoke(Object): Object and fails that native lookup.
    override fun invoke(samples: FloatArray): Int = try {
        if (isCurrent()) 1 else 0
    } catch (_: Exception) {
        // A Java exception left pending in this native callback can abort ART.
        0
    }
}

internal inline fun <T> speechNativeCall(action: () -> T): T = try {
    action()
} catch (error: LinkageError) {
    throw IllegalStateException("Speech runtime could not start. Copy playback diagnostics from Settings.", error)
}
