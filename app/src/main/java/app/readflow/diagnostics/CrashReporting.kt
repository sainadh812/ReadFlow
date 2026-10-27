package app.readflow.diagnostics

import android.content.Context
import android.os.SystemClock
import app.readflow.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.CustomKeysAndValues
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** No Firebase API is called until a new app process starts with saved, explicit consent. */
class CrashReporting(context: Context, scope: CoroutineScope) {
    private val submissionLock = Any()
    private val backend = FirebaseReportingBackend(context.applicationContext)
    private val consent = CrashReportingConsent(BuildConfig.FIREBASE_CONFIGURED,
        AndroidReportingPreference(context.applicationContext), backend)
    val state = consent.state
    private val limiter = DiagnosticRateLimiter()
    // Never retain IssueReport or reading contents in the reporting queue.
    private val events = Channel<TechnicalDiagnosticEvent>(8, BufferOverflow.DROP_OLDEST)
    private val fallbackFatal = ReportedFailure("UNCAUGHT_JAVA_OtherFailure").apply { stackTrace = emptyArray() }

    init {
        scope.launch {
            for (event in events) {
                synchronized(submissionLock) {
                    if (consent.canRecord) {
                        try { backend.record(event) } catch (_: Exception) { /* Optional reporting cannot break reading. */ }
                    }
                }
            }
        }
    }

    fun start() = synchronized(submissionLock) { consent.start() }

    fun setEnabled(enabled: Boolean) = synchronized(submissionLock) {
        consent.setEnabled(enabled)
        if (!consent.canRecord) {
            while (events.tryReceive().isSuccess) { /* Discard queued app reports on opt-out. */ }
            limiter.clear()
        }
    }

    fun record(report: IssueReport) {
        // Fatal Java exceptions are handled by the SDK's uncaught handler, once.
        if (!consent.canRecord || report.stage == "UNCAUGHT_JAVA") return
        val event = TechnicalDiagnostics.from(report)
        if (limiter.shouldReport(event, SystemClock.elapsedRealtime()) && consent.canRecord) events.trySend(event)
    }

    /** Preserve the full local report, but strip messages/input before forwarding to the SDK handler. */
    fun throwableForUncaughtHandler(error: Throwable): Throwable = if (!consent.sdkStarted) error else {
        try { sanitizedFatal(error) } catch (_: Throwable) { fallbackFatal }
    }
}

private class AndroidReportingPreference(context: Context) : ReportingPreference {
    private val preferences = context.getSharedPreferences("optional_crash_reporting", Context.MODE_PRIVATE)
    override fun readEnabled(): Boolean = preferences.getBoolean("enabled", false)
    // Commit the rare explicit consent change before enabling any future SDK startup.
    override fun saveEnabled(enabled: Boolean): Boolean = preferences.edit().putBoolean("enabled", enabled).commit()
}

private class FirebaseReportingBackend(private val context: Context) : ReportingBackend {
    private var sdk: FirebaseCrashlytics? = null
    override fun start() {
        val app = checkNotNull(FirebaseApp.initializeApp(context))
        app.setDataCollectionDefaultEnabled(false)
        sdk = FirebaseCrashlytics.getInstance()
        sdk!!.setCrashlyticsCollectionEnabled(true)
    }

    override fun disableAndRequestDeletion() {
        // Partial initialization can install handlers without yielding an SDK instance.
        // Surface unconfirmed cleanup to the consent controller instead of claiming a request.
        val service = checkNotNull(sdk) { "Crash reporting cleanup is unavailable" }
        try { service.setCrashlyticsCollectionEnabled(false) }
        finally {
            // This is a request, not a synchronous deletion guarantee. Native hooks remain until restart.
            service.deleteUnsentReports()
        }
    }

    fun record(event: TechnicalDiagnosticEvent) {
        val keys = CustomKeysAndValues.Builder().putString("stage", event.stage)
            .putString("category", event.category).putString("failure_code", event.code)
            .putString("severity", event.severity)
        event.environment.forEach { (key, value) -> keys.putString("environment_$key", value) }
        event.configuration.forEach { (key, value) -> keys.putString("configuration_$key", value) }
        sdk?.recordException(event.asReportedFailure(), keys.build())
    }
}
