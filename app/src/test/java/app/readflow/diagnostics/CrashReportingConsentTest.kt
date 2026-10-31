package app.readflow.diagnostics

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class CrashReportingConsentTest {
    private class Preference(var enabled: Boolean = false) : ReportingPreference {
        var reads = 0
        val writes = mutableListOf<Boolean>()
        var rejectSave = false
        var throwOnSave = false
        var throwOnRead = false
        override fun readEnabled(): Boolean {
            reads++
            if (throwOnRead) error("private preference failure")
            return enabled
        }
        override fun saveEnabled(enabled: Boolean): Boolean {
            writes += enabled
            if (throwOnSave) error("private write failure")
            if (rejectSave) return false
            this.enabled = enabled
            return true
        }
    }

    private class Backend : ReportingBackend {
        var starts = 0
        var deletions = 0
        var throwOnStart = false
        var throwOnDelete = false
        override fun start() {
            starts++
            if (throwOnStart) error("private backend startup failure")
        }
        override fun disableAndRequestDeletion() {
            deletions++
            if (throwOnDelete) error("private deletion failure")
        }
    }

    @Test fun absentConsentDoesNotTouchBackendOrPersistAnything() {
        val preference = Preference()
        val backend = Backend()
        val consent = CrashReportingConsent(true, preference, backend)
        repeat(3) { consent.start() }
        assertEquals(1, preference.reads); assertTrue(preference.writes.isEmpty())
        assertEquals(0, backend.starts); assertEquals(0, backend.deletions)
        assertFalse(consent.canRecord); assertFalse(consent.sdkStarted)
        assertEquals(CrashReportingState(true, false, false), consent.state.value)
    }

    @Test fun missingConfigurationDoesNotReadStoreConsentOrStartBackend() {
        val preference = Preference(true)
        val backend = Backend()
        val consent = CrashReportingConsent(false, preference, backend)
        consent.start(); consent.setEnabled(true); consent.setEnabled(false)
        assertEquals(0, preference.reads); assertTrue(preference.writes.isEmpty())
        assertEquals(0, backend.starts); assertEquals(0, backend.deletions)
        assertFalse(consent.canRecord); assertFalse(consent.sdkStarted)
        assertFalse(consent.state.value.configured); assertFalse(consent.state.value.enabled)
        assertFalse(consent.state.value.restartRequired); assertNotNull(consent.state.value.message)
    }

    @Test fun savedConsentStartsBackendOnceAndAllowsReportsOnlyAfterSuccess() {
        val preference = Preference(true)
        val backend = Backend()
        val consent = CrashReportingConsent(true, preference, backend)
        assertFalse(consent.canRecord)
        repeat(3) { consent.start() }
        assertEquals(1, backend.starts); assertTrue(consent.sdkStarted); assertTrue(consent.canRecord)
        assertEquals(CrashReportingState(true, true, false, active = true), consent.state.value)
        consent.setEnabled(true)
        assertEquals(1, backend.starts); assertTrue(consent.canRecord)
        assertFalse(consent.state.value.restartRequired)
    }

    @Test fun newOptInNeedsAnotherProcessAndDoesNotInitializeSdk() {
        val preference = Preference()
        val backend = Backend()
        val consent = CrashReportingConsent(true, preference, backend)
        consent.start(); consent.setEnabled(true); consent.start()
        assertTrue(preference.enabled); assertTrue(consent.state.value.enabled)
        assertTrue(consent.state.value.restartRequired); assertFalse(consent.canRecord)
        assertFalse(consent.state.value.active)
        assertFalse(consent.sdkStarted); assertEquals(0, backend.starts)
        val nextProcess = CrashReportingConsent(true, preference, backend)
        nextProcess.start()
        assertTrue(nextProcess.canRecord); assertEquals(1, backend.starts)
    }

    @Test fun settingConsentBeforeStartupCannotInitializeBackendInSameProcess() {
        val preference = Preference()
        val backend = Backend()
        val consent = CrashReportingConsent(true, preference, backend)
        consent.setEnabled(true); consent.start()
        assertEquals(0, backend.starts); assertFalse(consent.canRecord)
        assertTrue(consent.state.value.restartRequired)
    }

    @Test fun turningOffStopsAppReportsAndRequestsDeletionOnceEvenAfterReEnabling() {
        val preference = Preference(true)
        val backend = Backend()
        val consent = CrashReportingConsent(true, preference, backend)
        consent.start(); consent.setEnabled(false)
        assertFalse(preference.enabled); assertFalse(consent.canRecord)
        assertFalse(consent.state.value.enabled); assertTrue(consent.state.value.restartRequired)
        assertEquals(1, backend.deletions); assertTrue(consent.sdkStarted)
        consent.setEnabled(true); consent.start()
        assertTrue(preference.enabled); assertTrue(consent.state.value.enabled)
        assertFalse(consent.canRecord); assertTrue(consent.state.value.restartRequired)
        consent.setEnabled(false)
        assertEquals(1, backend.starts); assertEquals(1, backend.deletions)
    }

    @Test fun failedOptInIsNotPresentedAsSavedAndDoesNotStartSdk() {
        for (throwing in listOf(false, true)) {
            val preference = Preference().apply { rejectSave = !throwing; throwOnSave = throwing }
            val backend = Backend()
            val consent = CrashReportingConsent(true, preference, backend)
            consent.start(); consent.setEnabled(true)
            assertFalse(preference.enabled); assertFalse(consent.state.value.enabled)
            assertFalse(consent.canRecord); assertFalse(consent.sdkStarted)
            assertEquals(0, backend.starts); assertFalse(consent.state.value.message!!.contains("private"))
        }
    }

    @Test fun failedOptOutStillBlocksReportsAndWarnsThatRestartCanRestorePreference() {
        for (throwing in listOf(false, true)) {
            val preference = Preference(true).apply { rejectSave = !throwing; throwOnSave = throwing }
            val backend = Backend()
            val consent = CrashReportingConsent(true, preference, backend)
            consent.start(); consent.setEnabled(false)
            assertTrue(preference.enabled); assertFalse(consent.state.value.enabled); assertFalse(consent.canRecord)
            assertEquals(1, backend.deletions)
            assertTrue(consent.state.value.message!!.contains("Restarting may restore the previous setting"))
            assertFalse(consent.state.value.message!!.contains("private"))
            preference.rejectSave = false; preference.throwOnSave = false
            consent.setEnabled(false)
            assertFalse(preference.enabled); assertEquals(1, backend.deletions)
        }
    }

    @Test fun reportGateClosesBeforeSavingOrCallingBackend() {
        lateinit var consent: CrashReportingConsent
        var gatedAtSave = false
        var gatedAtDelete = false
        val preference = object : ReportingPreference {
            override fun readEnabled() = true
            override fun saveEnabled(enabled: Boolean): Boolean {
                gatedAtSave = !consent.canRecord
                return true
            }
        }
        val backend = object : ReportingBackend {
            override fun start() = Unit
            override fun disableAndRequestDeletion() { gatedAtDelete = !consent.canRecord }
        }
        consent = CrashReportingConsent(true, preference, backend)
        consent.start(); consent.setEnabled(false)
        assertTrue(gatedAtSave); assertTrue(gatedAtDelete)
    }

    @Test fun preferenceReadFailureLeavesServiceOffAndDoesNotOverwriteUnknownChoice() {
        val preference = Preference(true).apply { throwOnRead = true }
        val backend = Backend()
        val consent = CrashReportingConsent(true, preference, backend)
        consent.start(); consent.start()
        assertEquals(1, preference.reads); assertTrue(preference.writes.isEmpty())
        assertFalse(consent.canRecord); assertFalse(consent.sdkStarted)
        assertEquals(0, backend.starts); assertNotNull(consent.state.value.message)
        assertFalse(consent.state.value.message!!.contains("private"))
    }

    @Test fun partialInitializationIsTreatedAsInstalledButNeverAllowsAppReports() {
        val preference = Preference(true)
        val backend = Backend().apply { throwOnStart = true }
        val consent = CrashReportingConsent(true, preference, backend)
        consent.start(); consent.start()
        assertTrue(consent.sdkStarted); assertFalse(consent.canRecord)
        assertTrue(consent.state.value.enabled); assertTrue(consent.state.value.restartRequired)
        assertFalse(consent.state.value.active)
        assertEquals(1, backend.starts); assertEquals(1, backend.deletions)
        assertTrue(consent.state.value.message!!.contains("could not start"))
        assertFalse(consent.state.value.message!!.contains("private"))
        consent.setEnabled(false); consent.setEnabled(true)
        assertFalse(consent.canRecord); assertEquals(1, backend.starts); assertEquals(1, backend.deletions)
    }

    @Test fun backendDeletionFailureCannotRestoreReportingOrClaimDeletionSucceeded() {
        val preference = Preference(true)
        val backend = Backend().apply { throwOnDelete = true }
        val consent = CrashReportingConsent(true, preference, backend)
        consent.start(); consent.setEnabled(false)
        assertFalse(consent.canRecord); assertFalse(preference.enabled)
        assertTrue(consent.state.value.message!!.contains("could not be confirmed"))
        assertFalse(consent.state.value.message!!.contains("private"))
        consent.setEnabled(false)
        assertEquals(1, backend.deletions)
        assertTrue(consent.state.value.message!!.contains("could not be confirmed"))
    }

    @Test fun simultaneousStartupCallsInitializeExactlyOnce() {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val preference = Preference(true)
        val backend = object : ReportingBackend {
            override fun start() {
                calls.incrementAndGet(); entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            override fun disableAndRequestDeletion() = Unit
        }
        val consent = CrashReportingConsent(true, preference, backend)
        val first = Thread { consent.start() }
        val second = Thread { consent.start() }
        first.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); second.start()
        } finally { release.countDown(); first.join(5000); second.join(5000) }
        assertFalse(first.isAlive); assertFalse(second.isAlive)
        assertEquals(1, calls.get()); assertTrue(consent.canRecord)
    }

    @Test fun submissionStatusDistinguishesSdkAcceptanceFromEnabledPreference() {
        val consent = CrashReportingConsent(true, Preference(true), Backend())
        consent.onSubmissionAccepted("SYNTHESIZE_IllegalArgumentException")
        consent.onSubmissionFailed()
        assertEquals(0, consent.state.value.submittedThisSession)
        assertFalse(consent.state.value.submissionFailed)
        consent.start()
        consent.onSubmissionFailed()
        assertTrue(consent.state.value.enabled)
        assertTrue(consent.state.value.active)
        assertTrue(consent.state.value.submissionFailed)
        assertEquals(0, consent.state.value.submittedThisSession)
        consent.onSubmissionAccepted("SYNTHESIZE_IllegalArgumentException")
        assertFalse(consent.state.value.submissionFailed)
        assertEquals(1, consent.state.value.submittedThisSession)
        assertEquals("SYNTHESIZE_IllegalArgumentException", consent.state.value.lastFailureCode)
        consent.setEnabled(true)
        assertEquals(1, consent.state.value.submittedThisSession)
        consent.setEnabled(false)
        consent.onSubmissionAccepted("NORMALIZE_TEXT_NormalizationException")
        consent.onSubmissionFailed()
        assertFalse(consent.state.value.active)
        assertFalse(consent.state.value.submissionFailed)
        assertEquals(0, consent.state.value.submittedThisSession)
        assertNull(consent.state.value.lastFailureCode)
    }
}
