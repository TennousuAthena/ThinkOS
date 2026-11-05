"""The generic narrowed media option is registered with the existing API table."""
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class PublicMediaContractTest(unittest.TestCase):
    def test_optional_entry_and_probe_fields_share_api_ten(self):
        document = json.loads((ROOT / "shared/schema/plugin/layouts.json").read_text())
        vocabulary = document["x-thinkos-api"]
        self.assertEqual(10, vocabulary["current"])
        self.assertEqual({"public_only": 10}, vocabulary["mediaEntryFields"])
        self.assertEqual({"public_only": 10}, vocabulary["mediaProbeOptions"])


if __name__ == "__main__":
    unittest.main()
