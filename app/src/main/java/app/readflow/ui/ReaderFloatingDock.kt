package app.readflow.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Only an explicit performance visibility change resizes the compact dock. */
@Composable internal fun ReaderFloatingDock(
    showControls: () -> Unit,
    modifier: Modifier = Modifier,
    performance: (@Composable () -> Unit)? = null,
    transport: @Composable () -> Unit,
) {
    val openControls by rememberUpdatedState(showControls)
    Box(modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("reader-floating-card").pointerInput(Unit) {
                var travel = 0f
                var opened = false
                detectVerticalDragGestures(onDragStart = { travel = 0f; opened = false }, onVerticalDrag = { change, amount ->
                    travel += amount
                    if (!opened && travel < -28.dp.toPx()) { opened = true; openControls() }
                    change.consume()
                })
            },
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f)),
            shadowElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                performance?.let { metrics ->
                    metrics()
                    HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
                transport()
            }
        }
    }
}
