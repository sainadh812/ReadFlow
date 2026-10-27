package app.readflow.ui

/** The preview and email use this same text. No original issue attachment is ever added. */
internal object ReportProblemDetails {
    const val SUPPORT_EMAIL = "xperimenting.ai@gmail.com"
    const val SUBJECT = "Read Assist problem report"
    const val DESCRIPTION_LIMIT = 4000

    fun body(description: String, technicalReport: String?, includeTechnical: Boolean): String = buildString {
        append("Read Assist problem report\n\n")
        append("Description:\n")
        append(description.take(DESCRIPTION_LIMIT).ifBlank { "No description provided." })
        if (includeTechnical && !technicalReport.isNullOrBlank()) {
            append("\n\nTechnical details:\n")
            append(technicalReport)
        }
    }
}
