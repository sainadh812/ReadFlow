package app.readflow

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.PowerManager
import app.readflow.data.*
import app.readflow.ingest.*
import app.readflow.models.ModelStore
import app.readflow.playback.ReadingCoordinator
import app.readflow.playback.PlaybackDiagnostics
import app.readflow.diagnostics.*
import kotlinx.coroutines.*
import java.io.File

class ReadFlowApp : Application() {
    val database by lazy { ReadFlowDatabase.open(this) }
    val documents by lazy { Documents(this, database, LocalExtraction(), ReadabilityArticles(this, issues), issues) }
    val preferences by lazy { PreferenceStore(this) }
    val models by lazy { ModelStore(this) }
    val cache by lazy { AudioCache(this, database) }
    val diagnostics by lazy { PlaybackDiagnostics(File(noBackupFilesDir, "diagnostics")) }
    val issues by lazy { IssueLogs(File(noBackupFilesDir, "issues"), ::issueEnvironment) }
    val playback by lazy { ReadingCoordinator(documents, models, cache, preferences, getSystemService(PowerManager::class.java), diagnostics, issues) }
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
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) playback.pressure() }
}
