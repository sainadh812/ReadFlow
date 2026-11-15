package app.readflow.dictionary

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** App-scoped, offline-only lookups. The sole network request is an explicit pack download. */
class DictionaryRepository(context: Context) {
    val manifest: DictionaryManifest = Json { ignoreUnknownKeys = true }.decodeFromString<DictionaryManifest>(
        context.assets.open("dictionary/manifest.json").bufferedReader().use { it.readText() },
    ).also { it.validate() }

    private val root = File(context.noBackupFilesDir, "english-dictionary")
    private val installed = File(root, "current")
    private val staging = File(root, ".installing")
    private val previous = File(root, ".previous")
    private val archive = File(root, "${manifest.version}.sqlite.gz.part")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val operationLock = Any()
    private var installJob: Job? = null
    private var removeJob: Job? = null
    @Volatile private var databaseValid = false
    @Volatile private var installedManifest: DictionaryManifest? = null
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
        .followSslRedirects(false).build()
    private val mutableState = MutableStateFlow(DictionaryPackState())
    val state = mutableState.asStateFlow()

    private val initialization = scope.launch {
        mutex.withLock {
            try {
                check(root.isDirectory || root.mkdirs()) { "Cannot create dictionary storage" }
                recoverDictionary(installed, previous)
                staging.deleteRecursively()
                val marker = File(installed, ".verified").takeIf { it.isFile }?.readText()
                val receipt = File(installed, RECEIPT_NAME).takeIf { it.isFile }?.let {
                    Json.decodeFromString<DictionaryManifest>(it.readText()).also { saved -> saved.validateInstalledFor(manifest) }
                } ?: manifest.takeIf { marker == it.installedSha256 }
                if (receipt != null && marker == receipt.installedSha256) {
                    val database = File(installed, DATABASE_NAME)
                    check(database.length() == receipt.installedBytes && dictionaryFileHash(database) == receipt.installedSha256) {
                        "Dictionary files are damaged. Download the dictionary again."
                    }
                    verifyDatabase(database, receipt)
                    installedManifest = receipt
                    databaseValid = true
                }
                idle()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                databaseValid = false
                mutableState.value = DictionaryPackState(DictionaryPackPhase.ERROR, error = "Dictionary files could not be opened. Download the dictionary again.")
            }
        }
    }

