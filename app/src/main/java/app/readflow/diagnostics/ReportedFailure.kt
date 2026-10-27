package app.readflow.diagnostics

/** Only this synthetic throwable crosses the reporting boundary, never the original exception. */
internal class ReportedFailure(message: String) : RuntimeException(message)

internal fun TechnicalDiagnosticEvent.asReportedFailure(): Throwable {
    val root = ReportedFailure(code)
    root.stackTrace = failures.firstOrNull()?.frames.orEmpty().map { it.asStackTraceElement() }.toTypedArray()
    var parent: Throwable = root
    for (failure in failures.drop(1)) {
        val cause = ReportedFailure(failure.type)
        cause.stackTrace = failure.frames.map { it.asStackTraceElement() }.toTypedArray()
        parent.initCause(cause)
        parent = cause
    }
    return root
}

internal fun sanitizedFatal(error: Throwable): Throwable = TechnicalDiagnostics.from(IssueReport(
    id = "", timestampMs = 0, stage = "UNCAUGHT_JAVA", severity = "ERROR", environment = emptyMap(),
    input = IssueInput(), failures = generateSequence(error) { it.cause }.take(4).map {
        IssueFailure(it.javaClass.name, null, it.stackTrace.take(24).map(StackTraceElement::toString))
    }.toList(),
)).asReportedFailure()
