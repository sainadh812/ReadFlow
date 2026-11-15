#!/usr/bin/env python3
"""Importer contract tests; fixtures are authored here, not dictionary data."""
import collections
import importlib.util
from pathlib import Path
import sqlite3
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("dictionary_build", Path(__file__).with_name("build.py"))
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


class DictionaryImporterTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.executescript(build.SCHEMA)
        self.stats = collections.Counter()

    def tearDown(self):
        self.db.close()

    def record(self, word, gloss, **extra):
        value = {"word": word, "lang_code": "en", "pos": "noun", "senses": [{"glosses": [gloss]}]}
        value.update(extra)
        build.add_record(self.db, value, self.stats)

    def test_normalization_matches_reader_words_without_breaking_interior(self):
        self.assertEqual("well-being", build.normalize("“Ｗｅｌｌ‑being!”"))
        self.assertEqual("can't", build.normalize(" (CAN’T) "))
        self.assertEqual("reader's", build.normalize("readerʼs₹%&"))
        self.assertEqual("café", build.normalize("cafe\u0301"))
        self.assertEqual("", build.normalize("₹%&"))
        self.assertEqual("well being", build.normalize("  well\t\nbeing  "))

    def test_contraction_reference_retains_negation_and_does_not_alias_to_positive(self):
        self.record("is", "Exists.", forms=[{"form": "isn't", "tags": ["negative", "contraction"]}])
        self.record("isn't", "", senses=[{"glosses": ["Contraction of is + not (negative auxiliary)."],
                                          "tags": ["abbreviation", "alt-of", "contraction"],
                                          "alt_of": [{"word": "is", "extra": "+ not (negative auxiliary)"}]}])
        build.resolve_forms(self.db, self.stats)
        self.assertEqual([], self.db.execute("SELECT * FROM forms WHERE lookup=?", ("isn't",)).fetchall())
        self.assertEqual("Contraction of is + not (negative auxiliary).",
                         self.db.execute("SELECT definition FROM entries WHERE lookup=?", ("isn't",)).fetchone()[0])

    def test_non_pure_reference_preserves_additional_source_meaning(self):
        self.record("sourceword", "An original meaning.")
        self.record("variant", "", senses=[{"glosses": ["An additional meaning.", "Alternative form of sourceword."],
                                            "alt_of": [{"word": "sourceword"}]}])
        self.assertEqual("An additional meaning. Alternative form of sourceword.",
                         self.db.execute("SELECT definition FROM entries WHERE headword='variant'").fetchone()[0])

    def test_quotes_citations_and_unknown_examples_are_excluded(self):
        sense = {"examples": [
            {"type": "quotation", "text": "Quoted text"},
            {"type": "example", "ref": "A book", "text": "Misclassified quotation"},
            {"text": "Unclassified text"},
            {"type": "example", "text": "A source-authored example."},
            {"type": "example", "text": "Another eligible example."},
        ]}
        self.assertEqual("A source-authored example.", build.selected_example(sense, self.stats))
        self.assertEqual(3, self.stats["excluded_examples"])
        self.assertEqual(2, self.stats["eligible_authored_examples"])

    def test_forms_and_substantive_exact_senses_survive_together(self):
        self.record("run", "Move quickly.", forms=[{"form": "running", "tags": ["participle"]}])
        self.record("running", "The activity of running.", senses=[
            {"glosses": ["The activity of running."]},
            {"glosses": ["Participle of run."], "form_of": [{"word": "run"}]},
        ])
        build.resolve_forms(self.db, self.stats)
        self.assertEqual([("running", "run")], self.db.execute("SELECT * FROM forms").fetchall())
        self.assertEqual([("run",), ("running",)], self.db.execute("SELECT headword FROM entries ORDER BY headword").fetchall())
        self.assertEqual(2, self.stats["entries"])

    def test_explicit_irregular_forms_and_bounded_alias_chain(self):
        self.record("child", "A young person.", forms=[{"form": "children"}])
        self.record("go", "Move somewhere.")
        self.record("went", "Past of go.", senses=[{"form_of": [{"word": "go"}], "glosses": ["Past of go."]}])
        self.record("wente", "Variant of went.", senses=[{"alt_of": [{"word": "went"}], "glosses": ["Variant of went."]}])
        build.resolve_forms(self.db, self.stats)
        self.assertEqual([("children", "child"), ("went", "go"), ("wente", "go")],
                         self.db.execute("SELECT * FROM forms ORDER BY lookup").fetchall())

    def test_cyclic_or_missing_references_do_not_invent_definitions(self):
        self.record("a-cycle", "", senses=[{"form_of": [{"word": "b-cycle"}]}])
        self.record("b-cycle", "", senses=[{"form_of": [{"word": "a-cycle"}]}])
        self.record("missing-form", "", senses=[{"form_of": [{"word": "absent"}]}])
        build.resolve_forms(self.db, self.stats)
        self.assertEqual([], self.db.execute("SELECT * FROM forms").fetchall())
        self.assertEqual(3, self.stats["unresolved_form_lookups"])

    def test_case_distinct_headwords_and_source_links_are_retained(self):
        self.record("Polish", "Related to Poland.")
        self.record("polish", "Make smooth.")
        self.record("can't", "Cannot.")
        rows = self.db.execute("SELECT headword,lookup,source_url FROM entries ORDER BY id").fetchall()
        self.assertEqual("polish", rows[0][1])
        self.assertEqual("polish", rows[1][1])
        self.assertIn("/Polish#English", rows[0][2])
        self.assertIn("/can%27t#English", rows[2][2])

    def test_malformed_optional_fields_and_non_english_records_are_safe(self):
        self.record("foreign", "Do not include.", lang_code="fr")
        self.record("broken", "", forms="not a list", sounds=[None, {"ipa": 4}], senses=[None, {"glosses": [None, 4]}, {"glosses": ["Valid sense."], "examples": 3}])
        self.assertEqual([("broken", "Valid sense.")], self.db.execute("SELECT headword,definition FROM entries").fetchall())

    def test_overlong_entries_and_forms_do_not_reach_android(self):
        self.record("x" * 257, "Too long.")
        self.record("normal", "y" * 8193, forms=[{"form": "z" * 257}])
        self.assertEqual(0, self.db.execute("SELECT count(*) FROM entries").fetchone()[0])
        self.assertEqual(0, self.db.execute("SELECT count(*) FROM raw_links").fetchone()[0])
        self.assertEqual(1, self.stats["overlong_senses"])

    def test_changed_upstream_source_is_rejected_before_publication(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "source.jsonl"
            path.write_text('{"word":"word","lang_code":"en"}\n')
            with self.assertRaisesRegex(ValueError, "pinned SHA-256"):
                build.build(path, Path(temporary) / "out", {"sha256": "0" * 64, "bytes": path.stat().st_size}, "https://example.invalid/pack")

    def test_invalid_english_json_fails_instead_of_publishing_partial_pack(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "source.jsonl"
            path.write_text('{"lang_code":"en",BROKEN}\n')
            with self.assertRaisesRegex(ValueError, "line 1"):
                build.build(path, Path(temporary) / "out", {"sha256": build.sha256(path), "bytes": path.stat().st_size}, "https://example.invalid/pack")
            self.assertFalse((Path(temporary) / "out" / "manifest.json").exists())


if __name__ == "__main__":
    unittest.main()
