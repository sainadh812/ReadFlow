package app.readflow.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SupportTipDetailsTest {
    @Test fun amountsUseExactBoundedRupees() {
        assertEquals("1.00", SupportTipDetails.normalizedAmount("1"))
        assertEquals("10.00", SupportTipDetails.normalizedAmount("10"))
        assertEquals("50.00", SupportTipDetails.normalizedAmount("50"))
        assertEquals("100.00", SupportTipDetails.normalizedAmount("100"))
        assertEquals("12.35", SupportTipDetails.normalizedAmount(" 12.35 "))
        assertEquals("1000.00", SupportTipDetails.normalizedAmount("1000.00"))
    }

    @Test fun invalidOrAmbiguousAmountsNeverBecomePaymentRequests() {
        listOf("", " ", "0", "0.99", "-10", "+10", "1000.01", "10000", "10.999", "1e2",
            "NaN", "Infinity", "10,50", "1,000", "₹10", "١٠", "10&pa=other@ybl", "1.2.3").forEach { input ->
            assertNull(input, SupportTipDetails.normalizedAmount(input))
            assertNull(input, SupportTipDetails.paymentParameters(input))
        }
    }

    @Test fun requestKeepsOwnerRecipientAndDoesNotInventMerchantOrTransactionData() {
        val parameters = SupportTipDetails.paymentParameters("10.50")!!
        assertEquals("readassit@ybl", parameters["pa"])
        assertEquals("Read Assit", parameters["pn"])
        assertEquals("10.50", parameters["am"])
        assertEquals("INR", parameters["cu"])
        assertEquals(setOf("pa", "pn", "tn", "am", "cu"), parameters.keys)
    }
}
