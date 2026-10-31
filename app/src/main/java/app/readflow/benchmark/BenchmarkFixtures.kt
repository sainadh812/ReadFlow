package app.readflow.benchmark

/** Original synthetic text only: reports never contain a user's documents or generated audio. */
object BenchmarkFixtures {
    const val VERSION = "financial-page-2-raw-scoring"
    const val SPEECH_VERSION = "speech-financial-and-pronunciation-1"
    val SPEECH = listOf(
        "She saved \$1 million, or \$9.3 million adjusted for inflation.",
        "People aged 20-24 can compare income, expenses, and emergency savings.",
        "Milanković's example compares a 6 percent return with a 3 percent annual cost.",
    )
    val PRONUNCIATION = listOf(
        "Milanković's example compares income and expenses.",
        "He is 20-24 years old and saved \$9.3 million.",
        "Apple's iMac had 6 gigs in 1999 and 120 gigs in 2003.",
        "Please record a record of the present value.",
        "I read the report yesterday and will read the next one today.",
    )
    fun speechSamples(fixture: String): List<String> = when (fixture) {
        "financial" -> SPEECH
        "pronunciation" -> PRONUNCIATION
        else -> error("Unknown speech fixture: $fixture")
    }
    val PARAGRAPH_LINES = listOf(
        "A household saved \$1 million over twenty years.",
        "Its annual return was 6 percent before fees.",
        "People aged 20-24 can compare income and expenses.",
        "Milanković's example includes an emergency fund.",
    )
    val TABLE_ROWS = listOf(
        listOf("Year", "Income", "Expenses"),
        listOf("1999", "\$24,000", "\$18,000"),
        listOf("2003", "\$30,000", "\$21,000"),
        listOf("2006", "\$36,000", "\$24,000"),
    )
    val OCR_REFERENCE = (PARAGRAPH_LINES + TABLE_ROWS.map { it.joinToString(" ") }).joinToString("\n")
}
