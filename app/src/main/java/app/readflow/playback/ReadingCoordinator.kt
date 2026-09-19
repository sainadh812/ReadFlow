package app.readflow.playback

import android.net.Uri
import android.os.PowerManager
import androidx.media3.common.*
import app.readflow.core.*
import app.readflow.data.*
import app.readflow.models.*
import app.readflow.speech.*
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
)
class GenerationGate {
    private val counter = AtomicLong()
    fun next() = counter.incrementAndGet()
    fun current(id: Long) = counter.get() == id
}

class ReadingCoordinator(
    private val documents: Documents, private val models: ModelStore, private val cache: AudioCache,
    private val preferences: PreferenceStore, private val power: PowerManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = GenerationGate()
    private val mutable = MutableStateFlow(ReaderPlayback())
    val state = mutable.asStateFlow()
    private val engine = SherpaEngine(models::directory) { id -> models.packs.first { it.id == id }.voices }
    private val aligner = OnnxForcedAligner { java.io.File(models.directory("alignment"), "model.onnx") }
    private val normalizer = EnglishNormalizer()
    private var work: Job? = null
    private var observer: Job? = null
    private var player: Player? = null
    private val queued = linkedMapOf<String, Pair<PageContent, AlignedAudio>>()
    private var durableWord: String? = null
    private var settings = Preferences()
    private var memoryPressure = false

    init { scope.launch { preferences.values.collect { settings = it; player?.setPlaybackSpeed(it.speed) } } }
    fun pressure() { memoryPressure = true }
    fun attach(player: Player) {
        this.player = player
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
                    if (active != null) durableWord = active
                    mutable.value = mutable.value.copy(page = page, activeWordId = active, activeWordIds = ids, currentChunk = chunk,
                        playing = player.isPlaying, wantsToPlay = player.playWhenReady, positionMs = mediaMs, durationMs = chunk.durationMs,
                        status = if (player.playbackState == Player.STATE_BUFFERING) "Buffering" else if (player.isPlaying) "Reading" else if (mutable.value.preparing) "Preparing next sentence" else "Paused")
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
    fun detach() { observer?.cancel(); player = null; stop() }
    fun open(document: DocumentEntity, pageIndex: Int? = null) {
        val generation = invalidate()
        mutable.value = ReaderPlayback(document = document, preparing = true, status = "Extracting page")
        work = scope.launch {
            try {
                val position = documents.dao.position(document.id)
                val page = documents.loadPage(document, pageIndex ?: position?.page ?: 0)
                if (!gate.current(generation)) return@launch
                durableWord = position?.wordId?.takeIf { id -> page.words.any { it.id == id } }
                mutable.value = ReaderPlayback(document, page, selectedWordId = durableWord, status = if (page.words.isEmpty()) "No readable text on this page" else "Paused")
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { if (gate.current(generation)) mutable.value = mutable.value.copy(preparing = false, status = e.message ?: "Extraction failed") }
        }
    }
    private fun invalidate(): Long {
        val generation = gate.next()
        work?.cancel()
        player?.pause(); player?.stop(); player?.clearMediaItems(); queued.clear()
        mutable.value = mutable.value.copy(activeWordId = null, activeWordIds = emptySet(), currentChunk = null, playing = false, wantsToPlay = false, positionMs = 0, durationMs = 0)
        return generation
    }
    fun stop() { invalidate(); mutable.value = mutable.value.copy(preparing = false, status = "Paused") }
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
        val current = state.value; val page = current.page ?: return
        val word = page.words.firstOrNull { it.id == current.activeWordId || it.id == durableWord }
        val sentences = page.words.groupBy { it.sentenceId }.values.toList()
        val index = sentences.indexOfFirst { line -> line.any { it.id == word?.id } }.coerceAtLeast(0)
        val target = sentences.getOrNull(index + direction)?.firstOrNull()
        if (target != null) start(wordId = target.id)
        else current.document?.let { document ->
            val next = page.index + direction
            if (next in 0 until document.pageCount) start(document, next)
        }
    }
    fun start(document: DocumentEntity = checkNotNull(state.value.document), pageIndex: Int = state.value.page?.index ?: 0, wordId: String? = null) {
        val old = work
        val generation = invalidate()
        mutable.value = mutable.value.copy(document = document, preparing = true, status = "Preparing speech", selectedWordId = null, error = null)
        val chosen = settings
        durableWord = wordId
        work = scope.launch {
            try {
                // A cancelled native call owns its resources until it returns. New work waits here.
                old?.join()
                checkNotNull(player) { "Playback service is not connected" }
                models.verifyInstalled(chosen.model); models.verifyInstalled("alignment")
                val manifest = models.packs.first { it.id == chosen.model }
                var index = pageIndex
                var first = true
                var requested = wordId
                while (index < document.pageCount && gate.current(generation)) {
                    val page = documents.loadPage(document, index)
                    val words = page.words.filter { !chosen.skipMargins || !it.marginal || it.id == requested }
                    val chunks = normalizedChunks(words)
                    val start = if (first && requested != null) chunks.indexOfFirst { group -> group.any { it.id == requested } }.coerceAtLeast(0) else 0
                    for (group in chunks.drop(start)) {
                        val output = checkNotNull(player)
                        val targetSeconds = if (memoryPressure || power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) 8 else 30
                        while (remainingAudioMs(output) > targetSeconds * 1000 && gate.current(generation)) delay(200)
                        currentCoroutineContext().ensureActive()
                        val speech = normalizer.normalize(group)
                        val modelRevision = sha256(Json.encodeToString(VoicePack.serializer(), manifest).toByteArray())
                        val key = cacheKey(document.id, speech, modelRevision, chosen.voice, "steps=5;temperature=.7;speed=1;threads=2", CtcAlignment.VERSION)
                        val audio = cache.get(key) ?: run {
                            var committed = false
                            try {
                                mutable.value = mutable.value.copy(preparing = true, status = "Synthesizing and aligning")
                                engine.load(chosen.model, chosen.voice)
                                val generated = engine.synthesize(speech.text) { gate.current(generation) }
                                val quantized = cache.writePcm(key, generated)
                                val timings = aligner.align(quantized, speech)
                                currentCoroutineContext().ensureActive()
                                AlignedAudio(key, cache.file(key).path, withContext(Dispatchers.IO) { fileHash(cache.file(key)) }, quantized.sampleRate, quantized.samples.size.toLong(),
                                    timings, speech.sourceIds, speech, manifest.id + manifest.version, chosen.voice, CtcAlignment.VERSION).also { cache.commit(document.id, it); committed = true }
                            } finally {
                                if (!committed) withContext(NonCancellable + Dispatchers.IO) { cache.file(key).delete() }
                            }
                        }
                        if (!gate.current(generation)) return@launch
                        if (first && requested != null) check(audio.timings.any { it.wordId == requested }) { "This selection has no validated spoken boundary. Select a spoken word." }
                        queued[key] = page to audio
                        val item = MediaItem.Builder().setMediaId(key).setUri(Uri.fromFile(java.io.File(audio.audioPath)))
                            .setMediaMetadata(MediaMetadata.Builder().setTitle(document.title).setArtist("${manifest.name} · ${manifest.voices.first { it.id == chosen.voice }.name}").build()).build()
                        output.addMediaItem(item)
                        if (first) {
                            val target = audio.timings.firstOrNull { it.wordId == requested } ?: audio.timings.first()
                            output.seekTo(0, (target.startSample * 1000 + audio.sampleRate - 1) / audio.sampleRate)
                            output.prepare(); output.play(); first = false; requested = null
                        } else if (output.playbackState == Player.STATE_ENDED) {
                            val shouldPlay = output.playWhenReady
                            output.seekToNextMediaItem(); output.prepare(); output.playWhenReady = shouldPlay
                        }
                        mutable.value = mutable.value.copy(preparing = false)
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
                if (gate.current(generation)) mutable.value = mutable.value.copy(preparing = false, status = error.message ?: "Speech unavailable", error = error.message ?: "Speech unavailable", activeWordId = null)
            }
        }
    }
    private fun normalizedChunks(words: List<SourceWord>): List<List<SourceWord>> {
        fun split(group: List<SourceWord>): List<List<SourceWord>> {
            if (normalizer.normalize(group).text.length <= 220) return listOf(group)
            check(group.size > 1) { "This token exceeds the speech model limit; it was not truncated" }
            val middle = group.size / 2
            return split(group.take(middle)) + split(group.drop(middle))
        }
        return chunkWords(words).flatMap(::split)
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
