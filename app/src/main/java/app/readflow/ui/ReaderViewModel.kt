package app.readflow.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.readflow.BuildConfig
import app.readflow.ReadFlowApp
import app.readflow.core.*
import app.readflow.data.*
import app.readflow.diagnostics.*
import app.readflow.benchmark.BenchmarkOptions
import app.readflow.benchmark.BenchmarkFixtures
import app.readflow.ingest.ArticleImportException
import app.readflow.models.VoicePack
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as ReadFlowApp
    val playback = app.playback.state
    val library = app.documents.dao.library().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val preferences = app.preferences.values.stateIn(viewModelScope, SharingStarted.Eagerly, Preferences())
    val downloads = app.models.progress
    val ocrDownloads = app.ocrModels.progress
    val ocrProgress = combine(app.teleOcr.progress, app.paddleOcr.progress) { tele, paddle -> tele ?: paddle }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val ocrLastRun = combine(app.teleOcr.lastRun, app.paddleOcr.lastRun, preferences) { tele, paddle, prefs ->
        when (prefs.ocrEngine) {
            "paddleocr" -> paddle
            "teleocr" -> tele
            else -> null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val performance = app.diagnostics.performance
    val ocrPerformance = app.diagnostics.extractionPerformance
    val benchmark = app.benchmarks.state
    val benchmarkBusy = app.benchmarks.busy
    val screen = MutableStateFlow("library")
    val message = MutableStateFlow<String?>(null)
    val importing = MutableStateFlow(false)
    val refreshingText = MutableStateFlow(false)
    val issueReports = app.issues.reports
    val crashReportingState = app.crashReporting.state
    val selectedIssue = MutableStateFlow<IssueReport?>(null)
    var pendingIssueExport: String? = null
    val githubConfiguration by lazy { app.githubSettings.configuration }
    val sendingIssues = MutableStateFlow(false)
    val githubIssueUrl = MutableStateFlow<String?>(null)
    private val jobs = mutableMapOf<String, Job>()
    private val ocrDownloadJobs = mutableMapOf<String, Job>()
    private var ocrRefresh: Job? = null
    private val activeOperations = ConcurrentHashMap.newKeySet<Job>()
    private fun benchmarkBlocksWork(): Boolean = app.benchmarks.busy.value.also {
        if (it) message.value = "Finish or cancel the phone benchmark first"
    }
    fun open(document: DocumentEntity) {
        if (benchmarkBlocksWork()) return
        ocrRefresh?.cancel(); app.playback.open(document); screen.value = "reader"
    }
    fun import(uri: Uri) = launchImport("IMPORT_FILE", { withContext(Dispatchers.IO) {
        val title = runCatching { app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull()
        IssueInput(title = title, source = issueSource(uri.toString()), mime = runCatching { app.contentResolver.getType(uri) }.getOrNull())
    } }) { app.documents.import(uri) }
    fun importUrl(url: String) = launchImport("IMPORT_ARTICLE", { IssueInput(source = issueSource(url.trim()), mime = "text/html") }) { app.documents.importUrl(url.trim()) }
    private fun launchImport(stage: String, input: suspend () -> IssueInput, action: suspend () -> DocumentEntity) { viewModelScope.launch {
        if (benchmarkBlocksWork()) return@launch
        importing.value = true
        try { open(action()) } catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) {
            if (e !is ArticleImportException) app.issues.record(stage, e, input())
            message.value = e.message ?: "Import failed"
        }
        finally { importing.value = false }
    } }
    fun update(preferences: Preferences) { viewModelScope.launch { app.preferences.set(preferences) } }
    fun chooseOcrEngine(engine: String) {
        if (benchmarkBlocksWork()) return
        if (engine !in setOf("mlkit", "teleocr", "paddleocr")) return
        if (engine != "mlkit" && !app.ocrModels.installed(engine)) {
            message.value = "Download ${modelDisplayName(engine)} before selecting it"; return
        }
        app.playback.stop()
        operation("OCR_MODEL_SWITCH") {
            ocrRefresh?.cancelAndJoin()
            app.teleOcr.close(); app.paddleOcr.close()
            app.preferences.set(app.preferences.values.first().copy(ocrEngine = engine))
            message.value = "${modelDisplayName(engine)} selected · apply to the current page to update saved text"
        }
    }
    fun startBenchmark(options: BenchmarkOptions) {
        if (app.benchmarks.busy.value) return
        if (importing.value || activeOperations.any { it.isActive } ||
            downloads.value.values.any { it.busy } || ocrDownloads.value.values.any { it.busy }) {
            message.value = "Let imports and model setup finish before benchmarking"; return
        }
        ocrRefresh?.cancel()
        try {
            app.benchmarks.start(options)
            screen.value = "benchmark"
        } catch (error: IllegalArgumentException) { message.value = error.message }
    }
    fun cancelBenchmark(reason: String = "Stopped by user") = app.benchmarks.cancel(reason)
    fun leaveBenchmark() { app.benchmarks.cancel("Left the testbench"); screen.value = "settings" }
    fun copyBenchmarkResults() {
        try {
            app.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText("Read Assist phone benchmark", app.benchmarks.report()))
            message.value = "Benchmark results copied"
        } catch (_: Exception) { message.value = "Could not copy benchmark results" }
    }
    fun downloadOcr(id: String) {
        if (id !in setOf("teleocr", "paddleocr") || ocrDownloadJobs[id]?.isActive == true) return
        ocrDownloadJobs[id] = operation("OCR_MODEL_INSTALL") { app.ocrModels.install(id) }
    }
    fun cancelOcrDownload(id: String) { ocrDownloadJobs[id]?.cancel() }
    fun deleteOcr(id: String) { operation("OCR_MODEL_DELETE") {
        require(id in setOf("teleocr", "paddleocr"))
        ocrDownloadJobs[id]?.cancelAndJoin()
        cancelOcr(); ocrRefresh?.join()
        val prefs = app.preferences.values.first()
        if (prefs.ocrEngine == id) app.preferences.set(prefs.copy(ocrEngine = "mlkit"))
        if (id == "teleocr") app.teleOcr.deleteModels() else app.paddleOcr.deleteModels()
        message.value = "${modelDisplayName(id)} removed"
    } }
    fun downloadTeleOcr() = downloadOcr("teleocr")
    fun cancelTeleOcrDownload() = cancelOcrDownload("teleocr")
    fun deleteTeleOcr() = deleteOcr("teleocr")
    fun updateSpeechPerformance(prefs: Preferences) {
        if (benchmarkBlocksWork()) return
        app.playback.stop()
        update(prefs)
    }
    fun cancelOcr() { ocrRefresh?.cancel(); app.playback.stop() }
    fun download(id: String) {
        if (jobs[id]?.isActive == true) return
        jobs[id] = operation("MODEL_INSTALL", IssueInput(details = mapOf("model" to id))) { app.models.install(id) }
    }
    fun cancelDownload(id: String) { jobs[id]?.cancel() }
    fun deleteModel(id: String) { operation("MODEL_DELETE", IssueInput(details = mapOf("model" to id))) { jobs[id]?.cancelAndJoin(); app.playback.deleteModel(id) } }
    fun chooseModel(model: String, voice: String) { operation("MODEL_SWITCH", IssueInput(details = mapOf("model" to model, "voice" to voice))) { app.playback.changeModel(model, voice) } }
    fun chooseKokoroPhonemizer(phonemizer: String) { operation("PHONEMIZER_SWITCH",
        IssueInput(details = mapOf("model" to "kokoro", "synthesis" to "phonemizer=$phonemizer"))) {
        app.playback.changeKokoroPhonemizer(phonemizer)
    } }
    fun clearCache() { operation("CACHE_DELETE") { app.playback.clearCache(); message.value = "Audio cache cleared" } }
    private fun operation(stage: String, input: IssueInput = IssueInput(), action: suspend () -> Unit): Job {
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
        if (benchmarkBlocksWork()) return@launch
        try { action() } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            val pack = input.details["model"]?.let { id -> app.models.packs.firstOrNull { it.id == id } }
            val details = if (pack == null) input.details else input.details + mapOf("modelVersion" to pack.version,
                "manifestSha256" to sha256(Json.encodeToString(VoicePack.serializer(), pack).toByteArray()),
                "downloadPhase" to app.models.progress.value[pack.id]?.phase.orEmpty(),
                "downloadBytes" to app.models.progress.value[pack.id]?.received.toString())
            app.issues.record(stage, error, input.copy(details = details)); message.value = error.message ?: "Operation failed"
        }
        }
        activeOperations += job
        job.invokeOnCompletion { activeOperations -= job }
        job.start()
        return job
    }
    fun captureIssue(stage: String, error: Throwable, input: IssueInput = IssueInput()) {
        viewModelScope.launch { app.issues.record(stage, error, input) }
    }
    fun showIssues() { screen.value = "issues"; viewModelScope.launch { app.issues.refresh() } }
    fun setCrashReportingEnabled(enabled: Boolean) { app.crashReporting.setEnabled(enabled) }
    fun readIssue(id: String) { viewModelScope.launch { selectedIssue.value = app.issues.read(id) } }
    fun deleteIssue(id: String) { viewModelScope.launch {
        try { app.issues.delete(id); if (selectedIssue.value?.id == id) selectedIssue.value = null }
        catch (error: Exception) { message.value = error.message ?: "Could not delete log" }
    } }
    fun clearIssues() { viewModelScope.launch {
        try { app.issues.clear(); selectedIssue.value = null }
        catch (error: Exception) { message.value = error.message ?: "Could not delete logs" }
    } }
    fun exportIssue(uri: Uri, id: String) { viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) { checkNotNull(app.contentResolver.openOutputStream(uri, "wt")) { "Cannot open export destination" }.use {
                if (id == "history") app.issues.exportHistory(it) else app.issues.export(id, it)
            } }
            message.value = "Issue log saved"
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { message.value = error.message ?: "Could not export issue log" }
    } }
    fun saveGitHub(destination: GitHubDestination, token: String, onSaved: () -> Unit) { viewModelScope.launch {
        if (!BuildConfig.DEBUG) return@launch
        try { check(!sendingIssues.value); app.githubSettings.save(destination, token); onSaved(); message.value = "GitHub settings saved" }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { message.value = error.message ?: "GitHub settings could not be saved" }
    } }
    fun forgetGitHubToken() { viewModelScope.launch { app.githubSettings.forgetToken(); message.value = "GitHub token removed from this phone" } }
    fun sendIssues() {
        if (!BuildConfig.DEBUG) return
        if (sendingIssues.value) return
        sendingIssues.value = true
        viewModelScope.launch {
            try {
                val config = githubConfiguration.value
                val result = app.githubDiagnostics.send(config.destination, app.githubSettings.token(), config.installationId, app.issues.snapshot()) {
                    app.githubSettings.saveIssue(config.destination.repository, it)
                }
                githubIssueUrl.value = result.url
                message.value = if (result.uploadedReports == 0) "No new errors to send" else "${result.uploadedReports} error events appended to GitHub"
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { message.value = error.message ?: "Could not send errors to GitHub" }
            finally { sendingIssues.value = false }
        }
    }
    fun copyPlaybackDiagnostics() { viewModelScope.launch {
        try {
            val report = app.diagnostics.report(app)
            app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Read Assist diagnostics", report))
            message.value = "Playback diagnostics copied"
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { message.value = "Could not copy playback diagnostics" }
    } }
    fun deleteDocument(document: DocumentEntity) { operation("DOCUMENT_DELETE", documentIssueInput(document)) {
        ocrRefresh?.cancelAndJoin()
        app.playback.deleteDocument(document)
        app.issues.deleteForDocument(document.id)
        if (selectedIssue.value?.input?.documentId == document.id) selectedIssue.value = null
    } }
    fun page(index: Int) {
        if (benchmarkBlocksWork()) return
        playback.value.document?.let { if (index in 0 until it.pageCount) { ocrRefresh?.cancel(); app.playback.open(it, index) } }
    }
    fun retryOcr() = refreshOcr(rotate = false)
    fun rotateOcr() = refreshOcr(rotate = true)
    private fun refreshOcr(rotate: Boolean) {
        if (benchmarkBlocksWork()) return
        if (refreshingText.value) return
        val state = playback.value; val document = state.document ?: return
        val page = state.page
        val index = page?.index ?: state.viewPageIndex
        if (index !in 0 until document.pageCount) return
        refreshingText.value = true
        ocrRefresh = viewModelScope.launch {
            val request = app.playback.stop()
            try {
                val previous = app.documents.dao.page(document.id, index)?.rotation ?: 0
                app.documents.loadPage(document, index, if (rotate) (previous + 90) % 360 else previous)
                // Audio keys include the spoken text and source IDs. Changed recognition misses
                // naturally; deleting the document cache could race playback on another page.
                val current = playback.value
                if (app.playback.isCurrentRequest(request) && current.document?.id == document.id &&
                    (current.page?.index ?: current.viewPageIndex) == index) {
                    app.playback.open(document, index)
                    message.value = "Text recognition updated"
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { app.issues.record(if (rotate) "ROTATE_OCR" else "RETRY_OCR", e, documentIssueInput(document, page, pageIndex = index)); message.value = e.message }
            finally { refreshingText.value = false }
        }
    }
    fun readBookmark(bookmark: BookmarkEntity) {
        if (benchmarkBlocksWork()) return
        val document = playback.value.document?.takeIf { it.id == bookmark.documentId } ?: return
        val request = app.playback.stop()
        viewModelScope.launch {
            try {
                val page = app.documents.loadPage(document, bookmark.page)
                if (!app.playback.isCurrentRequest(request)) return@launch
                if (page.words.any { it.id == bookmark.wordId && it.text == bookmark.label }) {
                    app.playback.start(document, bookmark.page, bookmark.wordId)
                } else {
                    app.playback.open(document, bookmark.page)
                    message.value = "Text has changed. Select a word on the bookmarked page."
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                app.issues.record("OPEN_BOOKMARK", error, documentIssueInput(document, pageIndex = bookmark.page))
                message.value = error.message
            }
        }
    }
    fun bookmark(word: SourceWord) { viewModelScope.launch {
        val page = playback.value.page ?: return@launch
        app.documents.dao.bookmark(BookmarkEntity(page.documentId, word.id, page.index, word.text))
        message.value = "Bookmark saved"
    } }
    fun sample(previewModel: String? = null, previewVoice: String? = null, pronunciation: Boolean = false) { viewModelScope.launch {
        if (benchmarkBlocksWork()) return@launch
        try {
            if (previewModel != null) app.playback.changeModel(previewModel, checkNotNull(previewVoice))
            val text = if (pronunciation) BenchmarkFixtures.PRONUNCIATION.joinToString("\n\n")
                else app.assets.open("fixtures/reading.txt").bufferedReader().use { it.readText() }.trim()
            val id = stableId(if (pronunciation) "readflow-pronunciation-fixture" else "readflow-original-fixture", PIPELINE_VERSION)
            val document = DocumentEntity(id, sha256(text.toByteArray()), "Read Assist original fixture (CC0)",
                if (pronunciation) "Pronunciation comparison" else "A quiet space for thought", "", "text/plain", 1)
            app.documents.dao.insertDocument(document)
            val page = DocumentPipeline().process(id, 0, ExtractedPage(text.split("\n\n").mapIndexed { index, paragraph -> TextElement(paragraph, emptyList(), index, index) }, 0f, 0f, "Original sample"))
            app.documents.dao.putPage(PageEntity(id, 0, Json.encodeToString(PageContent.serializer(), page), "Original sample"))
            open(document)
            if (previewModel != null) {
                // Wait for preference collection before selecting the engine for the preview.
                app.preferences.values.first { it.model == previewModel && it.voice == previewVoice }
                app.playback.start(document, 0, page.words.first().id)
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) { app.issues.record("PREVIEW", e, IssueInput(details = mapOf("model" to previewModel.orEmpty(), "voice" to previewVoice.orEmpty()))); message.value = e.message }
    } }
}
