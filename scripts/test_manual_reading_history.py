"""Run production Room migration/history SQL on host SQLite, without Android mocks.

Usage: python3 scripts/test_manual_reading_history.py --report build/manual-history-check.json
The SQL comes from Database.kt and the old schema from Room's checked-in v1 export.
This verifies database behavior on host SQLite; Android Room compilation/device checks
remain separate.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SOURCE_PATH = ROOT / "app/src/main/java/app/readflow/data/Database.kt"
SCHEMA_PATH = ROOT / "app/schemas/app.readflow.data.ReadFlowDatabase/1.json"
NEW_SCHEMA_PATH = ROOT / "app/schemas/app.readflow.data.ReadFlowDatabase/2.json"
SOURCE = SOURCE_PATH.read_text()
OLD_SCHEMA = json.loads(SCHEMA_PATH.read_text())["database"]


def production_sql(name):
    match = re.search(
        rf'\binternal const val {re.escape(name)} = (?:"""(.*?)"""|"([^"\n]*)")',
        SOURCE,
        re.DOTALL,
    )
    if not match:
        raise AssertionError(f"Missing production SQL constant: {name}")
    return match.group(1) if match.group(1) is not None else match.group(2)


CREATE = production_sql("CREATE_MANUAL_HISTORY")
OPEN = production_sql("RECORD_MANUAL_OPEN")
VIEW = production_sql("RECORD_MANUAL_VIEW")
HISTORY = production_sql("READING_HISTORY_QUERY")


class ManualReadingHistorySqlTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "readflow.db"
        self.db = self.connect()
        self.addCleanup(lambda: self.db.close())
        for entity in OLD_SCHEMA["entities"]:
            self.db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
            for index in entity.get("indices", []):
                self.db.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        for identifier, timestamp in (("a", 10), ("b", 20)):
            self.db.execute(
                "INSERT INTO documents VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (identifier, f"hash-{identifier}", "fixture", identifier.upper(),
                 f"/fixture/{identifier}.pdf", "application/pdf", 30, "Ready", "fixture-v1", timestamp),
            )
            self.db.execute("INSERT INTO positions VALUES (?, ?, ?, ?)",
                            (identifier, 2, f"{identifier}-word-4", timestamp))
            self.db.execute("INSERT INTO pages VALUES (?, ?, ?, ?, ?)",
                            (identifier, 2, "Existing imported text", "Ready", 0))
            self.db.execute("INSERT INTO bookmarks VALUES (?, ?, ?, ?)",
                            (identifier, f"{identifier}-bookmark-word", 2, "Saved bookmark"))
        self.db.execute("INSERT INTO chunks VALUES (?, ?, ?, ?, ?)", ("chunk-a", "a", "{}", 1234, 9))
        self.db.execute("INSERT INTO timings VALUES (?, ?, ?, ?, ?, ?)",
                        ("chunk-a", "a-word-4", 0, 40, "fixture", 1.0))
        self.db.execute("PRAGMA user_version = 1")
        self.db.commit()
        self.old_rows = self.original_rows()

    def connect(self):
        database = sqlite3.connect(self.path)
        database.execute("PRAGMA foreign_keys = ON")
        return database

    def original_rows(self):
        return {
            entity["tableName"]: self.db.execute(
                f'SELECT * FROM "{entity["tableName"]}" ORDER BY rowid'
            ).fetchall()
            for entity in OLD_SCHEMA["entities"]
        }

    def migrate(self):
        # Assert this is still the migration body registered in production.
        self.assertRegex(SOURCE, r"object\s*:\s*Migration\(1,\s*2\)")
        self.assertRegex(SOURCE, r"db\.execSQL\(CREATE_MANUAL_HISTORY\)")
        self.assertIn(".addMigrations(MIGRATION_1_2)", SOURCE)
        with self.db:
            self.db.execute(CREATE)
            self.db.execute("PRAGMA user_version = 2")

    def write(self, sql, identifier, page, timestamp):
        self.db.execute(sql, {"id": identifier, "page": page, "time": timestamp})

    def manual(self, identifier):
        return self.db.execute(
            "SELECT documentId, page, lastOpenedAt, lastViewedAt FROM manual_reading_history WHERE documentId=?",
            (identifier,),
        ).fetchone()

    def test_migration_preserves_every_existing_table_and_audio_word(self):
        self.migrate()
        self.assertEqual(self.old_rows, self.original_rows())
        self.assertEqual([], self.db.execute("SELECT * FROM manual_reading_history").fetchall())
        self.assertEqual([(2,)], self.db.execute("PRAGMA user_version").fetchall())
        self.assertEqual([], self.db.execute("PRAGMA foreign_key_check").fetchall())

    def test_migrated_schema_has_required_columns_primary_key_and_cascade(self):
        self.migrate()
        columns = self.db.execute("PRAGMA table_info(manual_reading_history)").fetchall()
        self.assertEqual(
            [("documentId", "TEXT", 1, 1), ("page", "INTEGER", 1, 0),
             ("lastOpenedAt", "INTEGER", 1, 0), ("lastViewedAt", "INTEGER", 1, 0)],
            [(row[1], row[2], row[3], row[5]) for row in columns],
        )
        foreign_key = self.db.execute("PRAGMA foreign_key_list(manual_reading_history)").fetchall()
        self.assertEqual([(0, 0, "documents", "documentId", "id", "NO ACTION", "CASCADE", "NONE")], foreign_key)

    def test_migration_matches_room_generated_version_two_schema(self):
        self.migrate()
        schema = json.loads(NEW_SCHEMA_PATH.read_text())["database"]
        self.assertEqual(2, schema["version"])
        expected = sqlite3.connect(":memory:")
        self.addCleanup(expected.close)
        for entity in schema["entities"]:
            table = entity["tableName"]
            expected.execute(entity["createSql"].replace("${TABLE_NAME}", table))
            for index in entity.get("indices", []):
                expected.execute(index["createSql"].replace("${TABLE_NAME}", table))
            for pragma in ("table_info", "foreign_key_list", "index_list"):
                with self.subTest(table=table, pragma=pragma):
                    self.assertEqual(
                        expected.execute(f'PRAGMA {pragma}("{table}")').fetchall(),
                        self.db.execute(f'PRAGMA {pragma}("{table}")').fetchall(),
                    )

    def test_legacy_audio_is_resume_fallback_until_manual_history_exists(self):
        self.migrate()
        self.assertEqual([("b", 2, 20, 20), ("a", 2, 10, 10)], self.db.execute(HISTORY).fetchall())
        self.write(OPEN, "a", 7, 100)
        self.assertEqual([("a", 7, 100, 100), ("b", 2, 20, 20)], self.db.execute(HISTORY).fetchall())
        self.assertEqual(self.old_rows["positions"], self.original_rows()["positions"])

    def test_manual_page_changes_preserve_last_open_and_independent_narration(self):
        self.migrate()
        self.write(OPEN, "a", 7, 100)
        self.write(VIEW, "a", 8, 200)
        self.assertEqual(("a", 8, 100, 200), self.manual("a"))
        self.assertEqual(self.old_rows, self.original_rows())
        self.db.execute("UPDATE positions SET page=14, wordId='next-audio-word', updatedAt=300 WHERE documentId='a'")
        self.assertEqual(("a", 8, 100, 200), self.db.execute(HISTORY).fetchone())

    def test_delayed_writes_cannot_regress_saved_page_or_timestamps(self):
        self.migrate()
        self.write(OPEN, "a", 7, 100)
        self.write(VIEW, "a", 8, 200)
        self.write(VIEW, "a", 3, 150)
        self.write(OPEN, "a", 1, 50)
        self.assertEqual(("a", 8, 100, 200), self.manual("a"))
        self.write(OPEN, "a", 9, 300)
        self.assertEqual(("a", 9, 300, 300), self.manual("a"))

    def test_viewing_old_document_does_not_reorder_last_opened_document(self):
        self.migrate()
        self.write(OPEN, "a", 7, 100)
        self.write(OPEN, "b", 4, 150)
        self.write(VIEW, "a", 8, 1000)
        self.assertEqual([("b", 4, 150, 150), ("a", 8, 100, 1000)], self.db.execute(HISTORY).fetchall())

    def test_missing_and_deleted_documents_cannot_leave_orphan_history(self):
        self.migrate()
        self.write(OPEN, "missing", 4, 100)
        self.write(VIEW, "missing", 4, 101)
        self.assertIsNone(self.manual("missing"))
        self.write(OPEN, "a", 7, 100)
        self.write(OPEN, "b", 4, 150)
        self.db.execute("DELETE FROM documents WHERE id='a'")
        self.write(VIEW, "a", 8, 200)
        self.assertIsNone(self.manual("a"))
        self.assertEqual(("b", 4, 150, 150), self.manual("b"))
        self.assertEqual([("b", 2, "b-word-4", 20)], self.db.execute("SELECT * FROM positions").fetchall())
        self.assertEqual([], self.db.execute("PRAGMA foreign_key_check").fetchall())

    def test_committed_history_survives_reopen_and_failed_transaction_rolls_back(self):
        self.migrate()
        with self.db:
            self.write(OPEN, "a", 7, 100)
            self.write(VIEW, "a", 8, 200)
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db:
                self.write(VIEW, "a", 9, 300)
                self.db.execute("INSERT INTO manual_reading_history VALUES ('missing', 1, 1, 1)")
        self.db.close()
        self.db = self.connect()
        self.assertEqual(("a", 8, 100, 200), self.manual("a"))
        self.assertEqual(self.old_rows, self.original_rows())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path)
    arguments = parser.parse_args()
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(ManualReadingHistorySqlTest)
    names = [case.id().rsplit(".", 1)[-1] for case in suite]
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    report = {
        "result": "pass" if result.wasSuccessful() else "fail",
        "environment": "host SQLite; production SQL with exported Room v1 schema, not an Android device test",
        "sqliteVersion": sqlite3.sqlite_version,
        "sourceSha256": hashlib.sha256(SOURCE_PATH.read_bytes()).hexdigest(),
        "schemaV1Sha256": hashlib.sha256(SCHEMA_PATH.read_bytes()).hexdigest(),
        "schemaV2Sha256": hashlib.sha256(NEW_SCHEMA_PATH.read_bytes()).hexdigest(),
        "testsRun": result.testsRun,
        "checks": names,
        "failures": [{"test": case.id(), "details": details} for case, details in result.failures + result.errors],
    }
    if arguments.report:
        arguments.report.parent.mkdir(parents=True, exist_ok=True)
        arguments.report.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
