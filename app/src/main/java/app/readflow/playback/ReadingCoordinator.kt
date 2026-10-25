package app.readflow.playback

import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
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

// Compute settings do not change the requested voice/text. Retain the established
// recipe identifier so valid audio from older releases remains reusable.
private const val CACHE_SYNTHESIS_RECIPE = "steps=5;temperature=.7;speed=1;threads=2"
private class PrepareAheadLimit(message: String) : Exception(message)

data class ReaderPlayback(
    val document: DocumentEntity? = null, val page: PageContent? = null, val activeWordId: String? = null,
    val selectedWordId: String? = null, val playing: Boolean = false, val preparing: Boolean = false,
    val status: String = "Paused", val positionMs: Long = 0, val durationMs: Long = 0, val currentChunk: AlignedAudio? = null,
    val error: String? = null,
    val activeWordIds: Set<String> = emptySet(),
    val wantsToPlay: Boolean = false,
    val blockedSentence: BlockedSentence? = null,
    val viewPageIndex: Int = 0,
    val bufferedSentences: Int = 0,
    val bufferedListeningMs: Long = 0,
    val fillingBuffer: Boolean = false,
    val bufferTargetSentences: Int = 4,
    val prepareAheadProgress: PrepareAheadProgress? = null,
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
    private val benchmarkActive: () -> Boolean = { false },
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
    private data class QueuedSpeech(val page: PageContent, val audio: AlignedAudio,
        val sentenceId: String, val completesSentence: Boolean)
    private val queued = linkedMapOf<String, QueuedSpeech>()
    private var durableWord: String? = null
    private var settings = Preferences()
    private val memoryPressure = MemoryPressureWindow()
    private val reservePolicy = AudioReservePolicy()
    private var startupPending = false
    private var firstReadyAtMs: Long? = null
    private var producerFinished = true
    private var recoveringReserve = false
    private val pendingMediaItems = mutableListOf<MediaItem>()

    init { scope.launch { preferences.values.collect { settings = it; player?.setPlaybackSpeed(it.speed) } } }
    fun pressure() { memoryPressure.notePressure(SystemClock.elapsedRealtime()) }
    fun attach(player: Player) {
        playerListener?.let { this.player?.removeListener(it) }
        this.player = player
        playerListener = object : Player.Listener {
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK && recoveringReserve) releaseRecoveryForSeek(player)
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // During recovery the ended player retains play intent. Audio-focus loss or
                // a direct pause must cancel the pending automatic restart as well.
                if (!playWhenReady && startupPending && recoveringReserve) stop()
            }
            override fun onPlayerError(error: PlaybackException) {
                val current = state.value
                val document = current.document
                val chunk = current.currentChunk
                val input = document?.let { documentIssueInput(it, current.page,
                    current.page?.words?.filter { word -> word.id in chunk?.sourceIds.orEmpty() }.orEmpty(), chunk?.speech,
                    details = mapOf("playerErrorCode" to error.errorCodeName, "positionMs" to player.currentPosition.toString(),
                        "model" to chunk?.model.orEmpty(), "voice" to chunk?.voice.orEmpty(), "sampleRate" to chunk?.sampleRate.toString())) } ?: IssueInput()
                scope.launch { issues.record("MEDIA_PLAYBACK", error, input, audio = chunk?.audioPath?.let { java.io.File(it) }) }
                startupPending = false; producerFinished = true; gate.next(); work?.cancel()
                recoveringReserve = false; pendingMediaItems.clear()
                player.pause()
                mutable.value = current.copy(preparing = false, fillingBuffer = false, playing = false,
                    wantsToPlay = false, error = error.message ?: "Audio playback failed")
            }
        }.also(player::addListener)
        player.setPlaybackSpeed(settings.speed)
        observer?.cancel()
        observer = scope.launch {
            var savedWord: String? = null
            var reserveUpdatedAt = 0L
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                if (now - reserveUpdatedAt >= 200) {
                    updateReserve(player); reserveUpdatedAt = now
                }
                val wantsPlayback = startupPending || (player.playWhenReady &&
                    !(producerFinished && player.playbackState == Player.STATE_ENDED))
                val data = queued[player.currentMediaItem?.mediaId]
                if (data != null) {
                    val (page, chunk) = data
                    val mediaMs = player.currentPosition
                    val adjusted = mediaMs - (settings.latencyMs * player.playbackParameters.speed).toLong()
                    val ids = activeWordIds(chunk.timings, adjusted, chunk.sampleRate)
                    val active = ids.firstOrNull()
                    playbackResumeWord(chunk, active)?.let { durableWord = it }
                    mutable.value = mutable.value.copy(page = page, activeWordId = active, activeWordIds = ids, currentChunk = chunk,
                        playing = player.isPlaying, wantsToPlay = wantsPlayback, positionMs = mediaMs, durationMs = chunk.durationMs,
                        status = when {
                            mutable.value.error != null -> mutable.value.error!!
                            startupPending -> "Preparing audio ahead"
                            player.playbackState == Player.STATE_BUFFERING -> "Buffering"
                            player.isPlaying -> if (chunk.timings.isEmpty()) "Reading · word highlighting unavailable" else "Reading"
                            player.playWhenReady && !producerFinished && player.playbackState == Player.STATE_ENDED -> "Preparing next sentence"
                            producerFinished && player.playbackState == Player.STATE_ENDED -> "End of document"
                            else -> "Paused"
                        })
                    val word = durableWord
                    if (word != null && word != savedWord) {
                        documents.dao.putPosition(ReadingPosition(page.documentId, page.index, word)); savedWord = word
                    }
                } else mutable.value = mutable.value.copy(playing = player.isPlaying, wantsToPlay = wantsPlayback)
                // This only samples the player clock; it never advances a synthetic speech clock.
                delay(33)
            }
        }
    }
    fun detach() { observer?.cancel(); playerListener?.let { player?.removeListener(it) }; playerListener = null; player = null; stop() }
    fun open(document: DocumentEntity, pageIndex: Int? = null) {
        if (benchmarkActive()) return
        val old = work
        val generation = invalidate()
        mutable.value = ReaderPlayback(document = document, preparing = true, status = "Extracting page", viewPageIndex = pageIndex ?: 0)
        work = scope.launch {
            try {
                old?.join()
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
            } } finally { withContext(NonCancellable) { old?.join() } }
        }
    }
    private fun invalidate(): Long {
        val generation = gate.next()
        work?.cancel()
        startupPending = false; firstReadyAtMs = null; producerFinished = true; recoveringReserve = false
        pendingMediaItems.clear()
        issues.activeInput = IssueInput()
        player?.pause(); player?.stop(); player?.clearMediaItems(); queued.clear()
        mutable.value = mutable.value.copy(activeWordId = null, activeWordIds = emptySet(), currentChunk = null, playing = false,
            wantsToPlay = false, positionMs = 0, durationMs = 0, blockedSentence = null,
            bufferedSentences = 0, bufferedListeningMs = 0, fillingBuffer = false,
            prepareAheadProgress = mutable.value.prepareAheadProgress?.let {
                if (it.running) it.copy(running = false, status = "Cancelled · prepared audio kept") else it
            })
        return generation
    }
    fun stop(): Long {
        val generation = invalidate()
        mutable.value = mutable.value.copy(preparing = false, status = "Paused")
        return generation
    }
    fun isCurrentRequest(generation: Long) = gate.current(generation)
    fun select(wordId: String) {
        if (player?.playWhenReady == true || startupPending) start(wordId = wordId)
        else mutable.value = mutable.value.copy(selectedWordId = wordId)
    }
    // MediaSession uses these commands too: pausing an already-paused player during
    // the initial reserve must cancel its scheduled start, even without a Player event.
    fun pauseFromControls() {
        if (startupPending || (state.value.preparing && player?.mediaItemCount == 0)) stop()
        else player?.pause()
    }
    fun playFromControls() {
        if (benchmarkActive()) return
        val output = player ?: return
        // MediaSession's play action seeks to the default position first when ended.
        if (recoveringReserve && output.playbackState != Player.STATE_ENDED) releaseRecoveryForSeek(output)
        if (startupPending) { updateReserve(output); return }
        if (output.mediaItemCount > 0) {
            if (output.playbackState == Player.STATE_ENDED && producerFinished) output.seekToDefaultPosition()
            output.play()
        }
        else if (state.value.document != null) start(wordId = state.value.selectedWordId ?: durableWord)
    }
    fun toggle() {
        if (benchmarkActive()) return
        val player = player ?: return
        if (startupPending || (state.value.preparing && player.mediaItemCount == 0)) { stop(); return }
        if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) player.pause()
        else if (player.mediaItemCount > 0) playFromControls()
        else start(wordId = mutable.value.selectedWordId ?: durableWord)
    }
    fun seek(milliseconds: Long) { player?.let {
        if (recoveringReserve) releaseRecoveryForSeek(it)
        it.seekTo(milliseconds.coerceIn(0, state.value.durationMs))
    } }
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

    /** Prepare a chosen page range into the ordinary verified audio cache, without playback. */
    fun prepareAhead(endPageInclusive: Int) {
        if (benchmarkActive()) return
        val document = state.value.document ?: return
        val startPage = state.value.page?.index ?: state.value.viewPageIndex
        require(endPageInclusive in startPage until document.pageCount) { "Choose an end page within this document" }
        val old = work
        val generation = invalidate()
        val chosen = settings
        val limit = chosen.cacheMb * 1024L * 1024L
        val budget = PreparedRangeBudget(limit)
        var progress = PrepareAheadProgress(startPage, endPageInclusive)
        mutable.value = mutable.value.copy(preparing = true, error = null, status = "Preparing pages for later",
            prepareAheadProgress = progress)
        work = scope.launch {
            var page: PageContent? = null
            var words = emptyList<SourceWord>()
            var speech: SpeechText? = null
            var key: String? = null
            var stage = "VERIFY_MODELS"
            fun input() = documentIssueInput(document, page, words, speech,
                page?.index ?: progress.currentPage, details = mapOf("model" to chosen.model,
                    "voice" to chosen.voice, "synthesis" to runtimeDescription(chosen), "operation" to "prepare-pages"))
            fun publish(status: String) {
                progress = progress.copy(status = status, bytes = budget.bytes)
                if (gate.current(generation)) mutable.value = mutable.value.copy(status = status, prepareAheadProgress = progress)
            }
            try {
                old?.join()
                diagnostics.beginSession(chosen.model, chosen.voice)
                models.verifyInstalled(chosen.model); models.verifyInstalled("alignment")
                configureRuntime(chosen)
                val manifest = models.packs.first { it.id == chosen.model }
                val modelRevision = sha256(Json.encodeToString(VoicePack.serializer(), manifest).toByteArray())
                for (index in startPage..endPageInclusive) {
                    currentCoroutineContext().ensureActive()
                    progress = progress.copy(currentPage = index)
                    page = null; words = emptyList(); speech = null; key = null
                    stage = "EXTRACT_PAGE"
                    publish("Preparing page ${index + 1} of ${endPageInclusive + 1}")
                    issues.activeInput = input()
                    val extractedPage = documents.loadPage(document, index)
                    page = extractedPage
                    val pageWords = extractedPage.words.filter { !chosen.skipMargins || !it.marginal }
                    stage = "NORMALIZE_TEXT"
                    for (prepared in planner.prepare(pageWords)) {
                        currentCoroutineContext().ensureActive()
                        words = prepared.words; speech = prepared.speech
                        val chunkKey = cacheKey(document.id, prepared.speech, modelRevision, chosen.voice,
                            CACHE_SYNTHESIS_RECIPE, CtcAlignment.VERSION)
                        key = chunkKey
                        issues.activeInput = input()
                        stage = "READ_AUDIO_CACHE"
                        val started = SystemClock.elapsedRealtime()
                        var synthesisMs = 0L; var alignmentMs = 0L
                        val cached = cache.get(chunkKey)
                        val audio = cached ?: run {
                            var committed = false
                            try {
                                stage = "LOAD_MODEL"
                                engine.load(chosen.model, chosen.voice)
                                stage = "SYNTHESIZE"
                                val synthesisStarted = SystemClock.elapsedRealtime()
                                val generated = engine.synthesize(prepared.speech.text) { gate.current(generation) }
                                synthesisMs = SystemClock.elapsedRealtime() - synthesisStarted
                                val requiredBytes = 44L + generated.samples.size.toLong() * 2
                                if (!budget.canAdd(chunkKey, requiredBytes)) throw PrepareAheadLimit(
                                    "Audio cache limit reached · prepared pages kept. Increase the cache limit or choose fewer pages.")
                                if (cache.file(chunkKey).parentFile?.usableSpace?.let { it < requiredBytes + 16L * 1024 * 1024 } == true)
                                    throw PrepareAheadLimit("Storage is nearly full · prepared pages kept")
                                // Make room only by removing unrelated audio. Every completed range
                                // chunk remains protected, including chunks reused from an older run.
                                cache.trim(limit - requiredBytes, budget.protectedKeys + chunkKey)
                                stage = "WRITE_AUDIO"
                                val quantized = cache.writePcm(chunkKey, generated)
                                stage = "ALIGN_WORDS"
                                val alignmentStarted = SystemClock.elapsedRealtime()
                                val alignment = alignForPlayback(aligner, quantized, prepared.speech)
                                alignmentMs = SystemClock.elapsedRealtime() - alignmentStarted
                                alignment.rejected?.let { rejection ->
                                    issues.record(stage, rejection, input(), audio = cache.file(chunkKey),
                                        warnings = listOf("Prepared audio has no validated word highlighting for this chunk."),
                                        dedupeKey = "alignment-audio-only-$chunkKey")
                                }
                                currentCoroutineContext().ensureActive()
                                AlignedAudio(chunkKey, cache.file(chunkKey).path,
                                    withContext(Dispatchers.IO) { fileHash(cache.file(chunkKey)) }, quantized.sampleRate,
                                    quantized.samples.size.toLong(), alignment.timings, prepared.speech.sourceIds,
                                    prepared.speech, manifest.id + manifest.version, chosen.voice, CtcAlignment.VERSION)
                                    .also { cache.commit(document.id, it); committed = true }
                            } finally {
                                if (!committed) withContext(NonCancellable + Dispatchers.IO) { cache.file(chunkKey).delete() }
                            }
                        }
                        val audioBytes = withContext(Dispatchers.IO) { cache.file(chunkKey).length() }
                        if (!budget.canAdd(chunkKey, audioBytes)) throw PrepareAheadLimit(
                            "Audio cache limit reached · prepared pages kept. Increase the cache limit or choose fewer pages.")
                        budget.add(chunkKey, audioBytes)
                        diagnostics.recordPreparation(cached != null, synthesisMs, alignmentMs,
                            SystemClock.elapsedRealtime() - started, audio.durationMs,
                            characters = prepared.speech.text.codePointCount(0, prepared.speech.text.length))
                        progress = progress.copy(preparedChunks = progress.preparedChunks + 1,
                            reusedChunks = progress.reusedChunks + if (cached != null) 1 else 0,
                            audioMs = progress.audioMs + audio.durationMs)
                        publish("Preparing page ${index + 1} · ${progress.preparedChunks} audio chunks ready")
                        stage = "NORMALIZE_TEXT"
                    }
                    progress = progress.copy(completedPages = progress.completedPages + 1)
                    cache.trim(limit, budget.protectedKeys)
                    publish("${progress.completedPages} of ${progress.totalPages} pages prepared")
                }
                progress = progress.copy(running = false)
                publish(if (progress.preparedChunks == 0) "No readable text in these pages"
                    else "Pages ${startPage + 1}–${endPageInclusive + 1} ready · press Play to read")
            } catch (cancel: CancellationException) { throw cancel }
            catch (limitReached: PrepareAheadLimit) {
                progress = progress.copy(running = false)
                publish(limitReached.message ?: "Preparation stopped · completed audio kept")
            } catch (error: Exception) {
                if (gate.current(generation)) {
                    issues.record(stage, error, input(), audio = key?.let(cache::file))
                    progress = progress.copy(running = false)
                    publish("${error.message ?: "Preparation failed"} · completed audio kept")
                    mutable.value = mutable.value.copy(error = error.message ?: "Preparation failed")
                }
            } finally {
                // Next reading/benchmark work joins this job, including native cleanup.
                withContext(NonCancellable) {
                    try { old?.join(); releaseSpeechModels() }
                    catch (error: Exception) {
                        issues.record("RELEASE_MODELS", error, input())
                        if (gate.current(generation) && mutable.value.error == null) {
                            progress = progress.copy(status = "Prepared audio kept · model cleanup failed")
                            mutable.value = mutable.value.copy(status = progress.status,
                                error = error.message ?: "Model cleanup failed")
                        }
                    } finally {
                        if (gate.current(generation)) {
                            progress = progress.copy(running = false)
                            mutable.value = mutable.value.copy(preparing = false, prepareAheadProgress = progress)
                            issues.activeInput = IssueInput()
                        }
                    }
                }
            }
        }
    }

    private suspend fun configureRuntime(chosen: Preferences) {
        engine.configure(SpeechRuntimeConfig(chosen.speechThreads, chosen.allowSpinning))
        aligner.configure(SpeechRuntimeConfig(chosen.alignmentThreads, chosen.allowSpinning))
    }

    private suspend fun releaseSpeechModels() = withContext(NonCancellable) {
        try { engine.unload() } finally { aligner.close() }
    }

    private fun runtimeDescription(chosen: Preferences) =
        "steps=5;temperature=.7;speed=1;backend=cpu;speechThreads=${chosen.speechThreads};" +
            "alignmentThreads=${chosen.alignmentThreads};spinning=${chosen.allowSpinning};resampler=phase-sinc-v1"

    fun start(document: DocumentEntity = checkNotNull(state.value.document), pageIndex: Int = state.value.page?.index ?: state.value.viewPageIndex, wordId: String? = null) {
        if (benchmarkActive()) return
        val old = work
        val generation = invalidate()
        startupPending = true; producerFinished = false
        mutable.value = mutable.value.copy(document = document, preparing = true, fillingBuffer = true, wantsToPlay = true,
            status = "Preparing speech", selectedWordId = null, error = null, viewPageIndex = pageIndex)
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
                    "speed" to chosen.speed.toString(), "synthesis" to runtimeDescription(chosen),
                    "generation" to generation.toString(), "audioKey" to audioKey.orEmpty(), "sampleRate" to sampleRate.toString(), "sampleCount" to sampleCount.toString()))
            try {
                // A cancelled native call owns its resources until it returns. New work waits here.
                old?.join()
                diagnostics.beginSession(chosen.model, chosen.voice)
                checkNotNull(player) { "Playback service is not connected" }
                diagnostics.record(chosen.model, PlaybackStage.VERIFYING_MODELS)
                models.verifyInstalled(chosen.model); models.verifyInstalled("alignment")
                configureRuntime(chosen)
                val manifest = models.packs.first { it.id == chosen.model }
                modelVersion = manifest.version
                val modelRevision = sha256(Json.encodeToString(VoicePack.serializer(), manifest).toByteArray())
                var chunksSinceTrim = 0
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
                        startupPending = false
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
                        currentCoroutineContext().ensureActive()
                        diagnostics.record(chosen.model, PlaybackStage.PREPARING_TEXT)
                        stage = "NORMALIZE_TEXT"; currentWords = emptyList(); currentSpeech = null; audioKey = null
                        sampleRate = null; sampleCount = null
                        issues.activeInput = input()
                        if (!chunks.hasNext()) break
                        // Check page exhaustion before waiting: next-page extraction can run while
                        // the last page's reserve is still full. Audio preparation remains bounded.
                        while (!reservePolicy.shouldPrepareMore(reserve(output), constrained()) && gate.current(generation)) delay(100)
                        currentCoroutineContext().ensureActive()
                        val prepared = chunks.next()
                        val speech = prepared.speech
                        currentWords = prepared.words; currentSpeech = speech
                        val key = cacheKey(document.id, speech, modelRevision, chosen.voice, CACHE_SYNTHESIS_RECIPE, CtcAlignment.VERSION)
                        audioKey = key
                        issues.activeInput = input()
                        stage = "READ_AUDIO_CACHE"
                        val preparationStarted = SystemClock.elapsedRealtime()
                        var synthesisMs = 0L
                        var alignmentMs = 0L
                        val cached = cache.get(key)
                        val audio = cached ?: run {
                            var committed = false
                            try {
                                mutable.value = mutable.value.copy(preparing = true, status = "Loading ${manifest.name}")
                                diagnostics.record(chosen.model, PlaybackStage.LOADING_MODEL)
                                stage = "LOAD_MODEL"
                                engine.load(chosen.model, chosen.voice)
                                mutable.value = mutable.value.copy(status = "Generating speech")
                                diagnostics.record(chosen.model, PlaybackStage.SYNTHESIZING)
                                stage = "SYNTHESIZE"
                                val synthesisStarted = SystemClock.elapsedRealtime()
                                val generated = engine.synthesize(speech.text) { gate.current(generation) }
                                synthesisMs = SystemClock.elapsedRealtime() - synthesisStarted
                                sampleRate = generated.sampleRate; sampleCount = generated.samples.size
                                stage = "WRITE_AUDIO"
                                val quantized = cache.writePcm(key, generated)
                                mutable.value = mutable.value.copy(status = "Aligning words")
                                diagnostics.record(chosen.model, PlaybackStage.ALIGNING)
                                stage = "ALIGN_WORDS"
                                val alignmentStarted = SystemClock.elapsedRealtime()
                                val alignment = alignForPlayback(aligner, quantized, speech)
                                alignmentMs = SystemClock.elapsedRealtime() - alignmentStarted
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
                        diagnostics.recordPreparation(cached != null, synthesisMs, alignmentMs,
                            SystemClock.elapsedRealtime() - preparationStarted, audio.durationMs,
                            characters = speech.text.codePointCount(0, speech.text.length))
                        val startMs = if (first) playbackStartMs(audio, requested) else 0L
                        diagnostics.record(chosen.model, PlaybackStage.STARTING_AUDIO)
                        stage = "QUEUE_AUDIO"
                        currentCoroutineContext().ensureActive()
                        val sentenceId = prepared.words.first().sentenceId
                        queued[key] = QueuedSpeech(page, audio, "${page.id}:$sentenceId",
                            prepared.words.last().id == words.last { it.sentenceId == sentenceId }.id)
                        val item = MediaItem.Builder().setMediaId(key).setUri(Uri.fromFile(java.io.File(audio.audioPath)))
                            .setMediaMetadata(MediaMetadata.Builder().setTitle(document.title).setArtist("${manifest.name} · ${manifest.voices.first { it.id == chosen.voice }.name}").build()).build()
                        val advancePausedEnd = !startupPending && !output.playWhenReady && output.playbackState == Player.STATE_ENDED
                        // Catch depletion before publishing the new item, even between observer ticks.
                        updateReserve(output)
                        if (recoveringReserve) {
                            pendingMediaItems += item
                            if (firstReadyAtMs == null) firstReadyAtMs = SystemClock.elapsedRealtime()
                        } else output.addMediaItem(item)
                        if (first) {
                            output.seekTo(0, startMs)
                            firstReadyAtMs = SystemClock.elapsedRealtime()
                            first = false; requested = null
                        } else if (advancePausedEnd) {
                            output.seekToNextMediaItem(); output.prepare()
                        }
                        updateReserve(output)
                        mutable.value = mutable.value.copy(preparing = startupPending)
                        diagnostics.record(chosen.model, PlaybackStage.AUDIO_READY)
                        while (output.currentMediaItemIndex > 1) {
                            val removed = output.getMediaItemAt(0).mediaId
                            output.removeMediaItem(0); queued.remove(removed)
                        }
                        // Avoid a database-wide scan and directory walk after every sentence.
                        if (++chunksSinceTrim >= 8) {
                            cache.trim(chosen.cacheMb * 1024L * 1024L, queued.keys.toSet())
                            chunksSinceTrim = 0
                        }
                    }
                    index++
                }
                if (first) {
                    startupPending = false
                    mutable.value = mutable.value.copy(preparing = false, fillingBuffer = false, wantsToPlay = false, status = "No readable text")
                }
                cache.trim(chosen.cacheMb * 1024L * 1024L, queued.keys.toSet())
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
                withContext(NonCancellable) { old?.join() }
                if (gate.current(generation)) {
                    producerFinished = true
                    // A short document, or an error further ahead, must not strand valid audio.
                    player?.let(::updateReserve)
                    if (queued.isEmpty() || (recoveringReserve && pendingMediaItems.isEmpty())) {
                        startupPending = false
                        recoveringReserve = false
                        player?.pause()
                        mutable.value = mutable.value.copy(preparing = false, fillingBuffer = false, wantsToPlay = false)
                    }
                    issues.activeInput = IssueInput()
                }
            }
        }
    }
    private fun constrained(): Boolean = memoryPressure.isActive(SystemClock.elapsedRealtime()) ||
        power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE

    private fun reserve(player: Player): AudioReserve {
        val ids = if (recoveringReserve) pendingMediaItems.map { it.mediaId }
            else (player.currentMediaItemIndex.coerceAtLeast(0) until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        return audioReserve(ids.mapNotNull { id ->
            queued[id]?.let {
                PreparedAudioSegment(it.sentenceId, it.audio.durationMs, it.completesSentence)
            }
        }, when {
            recoveringReserve -> 0
            player.playbackState == Player.STATE_ENDED -> Long.MAX_VALUE
            else -> player.currentPosition
        }, player.playbackParameters.speed)
    }

    private fun updateReserve(player: Player) {
        if (!startupPending && !producerFinished && player.playWhenReady &&
            player.playbackState == Player.STATE_ENDED && player.currentMediaItemIndex == player.mediaItemCount - 1) {
            startupPending = true; recoveringReserve = true; firstReadyAtMs = null
            diagnostics.recordUnderrun()
        }
        val reserve = reserve(player)
        if (startupPending && reservePolicy.canStart(reserve, firstReadyAtMs, SystemClock.elapsedRealtime(), producerFinished, constrained())) {
            startupPending = false
            if (recoveringReserve) {
                val nextIndex = player.mediaItemCount
                player.addMediaItems(pendingMediaItems.toList())
                pendingMediaItems.clear(); recoveringReserve = false
                player.seekTo(nextIndex, 0)
            }
            player.prepare(); player.play()
            mutable.value = mutable.value.copy(preparing = false)
        }
        diagnostics.recordBuffer(reserve.queuedChunkCount, reserve.remainingListeningMs)
        mutable.value = mutable.value.copy(bufferedSentences = reserve.futureSentenceCount,
            bufferedListeningMs = reserve.remainingListeningMs, fillingBuffer = startupPending)
    }

    private fun releaseRecoveryForSeek(player: Player) {
        // Rewinding while waiting is a user-directed position change. Keep prepared audio,
        // but never let the pending recovery start jump over the user's chosen position.
        recoveringReserve = false; startupPending = false; firstReadyAtMs = null
        if (pendingMediaItems.isNotEmpty()) player.addMediaItems(pendingMediaItems.toList())
        pendingMediaItems.clear()
        mutable.value = mutable.value.copy(fillingBuffer = false)
    }
    suspend fun changeModel(model: String, voice: String) {
        check(!benchmarkActive()) { "Finish or cancel the benchmark before changing a model" }
        val old = work; val generation = invalidate()
        mutable.value = mutable.value.copy(preparing = false, status = "Paused")
        old?.join(); releaseSpeechModels()
        if (gate.current(generation)) {
            settings = settings.copy(model = model, voice = voice)
            preferences.set(settings)
        }
    }
    suspend fun deleteModel(id: String) {
        check(!benchmarkActive()) { "Finish or cancel the benchmark before removing a model" }
        val old = work; stop(); old?.join(); releaseSpeechModels(); models.delete(id)
    }
    suspend fun releaseForBenchmark() {
        val old = work
        stop(); old?.join(); releaseSpeechModels()
    }
    suspend fun clearCache() { val old = work; stop(); old?.join(); cache.clear() }
    suspend fun deleteDocument(document: DocumentEntity) {
        val old = work; stop(); old?.join()
        cache.deleteDocument(document.id); documents.delete(document)
        if (state.value.document?.id == document.id) mutable.value = ReaderPlayback()
    }
}
