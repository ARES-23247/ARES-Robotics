import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / 'audit_inventory.py'
SPEC = importlib.util.spec_from_file_location('audit_ledger_io', SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def record(digest='0' * 64):
    return {'sha256': digest, 'review': 'reviewed', 'validation': 'passed',
            'scope': 'Full file', 'evidence': ['recorded test']}


class AuditLedgerIoTest(unittest.TestCase):
    def test_ledger_fingerprint_excludes_only_own_hash_and_preserves_input(self):
        key = 'audit.json'
        document = {'schemaVersion': 1, 'files': {key: record(), 'robot.py': record('1' * 64)}}
        saved = copy.deepcopy(document)
        digest = MODULE.ledger_fingerprint(document, key)
        self.assertEqual(document, saved)
        document['files'][key]['sha256'] = '2' * 64
        self.assertEqual(MODULE.ledger_fingerprint(document, key), digest)
        for changed in ('sha256', 'scope', 'review', 'validation', 'evidence'):
            candidate = copy.deepcopy(document)
            candidate['files']['robot.py'][changed] = 'altered'
            self.assertNotEqual(MODULE.ledger_fingerprint(candidate, key), digest, changed)
        for changed in ('scope', 'review', 'validation', 'evidence'):
            candidate = copy.deepcopy(document)
            candidate['files'][key][changed] = 'altered'
            self.assertNotEqual(MODULE.ledger_fingerprint(candidate, key), digest, changed)
        candidate = copy.deepcopy(document)
        candidate['metadata'] = 'also bound'
        self.assertNotEqual(MODULE.ledger_fingerprint(candidate, key), digest)

    def test_reformatting_ledger_does_not_change_its_semantic_fingerprint(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'audit.json'
            document = {'schemaVersion': 1, 'files': {'audit.json': record()}}
            path.write_text(json.dumps(document), encoding='utf-8')
            digest = MODULE.fingerprint(path, ledger_key='audit.json')
            path.write_bytes(json.dumps(document, indent=4, sort_keys=True).replace('\n', '\r\n').encode())
            self.assertEqual(MODULE.fingerprint(path, ledger_key='audit.json'), digest)

    def test_parser_rejects_duplicate_keys_non_json_constants_and_wrong_schema(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'audit.json'
            for contents in ('{"schemaVersion":1,"schemaVersion":1,"files":{}}',
                             '{"schemaVersion":1,"files":{},"extra":NaN}',
                             '{"schemaVersion":true,"files":{}}',
                             '{"schemaVersion":2,"files":{}}',
                             '{"schemaVersion":1,"files":[]}', '[]'):
                with self.subTest(contents=contents):
                    path.write_text(contents, encoding='utf-8')
                    with self.assertRaises(ValueError):
                        MODULE.load_ledger(path)

    def test_invalid_paths_and_record_fields_are_rejected_before_fingerprinting(self):
        for path in ('../escape.py', '/outside.py', '', '.', 'a//b', 'a/./b'):
            with self.subTest(path=path), self.assertRaises(ValueError):
                MODULE.inventory(Path('.'), [path], {})
        for update in ({'sha256': 'bad'}, {'review': 'complete'}, {'validation': 'unknown'}, {'scope': None}):
            with self.subTest(update=update), self.assertRaises(ValueError):
                MODULE.validate_records({'robot.py': {**record(), **update}})

    def test_symlink_fingerprint_uses_link_text_without_reading_target(self):
        path = Path('link')
        with patch.object(Path, 'is_symlink', return_value=True), \
                patch.object(MODULE.os, 'readlink', return_value='../missing-target'), \
                patch.object(Path, 'read_bytes', side_effect=AssertionError('must not read target')):
            self.assertEqual(MODULE.fingerprint(path), hashlib.sha256(os.fsencode('../missing-target')).hexdigest())

    def test_atomic_output_failure_preserves_previous_report_and_removes_owned_temp(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / 'report.json'
            output.write_text('previous report', encoding='utf-8')
            with patch.object(MODULE.os, 'replace', side_effect=OSError('injected replacement failure')):
                with self.assertRaises(OSError):
                    MODULE.write_json(output, {'new': 'report'})
            self.assertEqual(output.read_text(), 'previous report')
            self.assertEqual(list(root.iterdir()), [output])

    def test_custom_ledger_cli_refresh_preserves_review_status_and_rejects_self_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            script = root / 'scripts/audit_inventory.py'
            script.parent.mkdir()
            script.write_bytes(SCRIPT.read_bytes())
            (root / 'nested').mkdir()
            source = root / 'source.py'
            source.write_bytes(b'pass\n')
            ledger = root / 'audit.json'
            document = {'schemaVersion': 1, 'files': {
                'source.py': record(MODULE.fingerprint(source)),
                'audit.json': {**record(), 'review': 'partial'}}}
            ledger.write_text(json.dumps(document), encoding='utf-8')
            def git(*args):
                return subprocess.run(['git', *args], cwd=root, check=True, capture_output=True)
            git('init', '-q')
            git('config', 'user.name', 'Audit Test')
            git('config', 'user.email', 'audit-test@example.invalid')
            git('add', 'source.py', 'audit.json')
            git('commit', '-qm', 'fixture')
            output = root / 'result.json'
            command = [sys.executable, str(script), '--records', 'nested/../audit.json', '--output', str(output)]
            subprocess.run(command + ['--refresh-ledger-fingerprint'], cwd=root, check=True, capture_output=True)
            refreshed = json.loads(ledger.read_text())
            self.assertEqual(refreshed['files']['audit.json']['review'], 'partial')
            self.assertEqual(refreshed['files']['audit.json']['evidence'], document['files']['audit.json']['evidence'])
            result = json.loads(output.read_text())
            self.assertEqual(result['complete'], 1)
            own = next(row for row in result['files'] if row['path'] == 'audit.json')
            self.assertEqual(own['review'], 'partial')
            self.assertEqual(own['fingerprintKind'], 'ledger-json-v1')
            before = ledger.read_bytes()
            collision = subprocess.run([sys.executable, str(script), '--records', str(ledger), '--output', str(ledger)],
                                       cwd=root, capture_output=True)
            self.assertNotEqual(collision.returncode, 0)
            self.assertEqual(ledger.read_bytes(), before)

    def test_refresh_cannot_invent_an_absent_self_review(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            ledger = root / 'audit.json'
            ledger.write_text('{"schemaVersion":1,"files":{}}', encoding='utf-8')
            before = ledger.read_bytes()
            with patch.object(MODULE, 'ROOT', root), \
                    patch.object(sys, 'argv', ['audit_inventory', '--records', str(ledger), '--output', str(root / 'out'),
                                              '--refresh-ledger-fingerprint']), \
                    patch.object(MODULE.subprocess, 'check_output', return_value=b'audit.json\0'):
                with self.assertRaises(ValueError):
                    MODULE.main()
            self.assertEqual(ledger.read_bytes(), before)
