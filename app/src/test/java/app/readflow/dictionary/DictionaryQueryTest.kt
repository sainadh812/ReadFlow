package app.readflow.dictionary

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class DictionaryQueryTest {
    @Test fun quotesAndPagePunctuationDoNotPreventLookup() {
        assertEquals(DictionaryQuery("can't", "can't"), dictionaryQuery("  “can’t,”  "))
        assertEquals(DictionaryQuery("well-being", "well-being"), dictionaryQuery("(well–being)."))
        assertEquals("good", dictionaryQuery("good₹%&").key)
        assertEquals("good", dictionaryQuery("good*").key)
        assertEquals("", dictionaryQuery("“₹%&—*”").key)
    }

    @Test fun unicodeFormsNormalizeWithoutDestroyingAccentsOrInternalPunctuation() {
        assertEquals("café", dictionaryQuery("cafe\u0301").key)
        assertEquals("flower", dictionaryQuery("ﬂower").key)
        assertEquals("mother-in-law's", dictionaryQuery("mother‐in‐law’s").key)
        assertEquals("rock 'n' roll", dictionaryQuery("rock   'n'  roll").key)
        assertEquals("a few", dictionaryQuery("a\u0085\u2028\u2029few").key)
    }

    @Test fun caseSensitiveSpellingSurvivesAndKeysIgnorePhoneLocale() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(DictionaryQuery("Polish", "polish"), dictionaryQuery("Polish"))
            assertEquals(DictionaryQuery("INDIGO", "indigo"), dictionaryQuery("INDIGO"))
        } finally { Locale.setDefault(originalLocale) }
    }

    @Test fun inflectionsAreNotGuessedByDestructiveSuffixStripping() {
        for (word in listOf("children", "went", "running", "business", "glass", "news")) assertEquals(word, dictionaryQuery(word).key)
    }

    @Test fun excessiveQueriesAreRejectedInsteadOfLookingUpATruncatedWord() {
        for (query in listOf("a".repeat(257), "ﬄ".repeat(100))) {
            try { dictionaryQuery(query); fail("Oversized query accepted") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun spellingSuggestionsAreBoundedDistinctAndNeverUnrelatedDefinitions() {
        assertEquals(listOf("resilient"), rankDictionarySuggestions("resilent", listOf("resilient", "resilience", "resilient", "resistor", "banana")))
        assertEquals(listOf("read", "reader", "reading"), rankDictionarySuggestions("rea", listOf("reading", "reader", "read", "banana")))
        assertEquals(2, rankDictionarySuggestions("read", listOf("reader", "reading", "readable", "read"), limit = 2).size)
        assertEquals(listOf("reader"), rankDictionarySuggestions("read", listOf("r".repeat(257), "ﬄ".repeat(100), "reader")))
    }

    @Test fun manyNounSensesCannotHideTheVerbBehindAnInflectedWord() {
        val exact = listOf(entry(1, "running", "adjective"), entry(2, "running", "noun"))
        val runNouns = (3L..80L).map { entry(it, "run", "noun") }
        val runVerbs = (81L..100L).map { entry(it, "run", "verb") }
        val selected = selectDictionarySenses(exact + runNouns + runVerbs)
        assertEquals(32, selected.size)
        assertEquals(exact.first(), selected.first())
        assertEquals(listOf("adjective", "noun", "verb"), selected.take(3).map { it.pos })
        assertEquals(81L, selected.first { it.pos == "verb" }.id)
        assertTrue(selected.any { it.headword == "run" && it.pos == "noun" })
        assertEquals(selected.size, selected.distinctBy { it.id }.size)
        assertTrue(selected.filter { it.pos == "verb" }.zipWithNext().all { (a, b) -> a.id < b.id })
    }

    @Test fun senseSelectionPreservesPrioritizedCaseAndBoundsResultsEvenWithDuplicateForms() {
        val capital = entry(20, "Polish", "adjective")
        val lower = entry(1, "polish", "noun")
        val selected = selectDictionarySenses(listOf(capital, lower, capital, entry(2, "polish", "verb")), 2)
        assertEquals(listOf(capital, lower), selected)
        assertTrue(selectDictionarySenses(listOf(capital), 0).isEmpty())
    }

    private fun entry(id: Long, headword: String, pos: String) = DictionaryEntry(id, headword, pos, "Meaning $id", "", "", "", "")
}
