package app.readflow.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.readflow.ReadFlowApp
import app.readflow.core.*
import app.readflow.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import java.io.File

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as ReadFlowApp
    val playback = app.playback.state
    val library = app.documents.dao.library().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val preferences = app.preferences.values.stateIn(viewModelScope, SharingStarted.Eagerly, Preferences())
    val downloads = app.models.progress
    val screen = MutableStateFlow("library")
    val message = MutableStateFlow<String?>(null)
    val importing = MutableStateFlow(false)
    private val jobs = mutableMapOf<String, Job>()
    fun open(document: DocumentEntity) { app.playback.open(document); screen.value = "reader" }
    fun import(uri: Uri) = launchImport { app.documents.import(uri) }
    fun importUrl(url: String) = launchImport { app.documents.importUrl(url.trim()) }
    private fun launchImport(action: suspend () -> DocumentEntity) { viewModelScope.launch {
        importing.value = true
        try { open(action()) } catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) { message.value = e.message ?: "Import failed" }
        finally { importing.value = false }
    } }
    fun update(preferences: Preferences) { viewModelScope.launch { app.preferences.set(preferences) } }
    fun download(id: String) {
        if (jobs[id]?.isActive == true) return
        jobs[id] = viewModelScope.launch { try { app.models.install(id) } catch (cancel: CancellationException) { throw cancel } catch (e: Exception) { message.value = e.message } }
    }
    fun cancelDownload(id: String) { jobs[id]?.cancel() }
    fun deleteModel(id: String) { viewModelScope.launch { jobs[id]?.cancelAndJoin(); app.playback.deleteModel(id) } }
    fun chooseModel(model: String, voice: String) { viewModelScope.launch { app.playback.changeModel(model, voice) } }
    fun clearCache() { viewModelScope.launch { app.playback.clearCache(); message.value = "Audio cache cleared" } }
    fun deleteDocument(document: DocumentEntity) { viewModelScope.launch {
        app.playback.deleteDocument(document)
    } }
    fun page(index: Int) { playback.value.document?.let { if (index in 0 until it.pageCount) app.playback.open(it, index) } }
    fun rotateOcr() { viewModelScope.launch {
        val state = playback.value; val document = state.document ?: return@launch; val page = state.page ?: return@launch
        app.playback.stop()
        try {
            val previous = app.documents.dao.page(document.id, page.index)?.rotation ?: 0
            app.documents.loadPage(document, page.index, (previous + 90) % 360)
            app.cache.deleteDocument(document.id)
            app.playback.open(document, page.index)
        } catch (e: Exception) { message.value = e.message }
    } }
    fun bookmark(word: SourceWord) { viewModelScope.launch {
        val page = playback.value.page ?: return@launch
        app.documents.dao.bookmark(BookmarkEntity(page.documentId, word.id, page.index, word.text))
        message.value = "Bookmark saved"
    } }
    fun sample(previewModel: String? = null, previewVoice: String? = null) { viewModelScope.launch {
        try {
            if (previewModel != null) app.playback.changeModel(previewModel, checkNotNull(previewVoice))
            val text = app.assets.open("fixtures/reading.txt").bufferedReader().use { it.readText() }.trim()
            val id = stableId("readflow-original-fixture", PIPELINE_VERSION)
            val document = DocumentEntity(id, sha256(text.toByteArray()), "ReadFlow original fixture (CC0)", "A quiet space for thought", "", "text/plain", 1)
            app.documents.dao.insertDocument(document)
            val page = DocumentPipeline().process(id, 0, ExtractedPage(text.split("\n\n").mapIndexed { index, paragraph -> TextElement(paragraph, emptyList(), index, index) }, 0f, 0f, "Original sample"))
            app.documents.dao.putPage(PageEntity(id, 0, Json.encodeToString(PageContent.serializer(), page), "Original sample"))
            open(document)
            if (previewModel != null) {
                // Wait for preference collection before selecting the engine for the preview.
                app.preferences.values.first { it.model == previewModel && it.voice == previewVoice }
                app.playback.start(document, 0, page.words.first().id)
            }
        } catch (e: Exception) { message.value = e.message }
    } }
}
