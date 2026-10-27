package app.readflow.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CrashReportingState(
    val configured: Boolean,
    val enabled: Boolean,
    val restartRequired: Boolean,
    val message: String? = null,
)

interface ReportingPreference {
    fun readEnabled(): Boolean
    /** Must durably save the choice before returning true. */
    fun saveEnabled(enabled: Boolean): Boolean
}

interface ReportingBackend {
    fun start()
    fun disableAndRequestDeletion()
}

/**
 * The backend can be initialized only once, at process startup with previously saved consent.
 * Turning it off blocks new app reports immediately. SDK handlers may remain installed until exit,
 * so turning it on again always waits for a new process. No exception text crosses this boundary.
 */
class CrashReportingConsent(
    private val configured: Boolean,
    private val preference: ReportingPreference,
    private val backend: ReportingBackend,
) {
    private val lock = Any()
    private val mutable = MutableStateFlow(CrashReportingState(configured, false, false,
        if (configured) null else UNAVAILABLE))
    val state: StateFlow<CrashReportingState> = mutable.asStateFlow()
    private var startupHandled = false
    private var backendAttempted = false
    private var recordingAllowed = false
    private var deletionRequested = false
    private var deletionFailed = false

    val canRecord: Boolean get() = synchronized(lock) { recordingAllowed }

    /** True even after partial startup failure: an SDK handler may already be installed. */
    val sdkStarted: Boolean get() = synchronized(lock) { backendAttempted }

    fun start() = synchronized(lock) {
        if (startupHandled) return@synchronized
        startupHandled = true
        if (!configured) return@synchronized
        val optedIn = try { preference.readEnabled() } catch (_: Throwable) {
            mutable.value = CrashReportingState(true, false, false,
                "Could not read your diagnostics choice. Reporting is off for this session.")
            return@synchronized
        }
        if (!optedIn) return@synchronized
        backendAttempted = true
        try {
            backend.start()
            recordingAllowed = true
            mutable.value = CrashReportingState(true, true, false)
        } catch (_: Throwable) {
            recordingAllowed = false
            disableBackendOnce()
            mutable.value = CrashReportingState(true, true, true,
                "Automatic diagnostics could not start. New app reports are blocked; restart to try again." +
                    if (deletionFailed) " Pending-report deletion could not be confirmed." else "")
        }
    }

    fun setEnabled(enabled: Boolean) = synchronized(lock) {
        // An early settings change must never become implicit startup consent later in this process.
        startupHandled = true
        if (!configured) {
            mutable.value = CrashReportingState(false, false, false, UNAVAILABLE)
            return@synchronized
        }
        if (enabled) {
            val saved = try { preference.saveEnabled(true) } catch (_: Throwable) { false }
            if (!saved) {
                mutable.value = mutable.value.copy(message = "Could not save your diagnostics choice. Try again.")
                return@synchronized
            }
            mutable.value = CrashReportingState(true, true, !recordingAllowed,
                if (recordingAllowed) null else "Restart the app to enable automatic diagnostics.")
        } else {
            // Gate before persistence/SDK calls, including when either operation fails.
            recordingAllowed = false
            val saved = try { preference.saveEnabled(false) } catch (_: Throwable) { false }
            disableBackendOnce()
            val message = when {
                !saved -> "New app reports are blocked, but your diagnostics choice could not be saved. " +
                    "Restarting may restore the previous setting; try turning this off again." +
                    if (deletionFailed) " Disabling the crash service or deleting pending reports could not be confirmed." else ""
                deletionFailed -> "New app reports are blocked. Disabling the crash service or deleting pending reports " +
                    "could not be confirmed; restart the app to finish turning diagnostics off."
                backendAttempted -> "New app reports are blocked and deletion of pending reports was requested. " +
                    "Restart the app to finish turning diagnostics off."
                else -> null
            }
            mutable.value = CrashReportingState(true, false, backendAttempted, message)
        }
    }

    /** Caller holds lock. A partial start needs cleanup too; repeated toggles never re-enter the SDK. */
    private fun disableBackendOnce() {
        if (!backendAttempted || deletionRequested) return
        deletionRequested = true
        try { backend.disableAndRequestDeletion() } catch (_: Throwable) { deletionFailed = true }
    }

    private companion object {
        const val UNAVAILABLE = "Automatic diagnostics are unavailable in this build. You can still report a problem by email."
    }
}
