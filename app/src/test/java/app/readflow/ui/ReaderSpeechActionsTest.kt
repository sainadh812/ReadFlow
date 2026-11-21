package app.readflow.ui

import app.readflow.core.DocumentPipeline
import app.readflow.core.ExtractedPage
import app.readflow.core.SpeechTarget
import app.readflow.core.TextElement
import app.readflow.playback.BlockedSentence
import app.readflow.playback.ReaderPlayback
import org.junit.Assert.*
import org.junit.Test

class ReaderSpeechActionsTest {
    private val page = DocumentPipeline().process("sample", 0,
        ExtractedPage(listOf(TextElement("A quiet page.", emptyList())), 0f, 0f, "Sample"))

    private class Harness(packs: Set<String>) {
        val events = mutableListOf<String>()
        val checked = mutableListOf<String>()
        val actions = ReaderSpeechActions({ checked += it; it in packs }, { events += "setup" })
        fun press(state: ReaderPlayback, model: String = "kokoro") = actions.primary(state, model,
            notifications = { events += "notifications" }, toggle = { events += "toggle" },
            startSelected = { events += "selected:$it" })
        fun move(state: ReaderPlayback, direction: Int) = actions.sentence(state, "kokoro", direction,
            notifications = { events += "notifications" }, move = { events += "move:$it" },
            skipBlocked = { events += "skipBlocked" })
        fun skipBlocked(state: ReaderPlayback) = actions.skipBlocked(state, "kokoro",
            notifications = { events += "notifications" }, skip = { events += "skipBlocked" })
    }

    @Test fun missingVoiceRoutesToSetupWithoutPermissionOrPlayback() {
        val harness = Harness(setOf("alignment"))
        harness.press(ReaderPlayback(page = page))
        assertEquals(listOf("setup"), harness.events)
        assertEquals(listOf("kokoro"), harness.checked)
    }

    @Test fun missingAlignmentRoutesToSetupWithoutPermissionOrPlayback() {
        val harness = Harness(setOf("kokoro"))
        harness.press(ReaderPlayback(page = page))
        assertEquals(listOf("setup"), harness.events)
        assertEquals(listOf("kokoro", "alignment"), harness.checked)
    }

    @Test fun freshInstallSelectedWordAlsoRoutesToSetup() {
        val harness = Harness(emptySet())
        harness.press(ReaderPlayback(page = page, selectedWordId = page.words[1].id))
        assertEquals(listOf("setup"), harness.events)
    }

    @Test fun installedModelsRequestPermissionThenUseNormalPlayback() {
        val harness = Harness(setOf("kokoro", "alignment"))
        harness.press(ReaderPlayback(page = page))
        assertEquals(listOf("notifications", "toggle"), harness.events)
    }

    @Test fun installedModelsKeepTheSelectedWord() {
        val harness = Harness(setOf("kokoro", "alignment"))
        val selected = page.words[1].id
        harness.press(ReaderPlayback(page = page, selectedWordId = selected))
        assertEquals(listOf("notifications", "selected:$selected"), harness.events)
    }

    @Test fun staleSelectionUsesNormalPlayback() {
        val harness = Harness(setOf("kokoro", "alignment"))
        harness.press(ReaderPlayback(page = page, selectedWordId = "old-page-word"))
        assertEquals(listOf("notifications", "toggle"), harness.events)
    }

    @Test fun pauseAndCancelNeverCheckModelsOrRequestPermission() {
        val states = listOf(
            ReaderPlayback(page = page, playing = true),
            ReaderPlayback(page = page, wantsToPlay = true),
            ReaderPlayback(page = page, preparing = true),
            ReaderPlayback(page = page, fillingBuffer = true),
        )
        for (state in states) {
            val harness = Harness(emptySet())
            harness.press(state.copy(selectedWordId = page.words[1].id))
            assertEquals(listOf("toggle"), harness.events)
            assertTrue(harness.checked.isEmpty())
        }
    }

    @Test fun readinessUsesTheSelectedModelForOtherSpeechActions() {
        val harness = Harness(setOf("kokoro", "alignment"))
        assertFalse(harness.actions.ready("other-voice"))
        assertEquals(listOf("setup"), harness.events)
        assertEquals(listOf("other-voice"), harness.checked)
        harness.events.clear()
        assertTrue(harness.actions.ready("kokoro"))
        assertTrue(harness.events.isEmpty())
    }

    @Test fun sentenceButtonsCannotStartWithoutEitherRequiredPack() {
        for (packs in listOf(emptySet(), setOf("kokoro"), setOf("alignment"))) {
            for (direction in listOf(-1, 1)) {
                val harness = Harness(packs)
                harness.move(ReaderPlayback(page = page), direction)
                assertEquals(listOf("setup"), harness.events)
            }
        }
    }

    @Test fun installedSentenceNavigationKeepsDirectionAndExistingPermissionBehavior() {
        val harness = Harness(setOf("kokoro", "alignment"))
        harness.move(ReaderPlayback(page = page), -1)
        harness.move(ReaderPlayback(page = page), 1)
        assertEquals(listOf("move:-1", "move:1"), harness.events)
    }

    @Test fun blockedSentenceRestartRequiresBothModelsBeforePermission() {
        val state = ReaderPlayback(page = page,
            blockedSentence = BlockedSentence(0, page.words[0].id, SpeechTarget(0, page.words[1].id)))
        for (packs in listOf(emptySet(), setOf("kokoro"), setOf("alignment"))) {
            val harness = Harness(packs)
            harness.move(state, 1)
            harness.skipBlocked(state)
            assertEquals(listOf("setup", "setup"), harness.events)
        }
        val installed = Harness(setOf("kokoro", "alignment"))
        installed.move(state, 1)
        installed.skipBlocked(state)
        assertEquals(listOf("notifications", "skipBlocked", "notifications", "skipBlocked"), installed.events)
    }

    @Test fun finalBlockedSentenceStopsWithoutModelsOrPermission() {
        val state = ReaderPlayback(page = page,
            blockedSentence = BlockedSentence(0, page.words.last().id, null))
        val harness = Harness(emptySet())
        harness.move(state, 1)
        harness.skipBlocked(state)
        assertEquals(listOf("skipBlocked", "skipBlocked"), harness.events)
        assertTrue(harness.checked.isEmpty())
    }
}
