package app.readflow.ui

import java.math.BigDecimal
import java.math.RoundingMode

/** Public recipient supplied by the owner; this is not a bank-verified identity. */
internal object SupportTipDetails {
    const val UPI_ID = "readassit@ybl"
    const val DISPLAY_NAME = "Read Assit"
    private val decimalAmount = Regex("[0-9]{1,4}(\\.[0-9]{1,2})?")
    private val maximum = BigDecimal("1000.00")

    /** Exact rupees, never floating point, exponents, signs or URI fragments. */
    fun normalizedAmount(input: String): String? {
        val value = input.trim()
        if (!decimalAmount.matches(value)) return null
        val amount = value.toBigDecimalOrNull() ?: return null
        if (amount < BigDecimal.ONE || amount > maximum) return null
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString()
    }

    fun paymentParameters(input: String): Map<String, String>? {
        val amount = normalizedAmount(input) ?: return null
        return linkedMapOf(
            "pa" to UPI_ID,
            "pn" to DISPLAY_NAME,
            "tn" to "Optional tip for Read Assit; no purchase or benefits",
            "am" to amount,
            "cu" to "INR",
        )
    }
}
