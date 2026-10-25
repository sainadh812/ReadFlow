package app.readflow.benchmark

/** Original synthetic text only: reports never contain a user's documents or generated audio. */
object BenchmarkFixtures {
    const val VERSION = "financial-page-2-raw-scoring"
    val SPEECH = listOf(
        "She saved \$1 million, or \$9.3 million adjusted for inflation.",
        "People aged 20-24 can compare income, expenses, and emergency savings.",
        "Milanković's example compares a 6 percent return with a 3 percent annual cost.",
    )
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