    fun install() = synchronized(operationLock) {
        if (dictionaryOperationPending(installJob, removeJob)) return@synchronized
        installJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                initialization.join()
                if (databaseValid && !updateAvailable()) { idle(); return@launch }
                check(root.isDirectory || root.mkdirs()) { "Cannot create dictionary storage" }
                check(!staging.exists() || staging.deleteRecursively()) { "Cannot clear interrupted dictionary setup" }
                val pending = (manifest.downloadBytes - archive.length()).coerceAtLeast(0)
                check(root.usableSpace > pending + manifest.installedBytes + 32L * 1024 * 1024) {
                    "Not enough storage for the English dictionary. Free some space and retry."
                }
                // Old definitions stay available throughout network IO and staging.
                download()
                check(staging.mkdirs()) { "Cannot create dictionary installation folder" }
                val progress = reporter(DictionaryPackPhase.INSTALLING, manifest.installedBytes)
                unpackDictionary(archive, File(staging, DATABASE_NAME), manifest.installedBytes, manifest.installedSha256, progress)
                currentCoroutineContext().ensureActive()
                verifyDatabase(File(staging, DATABASE_NAME), manifest)
                FileOutputStream(File(staging, RECEIPT_NAME)).use { output ->
                    output.write(Json.encodeToString(DictionaryManifest.serializer(), manifest).toByteArray())
                    output.fd.sync()
                }
                FileOutputStream(File(staging, ".verified")).use { output ->
                    output.write(manifest.installedSha256.toByteArray())
                    output.fd.sync()
                }
                mutex.withLock {
                    currentCoroutineContext().ensureActive()
                    promoteDictionary(staging, installed, previous)
                    installedManifest = manifest
                    databaseValid = true
                    archive.delete()
                    idle()
                }
            } catch (cancel: CancellationException) {
                staging.deleteRecursively()
                idle()
                throw cancel
            } catch (error: Exception) {
                staging.deleteRecursively()
                // A cancelled HTTP call may surface an IOException before the
                // coroutine boundary converts it to cancellation. Do not expose
                // a spurious download error or admit a retry before cleanup ends.
                if (!currentCoroutineContext().isActive) {
                    idle()
                    currentCoroutineContext().ensureActive()
                }
                mutableState.value = snapshot(DictionaryPackPhase.ERROR, error = error.message ?: "Dictionary download failed. Please retry.")
            }
        }.also { it.start() }
    }

    fun cancelInstall() = synchronized(operationLock) { installJob?.cancel(); Unit }

    fun remove() = synchronized(operationLock) {
        if (dictionaryOperationPending(removeJob)) return@synchronized
        val downloading = installJob
        downloading?.cancel()
        removeJob = scope.launch(start = CoroutineStart.LAZY) {
            initialization.join()
            downloading?.join()
            mutex.withLock {
                mutableState.value = snapshot(DictionaryPackPhase.CHECKING)
                try {
                    databaseValid = false
                    installedManifest = null
                    check(root.deleteRecursively()) { "Dictionary files could not be removed. Please retry." }
                    idle()
                } catch (error: Exception) {
                    mutableState.value = DictionaryPackState(DictionaryPackPhase.ERROR, error = error.message ?: "Dictionary could not be removed.")
                }
            }
        }.also { it.start() }
    }

    suspend fun lookup(query: String): DictionaryLookup = withContext(Dispatchers.IO) {
        val parsed = try { dictionaryQuery(query) } catch (error: IllegalArgumentException) {
            return@withContext DictionaryLookup(query.take(256), error = error.message)
        }
        if (parsed.key.isEmpty()) return@withContext DictionaryLookup(parsed.display)
        initialization.join()
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (!databaseValid) return@withLock DictionaryLookup(parsed.display, error = "Download the English dictionary to look up words offline.")
            try {
                openDatabase(File(installed, DATABASE_NAME)).use { database ->
                    val candidates = mutableListOf<DictionaryEntry>()
                    // Two ranks bound memory without letting a polysemous noun hide
                    // all verb senses. All forms come from the dataset, not stemming.
                    database.rawQuery(
                        """WITH matches AS (
                            SELECT $ENTRY_COLUMNS,
                                CASE WHEN lookup = ? THEN CASE WHEN headword = ? THEN 0 ELSE 2 END ELSE 1 END AS match_rank,
                                row_number() OVER (PARTITION BY headword, pos ORDER BY id) AS sense_rank
                            FROM entries WHERE lookup = ? OR headword IN (
                                SELECT DISTINCT headword FROM forms WHERE lookup = ? ORDER BY headword LIMIT 8
                            )
                        ) SELECT $ENTRY_COLUMNS FROM (
                            SELECT *, row_number() OVER (PARTITION BY pos ORDER BY sense_rank, match_rank, id) AS pos_rank FROM matches
                        ) ORDER BY pos_rank, sense_rank, match_rank, id LIMIT 128""".trimIndent(),
                        arrayOf(parsed.key, parsed.display, parsed.key, parsed.key),
                    ).use { cursor -> while (cursor.moveToNext()) candidates += cursor.entry() }
                    currentCoroutineContext().ensureActive()
                    val entries = selectDictionarySenses(candidates, MAX_SENSES)
                    DictionaryLookup(parsed.display, entries, if (entries.isEmpty()) suggestions(database, parsed.key) else emptyList())
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                databaseValid = false
                mutableState.value = DictionaryPackState(DictionaryPackPhase.ERROR, error = "Dictionary files could not be read. Download the dictionary again.")
                DictionaryLookup(parsed.display, error = "Dictionary files could not be read. Download the dictionary again.")
            }
        }
    }

    /** Only needed when disposing a repository in a host/test; normal ownership is application-wide. */
    fun close() { scope.cancel(); client.dispatcher.cancelAll(); client.connectionPool.evictAll() }

    private fun idle() {
        mutableState.value = snapshot(if (databaseValid) DictionaryPackPhase.READY else DictionaryPackPhase.NOT_INSTALLED)
    }

    private fun updateAvailable() = databaseValid && installedManifest?.installedSha256 != manifest.installedSha256

    private fun snapshot(phase: DictionaryPackPhase, received: Long = 0, total: Long = 0, error: String? = null) = DictionaryPackState(
        phase, received, total, error, databaseValid, updateAvailable(),
        installedManifest?.version.takeIf { databaseValid }, installedManifest?.installedBytes?.takeIf { databaseValid } ?: 0,
    )

    private fun reporter(phase: DictionaryPackPhase, total: Long): (Long) -> Unit {
        mutableState.value = snapshot(phase, total = total)
        var last = 0L
        return { count ->
            val now = System.nanoTime()
            if (count >= total || now - last >= 150_000_000L) {
                mutableState.value = snapshot(phase, count, total)
                last = now
            }
        }
    }

    private suspend fun download() {
        if (archive.isFile && archive.length() == manifest.downloadBytes && dictionaryFileHash(archive) == manifest.downloadSha256) return
        if (archive.length() >= manifest.downloadBytes) check(archive.delete()) { "Cannot clear an invalid dictionary download" }
        val offset = archive.length()
        val progress = reporter(DictionaryPackPhase.DOWNLOADING, manifest.downloadBytes)
        progress(offset)
        val request = Request.Builder().url(manifest.url).header("Accept-Encoding", "identity")
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
        val context = currentCoroutineContext()
        withDownloadCall(request) { response ->
            check(response.request.url.isHttps) { "Dictionary download redirected to an insecure address" }
            check(response.isSuccessful) { "Dictionary download failed (HTTP ${response.code}). Please retry." }
            val append = offset > 0 && response.code == 206
            if (response.code == 206) {
                val expectedStart = if (append) offset else 0L
                val range = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(response.header("Content-Range").orEmpty())
                check(range != null && range.groupValues[1].toLong() == expectedStart && range.groupValues[2].toLong() == manifest.downloadBytes - 1 && range.groupValues[3].toLong() == manifest.downloadBytes) {
                    "Dictionary server returned an invalid partial download"
                }
            }
            val body = checkNotNull(response.body) { "Dictionary download was empty" }
            val expectedBody = manifest.downloadBytes - if (append) offset else 0
            check(body.contentLength() < 0 || body.contentLength() == expectedBody) { "Dictionary download size did not match" }
            body.byteStream().use { input ->
                FileOutputStream(archive, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var received = if (append) offset else 0L
                    while (true) {
                        context.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(count.toLong() <= manifest.downloadBytes - received) { "Dictionary download exceeds its expected size" }
                        output.write(buffer, 0, count)
                        received += count
                        progress(received)
                    }
                    output.fd.sync()
                }
            }
        }
        check(archive.length() == manifest.downloadBytes) { "Dictionary download was interrupted. Retry to continue." }
        if (dictionaryFileHash(archive) != manifest.downloadSha256) {
            archive.delete()
            error("Dictionary download failed its integrity check. Please retry.")
        }
    }

    private suspend fun <T> withDownloadCall(request: Request, read: (Response) -> T): T = coroutineScope {
        val call = client.newCall(request)
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try { call.execute().use(read) } finally { cancellation.cancel() }
    }

    private fun openDatabase(file: File): SQLiteDatabase = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)

    private fun verifyDatabase(file: File, expected: DictionaryManifest) {
        openDatabase(file).use { database ->
            check(database.version == expected.schemaVersion) { "Unsupported dictionary database version" }
            database.rawQuery("PRAGMA quick_check(1)", null).use { check(it.moveToFirst() && it.getString(0) == "ok") { "Dictionary database is damaged" } }
            database.rawQuery("SELECT value FROM metadata WHERE key = 'pack_id'", null).use { check(it.moveToFirst() && it.getString(0) == expected.id) { "Unexpected dictionary pack" } }
            database.rawQuery("SELECT value FROM metadata WHERE key = 'version'", null).use { check(it.moveToFirst() && it.getString(0) == expected.version) { "Unexpected dictionary release" } }
            database.rawQuery("SELECT $ENTRY_COLUMNS, lookup FROM entries LIMIT 1", null).use { check(it.moveToFirst()) { "Dictionary has no definitions" } }
            database.rawQuery("SELECT lookup, headword FROM forms LIMIT 1", null).use { check(it.moveToFirst()) { "Dictionary has no word forms" } }
        }
    }

    private fun suggestions(database: SQLiteDatabase, key: String): List<String> {
        val candidates = mutableListOf<String>()
        fun collect(prefix: String, limit: Int) {
            database.rawQuery("SELECT DISTINCT headword FROM entries WHERE lookup >= ? AND lookup < ? LIMIT $limit", arrayOf(prefix, prefix + '\uffff')).use {
                while (it.moveToNext()) candidates += it.getString(0)
            }
        }
        collect(key, 24)
        if (candidates.size < 8 && key.length >= 3) collect(key.take(2), 96)
        return rankDictionarySuggestions(key, candidates)
    }

    private fun Cursor.entry() = DictionaryEntry(
        getLong(0), getString(1).take(256), getString(2).take(64), getString(3).take(8192),
        getString(4).take(4096), getString(5).take(256), getString(6).take(2048), getString(7).take(512),
    )

    private companion object {
        const val DATABASE_NAME = "dictionary.sqlite"
        const val RECEIPT_NAME = "manifest.json"
        const val MAX_SENSES = 32
        const val ENTRY_COLUMNS = "id, headword, pos, definition, example, ipa, source_url, labels"
    }
}
