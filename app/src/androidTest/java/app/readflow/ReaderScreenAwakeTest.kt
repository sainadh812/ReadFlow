package app.readflow

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.readflow.ui.ReaderScreenAwake
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderScreenAwakeTest {
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    @Test fun readerOnlyPreventsIdleDimmingWhileResumedAndRestoresOnExit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val owner = Owner()
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            val view = View(instrumentation.targetContext)
            val awake = ReaderScreenAwake(view, owner.lifecycle)
            assertFalse(view.keepScreenOn)
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            assertTrue(view.keepScreenOn)
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            assertFalse(view.keepScreenOn)
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            assertTrue(view.keepScreenOn)
            awake.close()
            assertFalse(view.keepScreenOn)
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            assertFalse(view.keepScreenOn)
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
        }
    }

    @Test fun leavingReaderPreservesAnExistingKeepAwakeSetting() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val owner = Owner()
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            val view = View(instrumentation.targetContext).apply { keepScreenOn = true }
            val awake = ReaderScreenAwake(view, owner.lifecycle)
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            assertTrue(view.keepScreenOn)
            awake.close()
            assertTrue(view.keepScreenOn)
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
        }
    }
}
