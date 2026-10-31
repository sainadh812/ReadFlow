package app.readflow.diagnostics

/** Accepted means the SDK call returned, not that a report reached the server. */
enum class DiagnosticSubmissionOutcome { Accepted, Suppressed, Failed }

/** Applies limits when consuming an event, without exposing backend exceptions or reading input. */
class DiagnosticSubmission(
    private val sink: (TechnicalDiagnosticEvent) -> Unit,
    private val limiter: DiagnosticRateLimiter = DiagnosticRateLimiter(),
) {
    @Synchronized fun submit(event: TechnicalDiagnosticEvent, nowMs: Long): DiagnosticSubmissionOutcome {
        if (!limiter.shouldReport(event, nowMs)) return DiagnosticSubmissionOutcome.Suppressed
        return try {
            sink(event)
            DiagnosticSubmissionOutcome.Accepted
        } catch (_: Throwable) {
            // SDK linkage failures must not terminate the optional reporting worker either.
            // A later occurrence may retry this signature. Failed attempts still consume the hourly budget.
            limiter.forget(event)
            DiagnosticSubmissionOutcome.Failed
        }
    }

    @Synchronized fun clear() = limiter.clear()
}
