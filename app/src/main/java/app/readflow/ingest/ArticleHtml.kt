package app.readflow.ingest

import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

object ArticleHtml {
    fun sanitize(html: String): String {
        val source = Jsoup.parse(html)
        source.select("script,style,iframe,object,embed,form,link,meta,svg,math,noscript,.headerlink,.viewcode-link").remove()
        return Jsoup.clean(source.outerHtml(), Safelist().addTags("html", "head", "title", "body", "article", "main", "section", "div", "p", "h1", "h2", "h3", "h4", "blockquote", "ul", "ol", "li", "strong", "em", "b", "i", "br", "table", "thead", "tbody", "tr", "td", "th").addAttributes("*", "class", "id"))
    }
}
