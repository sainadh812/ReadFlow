package app.readflow.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.readflow.playback.ReaderPlayback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Measures actual Compose layout without starting speech engines or requiring an emulator. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w800dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderFloatingDockTest {
    @get:Rule val compose = createComposeRule()

    @Test fun portraitKeepsPageAndControlsStillDuringPreparationAndErrors() {
        assertStableAudioStates(width = 360, height = 800, fontScale = 1f)
    }

    @Test fun narrowPortraitKeepsPageAndControlsStillDuringPreparationAndErrors() {
        assertStableAudioStates(width = 320, height = 640, fontScale = 1f)
    }

    @Test fun largeAccessibilityTextKeepsPageAndControlsStillDuringPreparationAndErrors() {
        assertStableAudioStates(width = 320, height = 800, fontScale = 2f)
    }

    @Test fun landscapeKeepsPageAndControlsStillDuringPreparationAndErrors() {
        assertStableAudioStates(width = 640, height = 360, fontScale = 1f)
    }

    @Test fun textOnlyDocumentsKeepTheStatusHeaderAndTransportStill() {
        assertStableAudioStates(width = 320, height = 800, fontScale = 2f, originalLabel = null)
    }

    @Test fun explicitlyHidingMetricsKeepsTransportAnchoredAndSubsequentAudioStatesStable() {
        val audio = mutableStateOf(AudioState())
        val metrics = mutableStateOf(Metrics())
        val showMetrics = mutableStateOf(true)
        showReader(360, 800, 1f, audio, metrics, showMetrics)
        val visible = snapshot()

        compose.runOnIdle { showMetrics.value = false }
        compose.onNodeWithTag(METRICS).assertDoesNotExist()
        val hidden = snapshot(includeMetrics = false)
        assertRectEquals("transport after explicit hide", visible.transport, hidden.transport)
        assertRectEquals("primary action after explicit hide", visible.primary, hidden.primary)
        audioStates.forEach { state ->
            compose.runOnIdle { audio.value = state }
            assertSnapshotEquals("metrics hidden: ${state.status}", hidden, snapshot(includeMetrics = false))
        }

        compose.runOnIdle { showMetrics.value = true }
        assertSnapshotEquals("metrics restored", visible, snapshot())
    }

    @Test fun viewSelectorAndStatusKeepTheirActionsWhenAudioStateChanges() {
        val audio = mutableStateOf(AudioState())
        val original = mutableStateOf(false)
        var detailRequests = 0
        showReader(320, 800, 2f, audio, mutableStateOf(Metrics()), mutableStateOf(false),
            original = original, showDetails = { detailRequests++ })

        compose.onNodeWithContentDescription("Text view").assertIsSelected()
        compose.onNodeWithContentDescription("PDF view").performClick().assertIsSelected()
        compose.runOnIdle { assertTrue(original.value) }
        compose.onNodeWithContentDescription("Text view").performClick().assertIsSelected()
        compose.runOnIdle { assertFalse(original.value) }

        val error = AudioState(status = "Reading stopped · Tap for details", hasIssue = true)
        compose.runOnIdle { audio.value = error }
        compose.onNodeWithText(error.status).performClick()
        compose.runOnIdle { assertEquals(1, detailRequests) }
    }

    @Test fun metricsRetainTheirDetailsAndHideActions() {
        var details = 0
        var hides = 0
        compose.setContent {
            MaterialTheme {
                PerformanceSummary(generation = "1,234 chars/min", buffer = "24 s ready · 1.8× listening",
                    showDetails = { details++ }, hide = { hides++ })
            }
        }
        compose.onNodeWithText("1,234 chars/min").performClick()
        compose.onNodeWithContentDescription("Hide performance metrics").performClick()
        compose.runOnIdle {
            assertEquals(1, details)
            assertEquals(1, hides)
        }
    }

    private fun assertStableAudioStates(width: Int, height: Int, fontScale: Float, originalLabel: String? = "PDF") {
        val audio = mutableStateOf(AudioState())
        val metrics = mutableStateOf(Metrics())
        showReader(width, height, fontScale, audio, metrics, mutableStateOf(true), originalLabel = originalLabel)
        val initial = snapshot(includeModes = originalLabel != null)
        assertTrue("Page has readable space", initial.page.height > 80f)
        assertTrue("Card does not cover the page", initial.page.bottom <= initial.dock.top)
        if (originalLabel != null) {
            compose.onNodeWithContentDescription("Text view").assertIsDisplayed()
            compose.onNodeWithContentDescription("PDF view").assertIsDisplayed()
        } else {
            compose.onNodeWithContentDescription("Text view").assertDoesNotExist()
            compose.onNodeWithContentDescription("PDF view").assertDoesNotExist()
        }
        compose.onNodeWithTag(TRANSPORT).assertIsDisplayed()
        assertTransportTargets(initial)

        audioStates.forEachIndexed { index, state ->
            compose.runOnIdle {
                audio.value = state
                metrics.value = when (index % 3) {
                    0 -> Metrics("1,234 chars/min", "24 s ready · 1.8× listening")
                    1 -> Metrics("987,654,321 chars/min", "12 min 59 s ready · 123.4× listening")
                    else -> Metrics("Performance · Saved audio", "No audio measured yet")
                }
            }
            val updated = snapshot(includeModes = originalLabel != null)
            assertSnapshotEquals("${width}dp / ${fontScale}×: ${state.status}", initial, updated)
            assertTransportTargets(updated)
        }
    }

    private fun showReader(
        width: Int,
        height: Int,
        fontScale: Float,
        audio: MutableState<AudioState>,
        metrics: MutableState<Metrics>,
        showMetrics: MutableState<Boolean>,
        originalLabel: String? = "PDF",
        original: MutableState<Boolean> = mutableStateOf(false),
        showDetails: () -> Unit = {},
    ) {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MaterialTheme {
                    Column(Modifier.requiredSize(width.dp, height.dp)) {
                        Box(Modifier.fillMaxWidth().weight(1f).testTag(PAGE))
                        val performance: (@Composable () -> Unit)? = if (showMetrics.value) {
                            {
                                PerformanceSummary(generation = metrics.value.generation, buffer = metrics.value.buffer,
                                    showDetails = {}, hide = { showMetrics.value = false }, modifier = Modifier.testTag(METRICS))
                            }
                        } else null
                        ReaderFloatingDock(status = audio.value.status, busy = audio.value.busy,
                            hasIssue = audio.value.hasIssue, originalLabel = originalLabel, original = original.value,
                            changeView = { original.value = it }, showDetails = showDetails, showControls = {},
                            modifier = Modifier.testTag(DOCK), progress = audio.value.progress,
                            performance = performance,
                            transport = {
                                ReaderTransportControls(
                                    state = ReaderPlayback(preparing = audio.value.busy, fillingBuffer = audio.value.busy,
                                        wantsToPlay = audio.value.status == "Listening",
                                        error = audio.value.status.takeIf { audio.value.hasIssue }),
                                    speed = 1f, changeSpeed = {}, expanded = false, toggleControls = {},
                                    onPrevious = {}, onPrimary = {}, onNext = {}, modifier = Modifier.testTag(TRANSPORT))
                            })
                    }
                }
            }
        }
    }

    private fun snapshot(includeMetrics: Boolean = true, includeModes: Boolean = true): Bounds {
        compose.waitForIdle()
        fun tagged(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        fun described(label: String) = compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
        return Bounds(tagged(PAGE), tagged(DOCK), tagged(TRANSPORT), tagged("reader-primary-action"),
            described("Previous sentence"), described("Next sentence"), described("Open reader controls"),
            described("Playback speed, 1.0 times"),
            if (includeModes) described("Text view") else null, if (includeModes) described("PDF view") else null,
            if (includeMetrics) tagged(METRICS) else null)
    }

    private fun assertSnapshotEquals(context: String, expected: Bounds, actual: Bounds) {
        assertRectEquals("$context: page", expected.page, actual.page)
        assertRectEquals("$context: dock", expected.dock, actual.dock)
        assertRectEquals("$context: transport", expected.transport, actual.transport)
        assertRectEquals("$context: primary action", expected.primary, actual.primary)
        assertRectEquals("$context: previous action", expected.previous, actual.previous)
        assertRectEquals("$context: next action", expected.next, actual.next)
        assertRectEquals("$context: open controls action", expected.controls, actual.controls)
        assertRectEquals("$context: speed action", expected.speed, actual.speed)
        assertOptionalRectEquals("$context: Text button", expected.text, actual.text)
        assertOptionalRectEquals("$context: PDF button", expected.pdf, actual.pdf)
        assertOptionalRectEquals("$context: metrics", expected.metrics, actual.metrics)
    }

    private fun assertOptionalRectEquals(message: String, expected: Rect?, actual: Rect?) {
        if (expected != null) assertRectEquals(message, expected, requireNotNull(actual))
        else assertEquals("$message visibility", null, actual)
    }

    private fun assertTransportTargets(bounds: Bounds) {
        val primarySize = with(compose.density) { 58.dp.toPx() }
        val minimumTouchSize = with(compose.density) { 48.dp.toPx() }
        assertEquals("Primary button keeps its circular width", primarySize, bounds.primary.width, .1f)
        assertEquals("Primary button keeps its circular height", primarySize, bounds.primary.height, .1f)
        listOf("previous" to bounds.previous, "next" to bounds.next, "controls" to bounds.controls, "speed" to bounds.speed)
            .forEach { (name, target) ->
                assertTrue("$name keeps a 48dp touch width", target.width >= minimumTouchSize - .1f)
                assertTrue("$name keeps a 48dp touch height", target.height >= minimumTouchSize - .1f)
            }
        assertTrue("Previous does not overlap Play", bounds.previous.right <= bounds.primary.left)
        assertTrue("Next does not overlap Play", bounds.primary.right <= bounds.next.left)
    }

    private fun assertRectEquals(message: String, expected: Rect, actual: Rect) {
        assertEquals("$message left", expected.left, actual.left, .1f)
        assertEquals("$message top", expected.top, actual.top, .1f)
        assertEquals("$message right", expected.right, actual.right, .1f)
        assertEquals("$message bottom", expected.bottom, actual.bottom, .1f)
    }

    private data class AudioState(
        val status: String = "Ready to listen",
        val busy: Boolean = false,
        val hasIssue: Boolean = false,
        val progress: Float? = null,
    )

    private data class Metrics(
        val generation: String = "Performance · Waiting for audio",
        val buffer: String = "No audio measured yet",
    )

    private data class Bounds(
        val page: Rect, val dock: Rect, val transport: Rect, val primary: Rect,
        val previous: Rect, val next: Rect, val controls: Rect, val speed: Rect,
        val text: Rect?, val pdf: Rect?, val metrics: Rect?,
    )

    companion object {
        private const val PAGE = "reader-page"
        private const val DOCK = "floating-dock"
        private const val TRANSPORT = "transport"
        private const val METRICS = "performance-summary"
        private val audioStates = listOf(
            AudioState("Preparing audio…", busy = true),
            AudioState("Preparing audio · 0 of 4 ahead", busy = true, progress = 0f),
            AudioState("Preparing audio · 3 of 4 ahead", busy = true, progress = .75f),
            AudioState("Listening"),
            AudioState("Preparing next sentence · 0:02 ready", busy = true),
            AudioState("Reading stopped · " + "A very long explanation of an audio preparation problem. ".repeat(8), hasIssue = true),
            AudioState("Preparing page 123 of 456 · 1,234 audio chunks ready", busy = true, progress = .5f),
            AudioState("Cancelled · prepared audio kept"),
            AudioState(),
        )
    }
}
