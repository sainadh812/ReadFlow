package app.readflow.dictionary

import java.net.URI
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.serialization.Serializable

/** Cancellation starts cleanup; only completion releases the shared install directory. */
internal fun dictionaryOperationPending(vararg jobs: Job?): Boolean = jobs.any { it != null && !it.isCompleted }

@Serializable
data class DictionaryManifest(
    val id: String,
    val version: String,
    val name: String,
    val url: String,
    val downloadBytes: Long,
    val downloadSha256: String,
    val installedBytes: Long,
    val installedSha256: String,
    val schemaVersion: Int,
    val source: String,
    val credits: String,
    val licenseUrl: String,
) {
    internal fun validate() {
        require(id.matches(Regex("[a-z0-9][a-z0-9-]{0,63}")) && version.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))) { "Invalid dictionary identity" }
        val address = URI(url)
        require(address.scheme == "https" && !address.host.isNullOrBlank() && address.userInfo == null && address.fragment == null) {
            "Dictionary download must use HTTPS"
        }
        require(downloadBytes in 1..512L * 1024 * 1024 && installedBytes in 1..2L * 1024 * 1024 * 1024) {
            "Dictionary pack is too large"
        }
        require(listOf(downloadSha256, installedSha256).all { it.matches(Regex("[a-f0-9]{64}")) }) { "Invalid dictionary checksums" }
        require(schemaVersion == 1) { "Unsupported dictionary format" }
        require(source.isNotBlank() && credits.isNotBlank() && licenseUrl.startsWith("https://")) { "Missing dictionary credits" }
    }

    /** A verified earlier edition remains readable while an update is downloaded. */
    internal fun validateInstalledFor(expected: DictionaryManifest) {
        validate()
        require(id == expected.id && schemaVersion == expected.schemaVersion) { "Installed dictionary is incompatible" }
    }
}

enum class DictionaryPackPhase { CHECKING, NOT_INSTALLED, DOWNLOADING, INSTALLING, READY, ERROR }

data class DictionaryPackState(
    val phase: DictionaryPackPhase = DictionaryPackPhase.CHECKING,
    val received: Long = 0,
    val total: Long = 0,
    val error: String? = null,
    val installed: Boolean = false,
    val updateAvailable: Boolean = false,
    val installedVersion: String? = null,
    val installedBytes: Long = 0,
) {
    val busy: Boolean get() = phase == DictionaryPackPhase.CHECKING || phase == DictionaryPackPhase.DOWNLOADING || phase == DictionaryPackPhase.INSTALLING
}

data class DictionaryEntry(
    val id: Long,
    val headword: String,
    val pos: String,
    val definition: String,
    val example: String,
    val ipa: String,
    val sourceUrl: String,
    val labels: String,
)

data class DictionaryLookup(
    val query: String,
    val entries: List<DictionaryEntry> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val error: String? = null,
)

/** Preserve first-match priority, while a long noun entry cannot hide its verb meanings. */
internal fun selectDictionarySenses(candidates: List<DictionaryEntry>, limit: Int = 32): List<DictionaryEntry> {
    require(limit >= 0)
    val distinct = candidates.distinctBy { it.id }
    val selected = distinct.distinctBy { it.pos }.take(limit).toMutableList()
    val selectedIds = selected.mapTo(mutableSetOf()) { it.id }
    val groups = distinct.groupBy { it.headword to it.pos }.values.map { it.iterator() }
    while (selected.size < limit) {
        var added = false
        for (group in groups) {
            while (group.hasNext()) {
                val entry = group.next()
                if (!selectedIds.add(entry.id)) continue
                selected += entry
                added = true
                break
            }
            if (selected.size == limit) break
        }
        if (!added) break
    }
    return selected
}

internal data class DictionaryQuery(val display: String, val key: String)

/** Lookup normalization never edits the document or goes through speech normalization. */
internal fun dictionaryQuery(raw: String): DictionaryQuery {
    require(raw.length <= 256) { "Search for a shorter word or phrase." }
    val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC).map { character ->
        when (character) {
            '\u2018', '\u2019', '\u02bc', '\uff07' -> '\''
            in '\u2010'..'\u2015', '\u2212', '\ufe63', '\uff0d' -> '-'
            else -> character
        }
    }.joinToString("").trim { !it.isLetterOrDigit() && Character.getType(it) !in setOf(
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(),
    ) }
        // Android's ICU regex rejects the JVM-only (?U) flag. Include Unicode
        // separators and NEL explicitly so Android and desktop tests agree.
        .replace(Regex("[\\s\\p{Z}\\u0085]+"), " ")
    require(normalized.length <= 256) { "Search for a shorter word or phrase." }
    return DictionaryQuery(normalized, normalized.lowercase(Locale.ROOT))
}

/** Suggestions are bounded spelling hints; they are never used as invented definitions. */
internal fun rankDictionarySuggestions(query: String, candidates: List<String>, limit: Int = 8): List<String> =
    candidates.distinct().mapNotNull { candidate ->
        runCatching { candidate to dictionaryQuery(candidate).key }.getOrNull()
    }
        .filter { (_, key) -> key != query }
        .map { (word, key) -> Triple(word, key.startsWith(query), editDistance(query, key)) }
        .filter { (_, prefix, distance) -> prefix || distance <= 2 }
        .sortedWith(compareByDescending<Triple<String, Boolean, Int>> { it.second }.thenBy { it.third }.thenBy { it.first })
        .take(limit).map { it.first }

private fun editDistance(a: String, b: String): Int {
    if (kotlin.math.abs(a.length - b.length) > 2) return 3
    var previous = IntArray(b.length + 1) { it }
    for (i in a.indices) {
        val row = IntArray(b.length + 1)
        row[0] = i + 1
        for (j in b.indices) row[j + 1] = minOf(row[j] + 1, previous[j + 1] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
        previous = row
    }
    return previous[b.length]
}
