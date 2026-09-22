package app.readflow.playback

import android.net.Uri
import android.os.PowerManager
import androidx.media3.common.*
import app.readflow.core.*
import app.readflow.data.*
import app.readflow.models.*
import app.readflow.speech.*
import app.readflow.diagnostics.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json

data class ReaderPlayback(
    val document: DocumentEntity? = null, val page: PageContent? = null, val activeWordId: String? = null,
    val selectedWordId: String? = null, val playing: Boolean = false, val preparing: Boolean = false,
    val status: String = "Paused", val positionMs: Long = 0, val durationMs: Long = 0, val currentChunk: AlignedAudio? = null,
    val error: String? = null,
    val activeWordIds: Set<String> = emptySet(),
    val wantsToPlay: Boolean = false,
    val blockedSentence: BlockedSentence? = null,
    val viewPageIndex: Int = 0,
)
data class BlockedSentence(val pageIndex: Int, val wordId: String, val resume: SpeechTarget?)
class GenerationGate {
    private val counter = AtomicLong()
    fun next() = counter.incrementAndGet()
    fun current(id: Long) = counter.get() == id
}

class ReadingCoordinator(
    private val documents: Documents, private val models: ModelStore, private val cache: AudioCache,
    private val preferences: PreferenceStore, private val power: PowerManager,
    private val diagnostics: PlaybackDiagnostics,
    private val issues: IssueLogs,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = GenerationGate()
    private val mutable = MutableStateFlow(ReaderPlayback())
    val state = mutable.asStateFlow()
    private val engine = SherpaEngine(models::directory) { id -> models.packs.first { it.id == id }.voices }
    private val aligner = OnnxForcedAligner { java.io.File(models.directory("alignment"), "model.onnx") }
    private val planner = SpeechPlanner()
    private var work: Job? = null
    private var observer: Job? = null
    private var player: Player? = null
    private var playerListener: Player.Listener? = null
    private val queued = linkedMapOf<String, Pair<PageContent, AlignedAudio>>()
    private var durableWord: String? = null
    private var settings = Preferences()
    private var memoryPressure = false

    init { scope.launch { preferences.values.collect { settings = it; player?.setPlaybackSpeed(it.speed) } } }
    fun pressure() { memoryPressure = true }
    fun attach(player: Player) {
        playerListener?.let { this.player?.removeListener(it) }
        this.player = player
        playerListener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val current = state.value
                val document = current.document
                val chunk = current.currentChunk
                val input = document?.let { documentIssueInput(it, current.page,
                    current.page?.words?.filter { word -> word.id in chunk?.sourceIds.orEmpty() }.orEmpty(), chunk?.speech,
                    details = mapOf("playerErrorCode" to error.errorCodeName, "positionMs" to player.currentPosition.toString(),
                        "model" to chunk?.model.orEmpty(), "voice" to chunk?.voice.orEmpty(), "sampleRate" to chunk?.sampleRate.toString())) } ?: IssueInput()
                scope.launch { issues.record("MEDIA_PLAYBACK", error, input, audio = chunk?.audioPath?.let { java.io.File(it) }) }
                mutable.value = current.copy(preparing = false, playing = false, error = error.message ?: "Audio playback failed")
            }
        }.also(player::addListener)
        player.setPlaybackSpeed(settings.speed)
        observer?.cancel()
        observer = scope.launch {
            var savedWord: String? = null
            while (isActive) {
                val data = queued[player.currentMediaItem?.mediaId]
                if (data != null) {
                    val (page, chunk) = data
                    val mediaMs = player.currentPosition
                    val adjusted = mediaMs - (settings.latencyMs * player.playbackParameters.speed).toLong()
                    val ids = activeWordIds(chunk.timings, adjusted, chunk.sampleRate)
                    val active = ids.firstOrNull()
                    playbackResumeWord(chunk, active)?.let { durableWord = it }
                    mutable.value = mutable.value.copy(page = page, activeWordId = active, activeWordIds = ids, currentChunk = chunk,
                        playing = player.isPlaying, wantsToPlay = player.playWhenReady, positionMs = mediaMs, durationMs = chunk.durationMs,
                        status = if (player.playbackState == Player.STATE_BUFFERING) "Buffering" else if (player.isPlaying) {
                            if (chunk.timings.isEmpty()) "Reading · word highlighting unavailable" else "Reading"
                        } else if (mutable.value.preparing) "Preparing next sentence" else "Paused")
                    val word = durableWord
                    if (word != null && word != savedWord) {
                        documents.dao.putPosition(ReadingPosition(page.documentId, page.index, word)); savedWord = word
                    }
                } else mutable.value = mutable.value.copy(playing = player.isPlaying)
                // This only samples the player clock; it never advances a synthetic speech clock.
                delay(33)
            }
        }
    }
    fun detach() { observer?.cancel(); playerListener?.let { player?.removeListener(it) }; playerListener = null; player = null; stop() }
    fun open(document: DocumentEntity, pageIndex: Int? = null) {
        val generation = invalidate()
        mutable.value = ReaderPlayback(document = document, preparing = true, status = "Extracting page", viewPageIndex = pageIndex ?: 0)
        work = scope.launch {
            try {
                val position = documents.dao.position(document.id)
                val requestedPage = pageIndex ?: position?.page ?: 0
                mutable.value = mutable.value.copy(viewPageIndex = requestedPage)
                val page = documents.loadPage(document, requestedPage)
                if (!gate.current(generation)) return@launch
                durableWord = position?.wordId?.takeIf { id -> page.words.any { it.id == id } }
                mutable.value = ReaderPlayback(document, page, selectedWordId = durableWord, status = if (page.words.isEmpty()) "No readable text on this page" else "Paused")
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { if (gate.current(generation)) {
                issues.record("EXTRACT_PAGE", e, documentIssueInput(document, pageIndex = state.value.viewPageIndex))
                if (gate.current(generation)) mutable.value = mutable.value.copy(preparing = false, status = e.message ?: "Extraction failed", error = e.message ?: "Extraction failed")
            } }
        }
    }
    private fun invalidate(): Long {
        val generation = gate.next()
        work?.cancel()
        issues.activeInput = IssueInput()
        player?.pause(); player?.stop(); player?.clearMediaItems(); queued.clear()
        mutable.value = mutable.value.copy(activeWordId = null, activeWordIds = emptySet(), currentChunk = null, playing = false, wantsToPlay = false, positionMs = 0, durationMs = 0, blockedSentence = null)
        return generation
    }
    fun stop(): Long {
        val generation = invalidate()
        mutable.value = mutable.value.copy(preparing = false, status = "Paused")
        return generation
    }
    fun isCurrentRequest(generation: Long) = gate.current(generation)
    fun select(wordId: String) {
        if (player?.playWhenReady == true || state.value.preparing) start(wordId = wordId)
        else mutable.value = mutable.value.copy(selectedWordId = wordId)
    }
    fun toggle() {
        val player = player ?: return
        if (state.value.preparing && player.mediaItemCount == 0) { stop(); return }
        if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) player.pause()
        else if (player.mediaItemCount > 0) player.play()
        else start(wordId = mutable.value.selectedWordId ?: durableWord)
    }
    fun seek(milliseconds: Long) { player?.let { it.seekTo(milliseconds.coerceIn(0, state.value.durationMs)) } }
    fun skip(seconds: Int) { player?.let { seek(it.currentPosition + seconds * 1000L) } }
    fun sentence(direction: Int) {
        if (direction > 0 && state.value.blockedSentence != null && !state.value.playing) { skipBlockedSentence(); return }
        val current = state.value; val page = current.page ?: return
        val wordId = current.activeWordId ?: current.selectedWordId ?: durableWord
        val word = page.words.firstOrNull { it.id == wordId }
        val sentences = page.words.groupBy { it.sentenceId }.values.toList()
        val index = sentences.indexOfFirst { line -> line.any { it.id == word?.id } }.coerceAtLeast(0)
        val target = sentences.getOrNull(index + direction)?.firstOrNull()
        if (target != null) start(wordId = target.id)
        else current.document?.let { document ->
            val next = page.index + direction
            if (next in 0 until document.pageCount) start(document, next)
        }
    }
    fun skipBlockedSentence() {
        val blocked = state.value.blockedSentence ?: return
        val document = state.value.document ?: return
        val target = blocked.resume
        if (target != null) start(document, target.pageIndex, target.wordId)
        else {
            stop()
            mutable.value = mutable.value.copy(error = null, selectedWordId = null, status = "End of document")
        }
    }
    fun start(document: DocumentEntity = checkNotNull(state.value.document), pageIndex: Int = state.value.page?.index ?: state.value.viewPageIndex, wordId: String? = null) {
        val old = work
        val generation = invalidate()
        mutable.value = mutable.value.copy(document = document, preparing = true, status = "Preparing speech", selectedWordId = null, error = null, viewPageIndex = pageIndex)
        val chosen = settings
        durableWord = wordId
        work = scope.launch {
            var processingPage: PageContent? = null
            var processingWords: List<SourceWord> = emptyList()
            var currentWords: List<SourceWord> = emptyList()
            var currentSpeech: SpeechText? = null
            var currentIndex = pageIndex
            var stage = "VERIFY_MODELS"
            var loggedFailure = false
            var audioKey: String? = null
            var modelVersion = "unknown"
            var sampleRate: Int? = null
            var sampleCount: Int? = null
            fun input(words: List<SourceWord> = currentWords) = documentIssueInput(document, processingPage, words, currentSpeech,
                currentIndex, wordId, mapOf("model" to chosen.model, "modelVersion" to modelVersion, "voice" to chosen.voice,
                    "speed" to chosen.speed.toString(), "synthesis" to "steps=5;temperature=.7;speed=1;threads=2",
                    "generation" to generation.toString(), "audioKey" to audioKey.orEmpty(), "sampleRate" to sampleRate.toString(), "sampleCount" to sampleCount.toString()))
            try {
                // A cancelled native call owns its resources until it returns. New work waits here.
                old?.join()
                checkNotNull(player) { "Playback service is not connected" }
                diagnostics.record(chosen.model, PlaybackStage.VERIFYING_MODELS)
                models.verifyInstalled(chosen.model); models.verifyInstalled("alignment")
                val manifest = models.packs.first { it.id == chosen.model }
                modelVersion = manifest.version
                var index = pageIndex
                var first = true
                var requested = wordId
                while (index < document.pageCount && gate.current(generation)) {
                    currentIndex = index; processingPage = null; currentWords = emptyList(); currentSpeech = null; audioKey = null
                    sampleRate = null; sampleCount = null
                    stage = "EXTRACT_PAGE"
                    issues.activeInput = input()
                    val page = documents.loadPage(document, index)
                    if (!gate.current(generation)) return@launch
                    if (first && requested != null && page.words.none { it.id == requested }) {
                        // A selected word can become obsolete while a concurrent OCR retry
                        // finishes. Show the refreshed page instead of failing speech planning.
                        durableWord = null
                        mutable.value = ReaderPlayback(document = document, page = page, viewPageIndex = index,
                            status = "Text has changed. Select a word to read.")
                        return@launch
                    }
                    val words = page.words.filter { !chosen.skipMargins || !it.marginal || it.id == requested }
                    processingPage = page; processingWords = words
                    if (first) mutable.value = mutable.value.copy(page = page)
                    val chunks = planner.prepare(words, if (first) requested else null, fromSelectedWord = true).iterator()
                    while (true) {
                        val output = checkNotNull(player)
                        val targetSeconds = if (memoryPressure || power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) 8 else 30
                        while (remainingAudioMs(output) > targetSeconds * 1000 && gate.current(generation)) delay(200)
                        currentCoroutineContext().ensureActive()
                        diagnostics.record(chosen.model, PlaybackStage.PREPARING_TEXT)
                        stage = "NORMALIZE_TEXT"; currentWords = emptyList(); currentSpeech = null; audioKey = null
                        sampleRate = null; sampleCount = null
                        issues.activeInput = input()
                        if (!chunks.hasNext()) break
                        val prepared = chunks.next()
                        val speech = prepared.speech
                        currentWords = prepared.words; currentSpeech = speech
                        val modelRevision = sha256(Json.encodeToString(VoicePack.serializer(), manifest).toByteArray())
                        val key = cacheKey(document.id, speech, modelRevision, chosen.voice, "steps=5;temperature=.7;speed=1;threads=2", CtcAlignment.VERSION)
                        audioKey = key
                        issues.activeInput = input()
                        stage = "READ_AUDIO_CACHE"
                        val audio = cache.get(key) ?: run {
                            var committed = false
                            try {
                                mutable.value = mutable.value.copy(preparing = true, status = "Loading ${manifest.name}")
                                diagnostics.record(chosen.model, PlaybackStage.LOADING_MODEL)
                                stage = "LOAD_MODEL"
                                engine.load(chosen.model, chosen.voice)
                                mutable.value = mutable.value.copy(status = "Generating speech")
                                diagnostics.record(chosen.model, PlaybackStage.SYNTHESIZING)
                                stage = "SYNTHESIZE"
                                val generated = engine.synthesize(speech.text) { gate.current(generation) }
                                sampleRate = generated.sampleRate; sampleCount = generated.samples.size
                                stage = "WRITE_AUDIO"
                                val quantized = cache.writePcm(key, generated)
                                mutable.value = mutable.value.copy(status = "Aligning words")
                                diagnostics.record(chosen.model, PlaybackStage.ALIGNING)
                                stage = "ALIGN_WORDS"
                                val alignment = alignForPlayback(aligner, quantized, speech)
                                alignment.rejected?.let { rejection ->
                                    issues.record(stage, rejection, input(), audio = cache.file(key),
                                        warnings = listOf("Speech continues without word highlighting for this chunk."),
                                        dedupeKey = "alignment-audio-only-$key")
                                }
                                currentCoroutineContext().ensureActive()
                                AlignedAudio(key, cache.file(key).path, withContext(Dispatchers.IO) { fileHash(cache.file(key)) }, quantized.sampleRate, quantized.samples.size.toLong(),
                                    alignment.timings, speech.sourceIds, speech, manifest.id + manifest.version, chosen.voice, CtcAlignment.VERSION).also { cache.commit(document.id, it); committed = true }
                            } catch (error: Exception) {
                                if (error !is CancellationException && gate.current(generation)) {
                                    issues.record(stage, error, input(), audio = cache.file(key))
                                    loggedFailure = true
                                }
                                throw error
                            } finally {
                                if (!committed) withContext(NonCancellable + Dispatchers.IO) { cache.file(key).delete() }
                            }
                        }
                        if (!gate.current(generation)) return@launch
                        val startMs = if (first) playbackStartMs(audio, requested) else 0L
                        diagnostics.record(chosen.model, PlaybackStage.STARTING_AUDIO)
                        stage = "QUEUE_AUDIO"
                        currentCoroutineContext().ensureActive()
                        queued[key] = page to audio
                        val item = MediaItem.Builder().setMediaId(key).setUri(Uri.fromFile(java.io.File(audio.audioPath)))
                            .setMediaMetadata(MediaMetadata.Builder().setTitle(document.title).setArtist("${manifest.name} · ${manifest.voices.first { it.id == chosen.voice }.name}").build()).build()
                        output.addMediaItem(item)
                        if (first) {
                            output.seekTo(0, startMs)
                            output.prepare(); output.play(); first = false; requested = null
                        } else if (output.playbackState == Player.STATE_ENDED) {
                            val shouldPlay = output.playWhenReady
                            output.seekToNextMediaItem(); output.prepare(); output.playWhenReady = shouldPlay
                        }
                        mutable.value = mutable.value.copy(preparing = false)
                        diagnostics.record(chosen.model, PlaybackStage.AUDIO_READY)
                        while (output.currentMediaItemIndex > 1) {
                            val removed = output.getMediaItemAt(0).mediaId
                            output.removeMediaItem(0); queued.remove(removed)
                        }
                        cache.trim(chosen.cacheMb * 1024L * 1024L, queued.keys.toSet())
                    }
                    index++
                }
                if (first) mutable.value = mutable.value.copy(preparing = false, status = "No readable text")
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (gate.current(generation)) {
                    if (!loggedFailure) issues.record(stage, error, input(if (error is SpeechPreparationException) error.sentence else currentWords),
                        audio = audioKey?.let(cache::file))
                    diagnostics.record(chosen.model, PlaybackStage.FAILED, error)
                    if (!gate.current(generation)) return@launch
                    val page = processingPage
                    val blocked = if (error is SpeechPreparationException && page != null) {
                        val failedWord = error.sentence.first().id
                        BlockedSentence(page.index, failedWord, afterSentence(processingWords, failedWord, page.index, document.pageCount))
                    } else null
                    val message = error.message ?: "Speech unavailable"
                    mutable.value = mutable.value.copy(preparing = false, status = message, error = message, blockedSentence = blocked,
                        selectedWordId = if (queued.isEmpty()) blocked?.wordId else mutable.value.selectedWordId)
                }
            } finally {
                if (gate.current(generation)) issues.activeInput = IssueInput()
            }
        }
    }
    private fun remainingAudioMs(player: Player): Long = (player.currentMediaItemIndex until player.mediaItemCount).sumOf {
        queued[player.getMediaItemAt(it).mediaId]?.second?.durationMs ?: 0
    } - player.currentPosition
    suspend fun changeModel(model: String, voice: String) {
        val old = work; val generation = invalidate()
        mutable.value = mutable.value.copy(preparing = false, status = "Paused")
        old?.join(); engine.unload(); aligner.close()
        if (gate.current(generation)) {
            settings = settings.copy(model = model, voice = voice)
            preferences.set(settings)
        }
    }
    suspend fun deleteModel(id: String) { val old = work; stop(); old?.join(); engine.unload(); aligner.close(); models.delete(id) }
    suspend fun clearCache() { val old = work; stop(); old?.join(); cache.clear() }
    suspend fun deleteDocument(document: DocumentEntity) {
        val old = work; stop(); old?.join()
        cache.deleteDocument(document.id); documents.delete(document)
        if (state.value.document?.id == document.id) mutable.value = ReaderPlayback()
    }
}
