package app.readflow.diagnostics

import org.junit.Assert.*
import org.junit.Test

class ReportedFailureTest {
    @Test fun fatalReportRemovesMessagesPathsAndSuppressedExceptions() {
        val error = IllegalStateException("private book text and user@example.com", IllegalArgumentException("private cause"))
        error.addSuppressed(RuntimeException("private suppressed text"))
        error.stackTrace = arrayOf(StackTraceElement("app.readflow.speech.Engine", "generate", "Engine.kt", 42),
            StackTraceElement("app.readflow.speech.Engine", "generate", "/private/book.pdf", 24))
        val sent = sanitizedFatal(error)
        val text = sent.stackTraceToString()
        assertTrue(text.contains("UNCAUGHT_JAVA_IllegalStateException"))
        assertTrue(text.contains("app.readflow.speech.Engine.generate"))
        assertEquals(42, sent.stackTrace.single().lineNumber)
        assertNull(sent.stackTrace.single().fileName)
        assertFalse(text.contains("private"))
        assertFalse(text.contains("user@example.com"))
        assertTrue(sent.suppressed.isEmpty())
        assertNotSame(error.cause, sent.cause)
    }

    @Test fun fatalReportBoundsCyclicCauses() {
        val first = RuntimeException("one")
        val second = RuntimeException("two", first)
        first.initCause(second)
        val sent = sanitizedFatal(first)
        assertEquals(4, generateSequence(sent) { it.cause }.count())
    }
}
