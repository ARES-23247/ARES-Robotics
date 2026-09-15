import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'verify-doc-links.ps1'


class DocLinksTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.shells = [p for name in ('pwsh', 'powershell') if (p := shutil.which(name))]
        if not cls.shells or not shutil.which('git'):
            raise unittest.SkipTest('Git and PowerShell are required')

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='ares-doc-links-audit-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'scripts').mkdir()
        shutil.copy2(SCRIPT, self.root / 'scripts/verify-doc-links.ps1')
        self.env = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
        self.env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull)
        self.git('init', '--quiet')

    def git(self, *args):
        subprocess.run(['git', '-C', str(self.root), *args], env=self.env,
                       capture_output=True, check=True, timeout=15)

    def document(self, content, name='README.md'):
        p = self.root / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content, encoding='utf-8', newline='\n')
        self.git('add', name)

    def verify(self, succeeds):
        for shell in self.shells:
            with self.subTest(shell=shell):
                run = subprocess.run([shell, '-NoProfile', '-NonInteractive', '-File',
                    str(self.root / 'scripts/verify-doc-links.ps1')], cwd=self.root.parent,
                    env=self.env, capture_output=True, text=True, timeout=20)
                self.assertEqual(run.returncode == 0, succeeds, run.stdout + run.stderr)

    def test_shorter_or_mismatched_fences_do_not_expose_code_examples(self):
        self.document('````markdown\n```\n[example](missing-example.md)\n~~~\n````\n')
        self.verify(True)

    def test_real_link_after_long_fence_is_not_silently_skipped(self):
        self.document('````markdown\n```\n````\n[broken](missing-real.md)\n')
        self.verify(False)

    def test_fence_with_trailing_text_is_not_a_closing_fence(self):
        self.document('~~~\n~~~still code\n[example](missing-example.md)\n~~~\n')
        self.verify(True)

    def test_angle_destination_with_title_is_actually_checked(self):
        self.document('[broken](<missing file.md> "descriptive title")\n')
        self.verify(False)

    def test_existing_angle_destination_with_title_and_encoded_fragment_passes(self):
        self.document('# Target\n', 'folder/target file.md')
        self.document('[valid](<folder/target%20file.md#target> "descriptive title")\n')
        self.verify(True)

    def test_unicode_document_and_target_paths_are_read_as_utf8(self):
        self.document('# Destination\n', 'docs/caf\u00e9.md')
        self.document('[valid](caf\u00e9.md)\n', 'docs/r\u00e9sum\u00e9.md')
        self.verify(True)

    def test_historical_exclusions_and_inline_code_do_not_hide_current_errors(self):
        self.document('[old](missing.md)\n', '.planning/history.md')
        self.document('`[example](missing.md)`\n[remote](https://example.invalid)\n')
        self.verify(True)
        self.document('[current](missing-current.md)\n')
        self.verify(False)

    def test_machine_local_file_urls_are_rejected(self):
        self.document('[local](file:///C:/temporary/file.md)\n')
        self.verify(False)
