package app.readflow

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readflow.speech.SherpaAudioCallback
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechCallbackAbiTest {
    @Test fun packagedDexPreservesTheNativeCallbackSignature() {
        var current = true
        val callback = SherpaAudioCallback { current }
        val method = callback.javaClass.getMethod("invoke", FloatArray::class.java)
        assertEquals(Int::class.javaObjectType, method.returnType)
        assertEquals(1, method.invoke(callback, floatArrayOf(0f, 0.1f)))
        current = false
        assertEquals(0, method.invoke(callback, floatArrayOf(0f)))
    }
}
