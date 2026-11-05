import contextlib
import copy
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('thinkos_update_contracts', ROOT / 'shared/gen/update_contracts.py')
contracts = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(contracts)


class UpdateContractsTest(unittest.TestCase):
    def test_both_languages_are_reproducible_from_the_same_schemas(self):
        paths = ('android/app/src/main/java/moe/kiwi/thinkos/update/UpdateModels.kt', 'server/internal/protocol/update_generated.go')
        with tempfile.TemporaryDirectory(prefix='thinkos-update-generation-') as name:
            target = Path(name)
            with patch.object(contracts, 'ROOT', target), contextlib.redirect_stdout(io.StringIO()):
                contracts.generate_kotlin()
                contracts.generate_go()
            for relative in paths:
                self.assertEqual((ROOT / relative).read_bytes(), (target / relative).read_bytes(), relative)

    def test_generator_refuses_unsupported_assertions_instead_of_ignoring_them(self):
        documents = contracts.documents()
        variants = []
        for mutation in (
            lambda item: item.update({'oneOf': []}),
            lambda item: item.update({'additionalProperties': True}),
            lambda item: item.update({'format': 'unrecognized'}),
        ):
            value = copy.deepcopy(documents)
            mutation(value['catalog.json'])
            variants.append(value)
        for value in variants:
            with tempfile.TemporaryDirectory(prefix='thinkos-update-schema-') as name:
                folder = Path(name)
                import json
                for key, schema in value.items():
                    (folder / key).write_text(json.dumps(schema))
                with patch.object(contracts, 'SCHEMAS', folder), self.assertRaises(ValueError):
                    contracts.documents()

    def test_sequence_and_time_fields_keep_int64_and_optional_channels(self):
        with tempfile.TemporaryDirectory(prefix='thinkos-update-int64-') as name:
            target = Path(name)
            with patch.object(contracts, 'ROOT', target), contextlib.redirect_stdout(io.StringIO()):
                contracts.generate_go()
            text = (target / 'server/internal/protocol/update_generated.go').read_text()
            self.assertIn('Seq int64', text)
            self.assertIn('Published int64', text)
            self.assertIn('Stable *UpdateSequence', text)
            self.assertIn('Beta *UpdateSequence', text)
