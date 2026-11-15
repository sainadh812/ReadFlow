package app.readflow.ui

import org.junit.Assert.*
import org.junit.Test

class ReaderNavigationTest {
    @Test fun settingsAndVoicesReturnToTheirActualOrigin() {
        val navigation = ReaderNavigation()
        navigation.navigate("reader")
        navigation.navigate("settings")
        navigation.navigate("voices")
        assertTrue(navigation.back())
        assertEquals("settings", navigation.screen.value)
        assertTrue(navigation.back())
        assertEquals("reader", navigation.screen.value)
        assertTrue(navigation.back())
        assertEquals("library", navigation.screen.value)
        assertFalse(navigation.back())
    }

    @Test fun returningToReaderOrLibraryRemovesLoopsWithoutReopeningDocuments() {
        val navigation = ReaderNavigation()
        navigation.navigate("reader")
        navigation.navigate("settings")
        navigation.navigate("ocr")
        navigation.navigate("reader")
        assertEquals(listOf("library", "reader"), navigation.history)
        navigation.navigate("reader")
        assertEquals(2, navigation.history.size)
        navigation.navigate("library")
        assertEquals(listOf("library"), navigation.history)
    }

    @Test fun nestedToolsReturnThroughTheirOpeningScreens() {
        val navigation = ReaderNavigation()
        navigation.navigate("settings")
        navigation.navigate("benchmark")
        navigation.navigate("voices")
        navigation.back()
        assertEquals("benchmark", navigation.screen.value)
        navigation.back()
        assertEquals("settings", navigation.screen.value)
        navigation.navigate("legal")
        navigation.back()
        assertEquals("settings", navigation.screen.value)
    }
}
