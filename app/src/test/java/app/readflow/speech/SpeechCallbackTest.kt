package app.readflow.speech

import org.junit.Assert.*
import org.junit.Test

class SpeechCallbackTest {
    @Test fun synthesisCallbackHasTheExactMethodSherpaLooksUp() {
        var current = true
        val callback = SherpaAudioCallback { current }
        val method = callback.javaClass.getMethod("invoke", FloatArray::class.java)
        assertEquals(Int::class.javaObjectType, method.returnType)
        assertEquals(1, method.invoke(callback, FloatArray(8)))
        current = false
        assertEquals(0, method.invoke(callback, FloatArray(8)))
    }

    @Test fun oldIndyLambdaReproducesTheMissingNativeMethod() {
        val current = java.util.concurrent.atomic.AtomicBoolean(true)
        val callback: (FloatArray) -> Int = { if (current.get()) 1 else 0 }
        assertEquals(1, callback(FloatArray(8)))
        try {
            callback.javaClass.getMethod("invoke", FloatArray::class.java)
            fail("Expected Kotlin 2.1.20's old indy callback to lack Sherpa's JNI signature")
        } catch (_: NoSuchMethodException) { }
    }

    @Test fun callbackPredicateExceptionStopsSynthesisWithoutEscapingIntoJni() {
        val callback = SherpaAudioCallback { throw IllegalStateException("cancel check failed") }
        val method = callback.javaClass.getMethod("invoke", FloatArray::class.java)
        assertEquals(0, method.invoke(callback, FloatArray(0)))
    }

    @Test fun linkageFailureBecomesARecoverableSpeechError() {
        val cause = UnsatisfiedLinkError("fixture")
        try {
            speechNativeCall<Unit> { throw cause }
            fail("Native linkage failure escaped")
        } catch (error: IllegalStateException) {
            assertSame(cause, error.cause)
            assertTrue(error.message!!.contains("diagnostics"))
        }
    }

    @Test fun nativeWrapperDoesNotSwallowCancellation() {
        val cancel = kotlinx.coroutines.CancellationException("cancelled")
        try {
            speechNativeCall<Unit> { throw cancel }
            fail("Cancellation swallowed")
        } catch (actual: kotlinx.coroutines.CancellationException) {
            assertSame(cancel, actual)
        }
    }
}
