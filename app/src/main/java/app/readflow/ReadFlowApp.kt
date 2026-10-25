package app.readflow

import android.app.Application
import android.os.PowerManager
import app.readflow.data.*
import app.readflow.ingest.*
import app.readflow.models.ModelStore
import app.readflow.playback.ReadingCoordinator
import app.readflow.playback.PlaybackDiagnostics
import app.readflow.playback.isMemoryPressureLevel
import app.readflow.diagnostics.*
import app.readflow.benchmark.*
import app.readflow.performance.BenchmarkCoordinator
import kotlinx.coroutines.*
import java.io.File

class ReadFlowApp : Application() {
    val database by lazy { ReadFlowDatabase.open(this) }
    val documents: Documents by lazy { Documents(this, database, LocalExtraction(), ReadabilityArticles(this, issues), issues, preferences, teleOcr,
        diagnostics, { benchmarks.busy.value }, paddleOcr) }
    val preferences by lazy { PreferenceStore(this) }
    val models by lazy { ModelStore(this) }
    val ocrModels by lazy { ModelStore(this, "ocr-model-manifests.json", "-ocr") }
    private val teleOcrInstance = lazy { TeleOcrEngine(this, ocrModels) }
    val teleOcr by teleOcrInstance
    private val paddleOcrInstance = lazy { PaddleOcrEngine(ocrModels) }
    val paddleOcr by paddleOcrInstance
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val cache by lazy { AudioCache(this, database) }
    val diagnostics by lazy { PlaybackDiagnostics(File(noBackupFilesDir, "diagnostics")) }
    val issues by lazy { IssueLogs(File(noBackupFilesDir, "issues"), ::issueEnvironment) }
    val githubSettings by lazy { GitHubSettings(this) }
    val githubDiagnostics by lazy { GitHubDiagnostics(GitHubRestApi()) }
    private val playbackInstance: Lazy<ReadingCoordinator> = lazy { ReadingCoordinator(documents, models, cache, preferences, getSystemService(PowerManager::class.java), diagnostics, issues,
        misakiOrtLibraryPath = app.readflow.speech.misaki.MisakiFrontend.ortLibraryPath(this),
        benchmarkActive = { benchmarks.busy.value }) }
    val playback: ReadingCoordinator by playbackInstance
    val benchmarks: BenchmarkCoordinator by lazy { BenchmarkCoordinator(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        BenchmarkStore(File(noBackupFilesDir, "benchmark")),
        prepare = { playback.releaseForBenchmark(); documents.awaitExtractionIdle(); teleOcr.close(); paddleOcr.close() },
        execute = { options, update -> BenchmarkRunner(this, models, ocrModels, teleOcr, paddleOcr).run(options, update) }) }
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { issues.recordCrash(error) } catch (_: Throwable) { }
            previous?.uncaughtException(thread, error)
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            issues.refresh()
            recoverProcessIssues(this@ReadFlowApp, issues)
        }
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (isMemoryPressureLevel(level)) {
            if (playbackInstance.isInitialized()) playback.pressure()
            // Engines own their locks until native work drains. Do not free a live inference.
            appScope.launch {
                // Release an idle engine immediately even while the other one drains
                // a long inference. The two model owners have independent locks.
                if (teleOcrInstance.isInitialized()) launch { teleOcr.close() }
                if (paddleOcrInstance.isInitialized()) launch { paddleOcr.close() }
            }
        }
    }
}
