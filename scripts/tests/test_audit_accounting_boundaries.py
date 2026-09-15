import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location('audit_accounting', Path(__file__).resolve().parents[1] / 'audit_inventory.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def record(digest):
    return {'sha256': digest, 'review': 'reviewed', 'validation': 'passed',
            'scope': 'Full configuration review', 'evidence': ['recorded test run']}


class AuditAccountingBoundariesTest(unittest.TestCase):
    def test_blank_scope_and_blank_evidence_never_receive_completion_credit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'source.py'
            source.write_bytes(b'pass\n')
            for update in ({'scope': ' \t\n'}, {'evidence': ['']}, {'evidence': ['valid', '  ']}):
                with self.subTest(update=update):
                    item = {**record(MODULE.fingerprint(source)), **update}
                    result = MODULE.inventory(root, ['source.py'], {'source.py': item})
                    self.assertEqual(result['complete'], 0)

    def test_wrongly_typed_evidence_and_scope_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'source.py'
            source.write_bytes(b'pass\n')
            for update in ({'scope': ['not a string']}, {'evidence': 'not a list'}, {'evidence': [123]}):
                with self.subTest(update=update):
                    item = {**record(MODULE.fingerprint(source)), **update}
                    with self.assertRaises(ValueError):
                        MODULE.inventory(root, ['source.py'], {'source.py': item})

    def test_generator_paths_are_not_consumed_twice_for_orphan_detection(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'source.py'
            source.write_bytes(b'pass\n')
            result = MODULE.inventory(root, iter(['source.py', 'source.py']),
                                      {'source.py': record(MODULE.fingerprint(source))})
            self.assertEqual(result['complete'], 1)
            self.assertEqual(result['orphanedRecords'], [])

    def test_self_record_hash_is_stable_but_binds_every_other_value(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / 'docs/audits/file-reviews.json'
            path.parent.mkdir(parents=True)
            key = path.relative_to(root).as_posix()
            document = {'schemaVersion': 1, 'files': {key: record('0' * 64)}}
            path.write_text(json.dumps(document), encoding='utf-8')
            digest = MODULE.inventory(root, [key], document['files'])['files'][0]['sha256']
            document['files'][key]['sha256'] = digest
            path.write_text(json.dumps(document), encoding='utf-8')
            self.assertEqual(MODULE.inventory(root, [key], document['files'])['complete'], 1)
            document['files'][key]['scope'] = 'Changed review claim'
            path.write_text(json.dumps(document), encoding='utf-8')
            self.assertEqual(MODULE.inventory(root, [key], document['files'])['complete'], 0)
