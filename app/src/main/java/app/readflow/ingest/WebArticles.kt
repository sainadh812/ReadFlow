package app.readflow.ingest

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.*
import app.readflow.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

data class Article(val title: String, val page: ExtractedPage, val sanitizedHtml: String)
interface ArticleExtractor { suspend fun import(url: String): Article }
class ReadabilityArticles(private val context: Context) : ArticleExtractor {
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).followSslRedirects(false).build()
    override suspend fun import(url: String): Article = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")) { "Use an HTTPS article URL" }
        val html = client.newCall(Request.Builder().url(url).header("User-Agent", "ReadFlow/0.1 (local article reader)").build()).execute().use { response ->
            check(response.request.url.isHttps) { "Insecure webpage redirect rejected" }
            check(response.isSuccessful) { "Article is inaccessible (HTTP ${response.code}). Sign-in and paywalled content are not supported." }
            require(response.header("Content-Type").orEmpty().contains("html")) { "This URL does not return a webpage" }
            response.body!!.byteStream().use { input ->
                val bytes = input.readNBytes(4 * 1024 * 1024 + 1)
                require(bytes.size <= 4 * 1024 * 1024) { "Webpage is too large" }
                bytes.toString(Charsets.UTF_8)
            }
        }
        extractHtml(html)
    }
    internal suspend fun extractHtml(html: String): Article = withContext(Dispatchers.IO) {
        val source = Jsoup.parse(html)
        source.select("script,style,iframe,object,embed,form,link,meta,svg,math,noscript").remove()
        val clean = Jsoup.clean(source.outerHtml(), Safelist().addTags("html", "head", "title", "body", "article", "main", "section", "div", "p", "h1", "h2", "h3", "h4", "blockquote", "ul", "ol", "li", "strong", "em", "b", "i", "br", "table", "thead", "tbody", "tr", "td", "th").addAttributes("*", "class", "id"))
        val result = extractSandboxed(clean)
        val content = Jsoup.parse(result.getValue("content").jsonPrimitive.content)
        val hasTables = content.select("table").isNotEmpty()
        content.select("table").remove()
        val paragraphs = content.select("h1,h2,h3,h4,p,li,blockquote").filter { it.children().none { child -> child.tagName() in setOf("p", "li", "blockquote") } }
        val elements = paragraphs.mapIndexed { i, p -> TextElement(p.text(), emptyList(), i, i, p.tagName().startsWith("h")) }.filter { it.text.isNotBlank() }
        check(elements.sumOf { it.text.length } >= 100) { "No readable article found. The site may require JavaScript, sign-in, or a subscription." }
        Article(result["title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: source.title().ifBlank { "Saved article" },
            ExtractedPage(elements, 0f, 0f, "Saved article", if (hasTables) listOf("Tables were omitted from speech. See the saved source.") else emptyList()), clean)
    }
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractSandboxed(html: String): JsonObject = withContext(Dispatchers.Main) {
        val view = WebView(context)
        try {
            view.settings.apply {
                javaScriptEnabled = true; allowFileAccess = false; allowContentAccess = false
                blockNetworkLoads = true; blockNetworkImage = true; domStorageEnabled = false
                javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(false)
            }
            val result = CompletableDeferred<JsonObject>()
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?) = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                override fun onPageFinished(view: WebView, url: String) {
                    val library = context.assets.open("vendor/Readability.js").bufferedReader().use { it.readText() }
                    view.evaluateJavascript("$library; JSON.stringify(new Readability(document.cloneNode(true)).parse());") { encoded ->
                        try {
                            val json = Json.parseToJsonElement(encoded).jsonPrimitive.content
                            result.complete(Json.parseToJsonElement(json).jsonObject)
                        } catch (_: Exception) { result.completeExceptionally(IllegalArgumentException("Readability could not extract this article")) }
                    }
                }
            }
            // No JavaScript bridge exists. Only the bundled Readability code is executed.
            view.loadDataWithBaseURL("https://readflow.invalid/", "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'none'\">$html", "text/html", "UTF-8", null)
            withTimeout(15_000) { result.await() }
        } finally { view.stopLoading(); view.destroy() }
    }
}
