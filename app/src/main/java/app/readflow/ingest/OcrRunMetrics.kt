package app.readflow.ingest

/** Timings for the most recently completed native OCR call, excluding document rendering. */
data class OcrRunMetrics(val cold: Boolean, val loadMs: Long, val layoutMs: Long,
    val recognitionMs: Long, val backend: String)
