package app.readflow.diagnostics

import android.os.Build
import app.readflow.BuildConfig
import app.readflow.core.*
import app.readflow.data.DocumentEntity
import java.net.URI

fun issueEnvironment() = mapOf(
    "appVersion" to BuildConfig.VERSION_NAME, "versionCode" to BuildConfig.VERSION_CODE.toString(),
    "device" to "${Build.MANUFACTURER} ${Build.MODEL}", "android" to "${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}",
    "abis" to Build.SUPPORTED_ABIS.joinToString(), "heapLimitMiB" to (Runtime.getRuntime().maxMemory() / 1048576).toString(),
    "sherpa" to "1.13.8", "alignmentOrt" to "1.20.0", "alignmentVersion" to CtcAlignment.VERSION,
    "pipeline" to PIPELINE_VERSION, "normalization" to EnglishNormalizer.VERSION, "inference" to "CPU, two threads",
)

fun issueSource(source: String): String = try {
    val uri = URI(source)
    if (uri.scheme in setOf("https", "http")) URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toASCIIString()
    else if (uri.scheme == "content") "content://${uri.authority}/[private document identifier omitted]"
    else source.take(2048)
} catch (_: Exception) { "Unparseable source identifier omitted" }

fun documentIssueInput(document: DocumentEntity, page: PageContent? = null, words: List<SourceWord> = emptyList(),
    speech: SpeechText? = null, pageIndex: Int? = page?.index, requestedWordId: String? = null,
    details: Map<String, String> = emptyMap(),
): IssueInput {
    val first = words.minOfOrNull { it.sourceStart }
    val last = words.maxOfOrNull { it.sourceEnd }
    val original = if (page != null && first != null && last != null && first >= 0 && last <= page.original.length) page.original.substring(first, last) else page?.original?.take(IssueLogs.TEXT_LIMIT)
    return IssueInput(document.id, document.title, issueSource(document.source), document.mime, pageIndex, requestedWordId,
        original, if (words.isEmpty()) page?.reading?.take(IssueLogs.TEXT_LIMIT) else words.joinToString(" ") { it.text },
        speech?.text, words, speech?.tokens.orEmpty(), page?.extraction,
        mapOf("documentSha256" to document.hash, "documentProcessingVersion" to document.processingVersion,
            "pageCount" to document.pageCount.toString(), "pageOriginalCharacters" to (page?.original?.length ?: 0).toString(),
            "nonAsciiCodePoints" to original.orEmpty().take(IssueLogs.TEXT_LIMIT).codePoints().filter { it > 127 }.distinct().limit(64).toArray().joinToString { "U+%04X".format(it) },
            "excerptPolicy" to "Failed word range when available, otherwise first 8192 characters of page; at most 128 word records",
            "sourcePolicy" to "URL credentials/query/fragment and SAF document ID omitted") + details)
}
