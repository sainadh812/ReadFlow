package app.readflow.ingest

/** Processing effort is independent of the selected OCR engine. */
enum class OcrQuality(
    val id: String,
    val pdfDpi: Int,
    val maxSourcePixels: Int,
    val maxRotatedPixels: Int,
    val lineRetries: Int,
    val retryConfidence: Float,
    val automaticDeskew: Boolean,
) {
    FAST("fast", 200, 4_000_000, 5_000_000, 0, .82f, false),
    // The existing pipeline remains the default for both upgrades and new installs.
    BALANCED("balanced", 300, 10_000_000, 12_000_000, 6, .82f, true),
    // More uncertain lines are checked, without increasing the peak bitmap budget
    // or weakening the safeguards that prevent retries from changing reliable text.
    HIGH("high", 300, 10_000_000, 12_000_000, 12, .90f, true);

    companion object {
        fun fromId(id: String?): OcrQuality = entries.firstOrNull { it.id == id } ?: BALANCED
    }
}
