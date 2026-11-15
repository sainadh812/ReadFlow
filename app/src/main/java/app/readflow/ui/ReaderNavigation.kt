package app.readflow.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One history for tabs and nested destinations; returning to an existing route removes loops. */
internal class ReaderNavigation {
    private val stack = mutableListOf("library")
    private val mutable = MutableStateFlow("library")
    val screen = mutable.asStateFlow()
    val history: List<String> get() = stack.toList()

    fun navigate(destination: String) {
        require(destination in ROUTES) { "Unknown destination: $destination" }
        if (stack.last() == destination) return
        val existing = stack.indexOf(destination)
        if (existing >= 0) while (stack.size > existing + 1) stack.removeAt(stack.lastIndex)
        else stack += destination
        mutable.value = stack.last()
    }

    fun back(): Boolean {
        if (stack.size == 1) return false
        stack.removeAt(stack.lastIndex)
        mutable.value = stack.last()
        return true
    }

    private companion object { val ROUTES = setOf("library", "reader", "voices", "settings", "legal", "ocr", "benchmark", "issues") }
}
