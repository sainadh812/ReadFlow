package app.readflow.data

/** Stored IDs are independent of Compose and font resources, so upgrades keep reader choices. */
internal object ReaderAppearancePreferences {
    const val WARM = "warm_paperback"
    const val CRISP = "crisp_paper"
    const val EVENING = "evening_paper"
    const val SYSTEM_FONT = "system_serif"
    val themes = setOf(WARM, CRISP, EVENING)
    val fonts = setOf(SYSTEM_FONT, "opendyslexic", "atkinson", "lexend", "literata", "source_serif", "noto_sans")

    /** Only migrate the old app-wide dark choice when no valid reader theme has been saved. */
    fun theme(stored: String?, legacyDark: Boolean): String =
        stored?.takeIf { it in themes } ?: if (legacyDark) EVENING else WARM

    fun font(stored: String?): String = stored?.takeIf { it in fonts } ?: SYSTEM_FONT

    fun normalize(value: Preferences): Preferences = value.copy(
        readerTheme = theme(value.readerTheme, value.dark),
        readerFont = font(value.readerFont),
        fontSize = value.fontSize.coerceIn(5, 34),
    )
}
