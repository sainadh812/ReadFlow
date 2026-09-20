import hashlib
import json
import unittest
from read_github_diagnostics import parse_comments


def comment(entry):
    payload = json.dumps(entry)
    return {"body": "<!-- readflow-batch:" + hashlib.sha256(payload.encode()).hexdigest() + " -->\n2 events\n```jsonl\n" + payload + "\n```"}


class DiagnosticReaderTest(unittest.TestCase):
    def test_deduplicates_and_prefers_explicit_private_details(self):
        basic = {"eventIds": ["a", "b"], "stage": "PARSE", "inputIncluded": False}
        detailed = dict(basic, inputIncluded=True, input={"originalText": "$(not a command)"})
        events = parse_comments([comment(basic), comment(detailed), comment(basic)])
        self.assertEqual(set(events), {"a", "b"})
        self.assertEqual(events["a"]["input"]["originalText"], "$(not a command)")

    def test_tampered_or_unrelated_comments_are_ignored(self):
        valid = comment({"eventIds": ["a"]})
        self.assertEqual(parse_comments([{"body": "hello"}, {"body": valid["body"].replace('["a"]', '["b"]')}]), {})


if __name__ == "__main__":
    unittest.main()
