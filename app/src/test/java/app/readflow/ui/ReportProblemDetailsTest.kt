package app.readflow.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportProblemDetailsTest {
    @Test fun technicalDetailsAreExcludedUntilTheUserChoosesToIncludeThem() {
        val technical = "{\"stage\":\"SYNTHESIZE\",\"appVersion\":\"test\"}"
        val body = ReportProblemDetails.body("Reading stopped", technical, includeTechnical = false)
        assertTrue(body.contains("Reading stopped"))
        assertFalse(body.contains(technical))
        assertFalse(body.contains("Technical details:"))
    }

    @Test fun consentIncludesOnlyThePreviewedTechnicalSummaryAndWrittenDescription() {
        val technical = "{\"stage\":\"SYNTHESIZE\"}"
        val body = ReportProblemDetails.body("Reading stopped", technical, includeTechnical = true)
        assertTrue(body.endsWith("Technical details:\n$technical"))
        assertTrue(body.contains("Description:\nReading stopped"))
    }

    @Test fun unavailableDiagnosticsDoNotPreventAUserWrittenReport() {
        val body = ReportProblemDetails.body("Reading stopped", null, includeTechnical = true)
        assertTrue(body.contains("Reading stopped"))
        assertFalse(body.contains("Technical details:"))
    }
}
