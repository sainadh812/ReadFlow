import app.readflow.core.*;
import app.readflow.ingest.ArticleHtml;
import java.nio.file.*;
import java.util.*;
import org.jsoup.Jsoup;

/** Host normalization probe. Does not claim Android extraction, synthesis, or auditory validation. */
class CheckImportedText {
    public static void main(String[] paths) throws Exception {
        for (String path : paths) {
            String input = Files.readString(Path.of(path));
            List<String> paragraphs = new ArrayList<>();
            boolean html = path.endsWith(".html");
            if (html) {
                var document = Jsoup.parse(ArticleHtml.INSTANCE.sanitize(input));
                var main = document.selectFirst(".document");
                if (main == null) main = document.body();
                for (var element : main.select("h1,h2,h3,h4,p,li,blockquote")) {
                    if (element.children().stream().noneMatch(child -> Set.of("p", "li", "blockquote").contains(child.tagName()))) {
                        paragraphs.add(element.text());
                    }
                }
            } else paragraphs.addAll(Arrays.asList(input.split("\\n\\s*\\n")));
            List<TextElement> elements = new ArrayList<>();
            for (int i = 0; i < paragraphs.size(); i++) elements.add(new TextElement(paragraphs.get(i), List.of(), i, i, false));
            var extracted = new ExtractedPage(elements, 0f, 0f, "Host probe", List.of(), new Transform(1f, 0f, 0f, 1f, 0f, 0f));
            var page = new DocumentPipeline(new GeometricReadingOrder()).process("local-probe", 0, extracted);
            var sentences = new LinkedHashMap<String, List<SourceWord>>();
            for (var word : page.getWords()) sentences.computeIfAbsent(word.getSentenceId(), ignored -> new ArrayList<>()).add(word);
            int prepared = 0;
            var failures = new TreeMap<String, Integer>();
            for (var words : sentences.values()) {
                try {
                    var chunks = new SpeechPlanner(new EnglishNormalizer(), 220).prepare(words, null).iterator();
                    while (chunks.hasNext()) { chunks.next(); prepared++; }
                } catch (IllegalArgumentException error) { failures.merge(error.getMessage(), 1, Integer::sum); }
            }
            System.out.println(Path.of(path).getFileName() + ": words=" + page.getWords().size() + ", preparedChunks=" + prepared + ", blockedSentences=" + failures);
            if (html && !failures.isEmpty()) throw new AssertionError("Webpage normalization still blocked");
        }
    }
}
