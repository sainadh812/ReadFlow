package app.readflow.ingest

import java.util.concurrent.atomic.AtomicLong

/** Local CPU-only TeleOCR bridge. Calls are blocking and must run on a worker thread. */
object TeleOcrNative {
    init { System.loadLibrary("readflow_teleocr") }

    fun open(modelPath: String, projectorPath: String, threads: Int, imageTokens: Int): Session {
        val session = create(threads)
        return try {
            session.load(modelPath, projectorPath, imageTokens)
            session
        } catch (error: Throwable) {
            session.close()
            throw error
        }
    }

    /** Create before starting the cancellation watcher, then call Session.load on IO. */
    fun create(threads: Int): Session {
        require(threads in 1..8)
        return Session(nativeCreate(threads))
    }

    /** Own one session per page, reuse it for layout/region passes, then close it. */
    class Session internal constructor(handle: Long) : AutoCloseable {
        private val nativeHandle = AtomicLong(handle)

        fun load(modelPath: String, projectorPath: String, imageTokens: Int) {
            require(imageTokens in 64..2048)
            val handle = nativeHandle.get()
            check(handle != 0L) { "TeleOCR session is closed." }
            nativeLoad(handle, modelPath, projectorPath, imageTokens)
        }

        fun recognize(rgb: ByteArray, width: Int, height: Int, prompt: String, maxOutputTokens: Int): String {
            require(width in 1..8192 && height in 1..8192)
            require(width.toLong() * height * 3 == rgb.size.toLong())
            require(rgb.size <= 24 * 1024 * 1024)
            require(maxOutputTokens in 1..4096)
            require(prompt.isNotBlank() && prompt.length <= 4096)
            val handle = nativeHandle.get()
            check(handle != 0L) { "TeleOCR session is closed." }
            return nativeRecognize(handle, rgb, width, height, prompt, maxOutputTokens).toString(Charsets.UTF_8)
        }

        /** Safe from another thread. Cancellation is permanent for this session. */
        fun cancel() { nativeHandle.get().takeIf { it != 0L }?.let(::nativeCancel) }

        override fun close() { nativeHandle.getAndSet(0L).takeIf { it != 0L }?.let(::nativeClose) }
    }

    private external fun nativeCreate(threads: Int): Long
    private external fun nativeLoad(handle: Long, modelPath: String, projectorPath: String, imageTokens: Int)
    private external fun nativeRecognize(handle: Long, rgb: ByteArray, width: Int, height: Int, prompt: String, maxOutputTokens: Int): ByteArray
    private external fun nativeCancel(handle: Long)
    private external fun nativeClose(handle: Long)
}
