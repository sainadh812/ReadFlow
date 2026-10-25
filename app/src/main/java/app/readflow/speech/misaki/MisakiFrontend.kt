package app.readflow.speech.misaki

import android.content.Context
import androidx.annotation.Keep
import dalvik.system.BaseDexClassLoader
import org.json.JSONObject
import java.io.Closeable
import java.io.File

/** Experimental US English Misaki frontend with dictionary, POS and neural G2P.
 *
 * Construction loads its models synchronously: callers must use a worker thread
 * and include construction in cold-load timings. Unresolved input fails explicitly;
 * this frontend never invokes eSpeak. Its native ORT environment lives until process
 * exit, while [close] releases every POS and neural session owned by this instance.
 */
@Keep
class MisakiFrontend(ortLibraryPath: String = "libonnxruntime.so", threads: Int = 1) : Closeable {
    private var handle: Long

    init {
        require(threads in 1..4)
        nativeCall {
            System.loadLibrary("onnxruntime")
            System.loadLibrary("readflow_misaki")
        }
        // With uncompressed APK libraries there may be no extracted .so at the
        // nativeLibraryDir path. dlopen by SONAME uses the app's linker namespace.
        val library = ortLibraryPath.takeIf { "!/" in it || File(it).isFile } ?: "libonnxruntime.so"
        handle = decode(nativeCall { nativeCreate(library.toByteArray(Charsets.UTF_8), threads) }).getLong("handle")
        check(handle != 0L) { "Misaki returned an invalid session" }
    }

    @Synchronized
    fun phonemize(text: String): String {
        check(handle != 0L) { "Misaki frontend is closed" }
        require('\u0000' !in text) { "Misaki input cannot contain NUL" }
        require(text.length <= 100_000) { "Misaki input is too long; split it into sentences" }
        val response = decode(nativeCall { nativePhonemize(handle, text.toByteArray(Charsets.UTF_8)) })
        check(response.getBoolean("complete")) { "Misaki returned an incomplete pronunciation" }
        return response.getString("phonemes")
    }

    @Synchronized
    override fun close() {
        val owned = handle
        if (owned == 0L) return
        handle = 0L
        decode(nativeCall { nativeClose(owned) })
    }

    private fun decode(bytes: ByteArray): JSONObject = JSONObject(bytes.toString(Charsets.UTF_8)).also {
        if (it.has("error")) throw IllegalStateException(it.getString("error"))
    }

    private inline fun <T> nativeCall(action: () -> T): T = try {
        action()
    } catch (error: LinkageError) {
        throw IllegalStateException("Misaki could not load its native runtime. Use the current Kokoro frontend and report this test failure.", error)
    }

    private external fun nativeCreate(library: ByteArray, threads: Int): ByteArray
    private external fun nativePhonemize(handle: Long, text: ByteArray): ByteArray
    private external fun nativeClose(handle: Long): ByteArray

    companion object {
        const val VERSION = "misaki-fba1236-go-ec904cd-neural-us-v1"

        /** Bionic accepts the APK!/lib path when AGP leaves native libs uncompressed. */
        fun ortLibraryPath(context: Context): String =
            (context.classLoader as? BaseDexClassLoader)?.findLibrary("onnxruntime")
                ?: File(context.applicationInfo.nativeLibraryDir, "libonnxruntime.so")
                    .takeIf(File::isFile)?.absolutePath
                ?: "libonnxruntime.so"
    }
}
