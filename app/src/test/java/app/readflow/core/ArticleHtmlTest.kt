package app.readflow.core

import app.readflow.ingest.ArticleHtml
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class ArticleHtmlTest {
    @Test fun headingPermalinksAreRemovedWithoutLosingIdentifierText() {
        val clean = ArticleHtml.sanitize("""<article><h1>Control unit<a class="headerlink" href="#control">&#xf0c1;</a></h1>
            <p>The <code>clk_i</code> signal controls an AXI4 interface.</p><script>bad()</script>
            <iframe src="https://example.com"></iframe></article>""")
        assertFalse(clean.contains('\uf0c1'))
        assertFalse(clean.contains("bad()"))
        assertFalse(clean.contains("<iframe"))
        val elements = Jsoup.parse(clean).select("h1,p").mapIndexed { i, p -> TextElement(p.text(), emptyList(), i, i) }
        val page = DocumentPipeline().process("technical-article", 0, ExtractedPage(elements, 0f, 0f, "Saved article"))
        assertTrue(page.reading.contains("clk_i"))
        assertTrue(SpeechPlanner().prepare(page.words).any { it.speech.text.contains("clk underscore i") })
    }
}
