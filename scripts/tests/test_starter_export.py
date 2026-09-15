"""Test canonical starter export boundaries with an isolated tracked source tree."""
import ctypes
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]
RUNTIME = 'ARESLib-Kotlin/ares-micro/ares_micro'
FTC_RUNTIME = 'templates/ftc/runtime/src/main/kotlin/org/firstinspires/ftc/teamcode/dsl/FtcGeneratedProjectRuntime.kt'


class StarterExportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pwsh = shutil.which('pwsh')
        if not cls.pwsh or not shutil.which('git'):
            raise unittest.SkipTest('PowerShell 7 and Git are required for starter export')

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='ares-export-audit-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repo = self.root / 'source repository'
        self.repo.mkdir()
        self.output = self.root / 'mirrors'
        self.env = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
        self.env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull,
                        GIT_AUTHOR_NAME='Audit Fixture', GIT_AUTHOR_EMAIL='audit@example.invalid',
                        GIT_COMMITTER_NAME='Audit Fixture', GIT_COMMITTER_EMAIL='audit@example.invalid')
        self.git('init', '--quiet', '--initial-branch=main')
        self.write('release/ares-versions.properties', 'aresVersion=1.2.3\nstudioVersion=4.5.6\ngithubMavenRepository=https://example.invalid/maven\nftcStarterVersion=1.2.3\nfrcStarterVersion=1.2.3\nxrpStarterVersion=1.2.3\nlightbotExampleVersion=1.2.3\nbiobuzzExampleVersion=1.2.3\n')
        self.write('build-logic/ares-versioning.gradle', '// shared version resolution\n')
        self.write(FTC_RUNTIME, '// canonical FTC runtime\n')
        self.write(RUNTIME + '/__init__.py', '# canonical XRP runtime\n')
        self.write(RUNTIME + '/controller.py', 'VALUE = 1\n')
        for template in ('ARES-FTC-Starter', 'ARES-FRC-Starter', 'ARES-XRP-Starter', 'ARES-FTC'):
            self.write(template + '/main.txt', template + '\n')
        self.write('ARES-FTC/biobuzz/main.txt', 'BioBuzz overlay\n')
        self.write('ARES-FTC/biobuzz/shared/src/main/resources/field-presets/ftc/2026-2027-biobuzz.json', '{"id":"biobuzz"}\n')
        self.write('.gitignore', '*.local\n__pycache__/\n')
        self.write('scripts/export-starter-mirrors.ps1', (ROOT / 'scripts/export-starter-mirrors.ps1').read_text())
        self.write('scripts/build-starter-archives.ps1', (ROOT / 'scripts/build-starter-archives.ps1').read_text())
        self.git('add', '.')
        self.git('commit', '--quiet', '-m', 'fixture')

    def write(self, relative, content):
        path = self.repo / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8', newline='\n')
        return path

    def git(self, *args):
        return subprocess.run(['git', '-C', str(self.repo), *args], env=self.env,
                              capture_output=True, check=True, timeout=15).stdout.decode('utf-8').strip()

    def export(self, check=False, output=None, succeeds=True):
        args = [self.pwsh, '-NoProfile', '-NonInteractive', '-File',
                str(self.repo / 'scripts/export-starter-mirrors.ps1'), '-OutputRoot', str(output or self.output)]
        if check:
            args.append('-Check')
        run = subprocess.run(args, cwd=self.root, env=self.env, capture_output=True, text=True, timeout=30)
        self.assertEqual(run.returncode == 0, succeeds, run.stdout + run.stderr)
        return run

    def test_xrp_runtime_excludes_untracked_ignored_and_cache_files(self):
        self.write(RUNTIME + '/debug-capture.txt', 'synthetic local-only data')
        self.write(RUNTIME + '/settings.local', 'synthetic ignored data')
        self.write(RUNTIME + '/__pycache__/controller.pyc', 'synthetic cache')
        self.export()
        runtime = self.output / 'ARES-XRP-Starter/lib/ares_micro'
        self.assertEqual(sorted(p.name for p in runtime.iterdir()), ['__init__.py', 'controller.py'])
        self.export(check=True)
        manifest = json.loads((self.output / 'ARES-XRP-Starter/.ares-starter-mirror.json').read_text(encoding='utf-8-sig'))
        self.assertNotIn('lib/ares_micro/debug-capture.txt', manifest['files'])
        standalone = (self.output / 'ARES-XRP-Starter/release/ares-versions.properties').read_text()
        self.assertIn('aresVersion=1.2.3', standalone)
        self.assertNotIn('studioVersion', standalone)

    def test_tracked_unicode_and_space_paths_survive_git_enumeration(self):
        self.write('ARES-FTC-Starter/configs/caf\u00e9 notes.txt', 'tracked unicode filename')
        self.git('add', '.')
        self.export()
        self.assertEqual((self.output / 'ARES-FTC-Starter/configs/caf\u00e9 notes.txt').read_text(), 'tracked unicode filename')
        self.export(check=True)

    def test_sibling_with_workspace_name_prefix_is_allowed(self):
        self.output = self.root / 'source repository-mirrors'
        self.export()
        self.export(check=True)

    def test_hidden_tracked_file_is_copied_and_checked(self):
        hidden = self.write('ARES-FTC-Starter/.hidden.txt', 'tracked hidden content')
        self.git('add', '.')
        if os.name == 'nt':
            self.assertTrue(ctypes.windll.kernel32.SetFileAttributesW(str(hidden), 2))
        self.export()
        self.assertEqual((self.output / 'ARES-FTC-Starter/.hidden.txt').read_text(), 'tracked hidden content')
        if os.name == 'nt':
            self.assertTrue(ctypes.windll.kernel32.SetFileAttributesW(str(self.output / 'ARES-FTC-Starter/.hidden.txt'), 2))
        self.export(check=True)

    def test_archives_include_canonical_text_binary_and_runtime_with_reproducible_bytes(self):
        self.write('ARES-FTC-Starter/.hidden.txt', 'first\r\nsecond\n')
        binary = self.write('ARES-FTC-Starter/data.bin', '')
        binary.write_bytes(b'\x00\xff\r\n')
        self.write('ARES-FTC-Starter/gradlew', '#!/bin/sh\r\n')
        self.git('add', '.')
        self.write(RUNTIME + '/private.local', 'synthetic ignored data')
        outputs = []
        for name in ('archives-one', 'archives-two'):
            output = self.root / name
            run = subprocess.run([self.pwsh, '-NoProfile', '-NonInteractive', '-File',
                str(self.repo / 'scripts/build-starter-archives.ps1'), '-OutputDirectory', str(output)],
                cwd=self.root, env=self.env, capture_output=True, text=True, timeout=30)
            self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
            outputs.append({p.name: p.read_bytes() for p in output.glob('*.zip')})
        self.assertEqual(len(outputs[0]), 5)
        self.assertEqual(outputs[0], outputs[1])
        with zipfile.ZipFile(self.root / 'archives-one/ARES-FTC-Starter-1.2.3.zip') as archive:
            prefix = 'ARES-FTC-Starter-1.2.3/'
            self.assertEqual(archive.read(prefix + '.hidden.txt'), b'first\nsecond\n')
            self.assertEqual(archive.read(prefix + 'data.bin'), b'\x00\xff\r\n')
            self.assertEqual((archive.getinfo(prefix + 'gradlew').external_attr >> 16) & 0o777, 0o755)
            self.assertTrue(all(i.date_time == (2000, 1, 1, 0, 0, 0) for i in archive.infolist()))
        with zipfile.ZipFile(self.root / 'archives-one/ARES-XRP-Starter-1.2.3.zip') as archive:
            self.assertFalse(any('private.local' in name for name in archive.namelist()))
            self.assertIn('ARES-XRP-Starter-1.2.3/lib/ares_micro/controller.py', archive.namelist())
        with zipfile.ZipFile(self.root / 'archives-one/ARES-BIOBUZZ-Example-1.2.3.zip') as archive:
            prefix = 'ARES-BIOBUZZ-Example-1.2.3/'
            self.assertEqual(archive.read(prefix + 'main.txt'), b'BioBuzz overlay\n')
            self.assertEqual(archive.read(prefix + 'TeamCode/src/main/assets/paths/field.json'), b'{"id":"biobuzz"}\n')
            runtime = 'TeamCode/src/main/java/' + FTC_RUNTIME.removeprefix('templates/ftc/runtime/src/main/kotlin/')
            self.assertEqual(archive.read(prefix + runtime), b'// canonical FTC runtime\n')
        with zipfile.ZipFile(self.root / 'archives-one/ARES-Lightbot-Example-1.2.3.zip') as archive:
            self.assertFalse(any('/biobuzz/' in name for name in archive.namelist()))

    def test_check_rejects_changed_missing_and_added_files(self):
        self.export()
        target = self.output / 'ARES-FTC-Starter/main.txt'
        original = target.read_bytes()
        target.write_text('changed')
        self.export(check=True, succeeds=False)
        target.unlink()
        self.export(check=True, succeeds=False)
        target.write_bytes(original)
        extra = target.parent / 'extra.txt'
        extra.write_text('unexpected')
        self.export(check=True, succeeds=False)
        extra.unlink()
        self.export(check=True)

    def test_workspace_output_and_existing_mirrors_are_rejected(self):
        self.export(output=self.repo, succeeds=False)
        self.export(output=self.repo / 'nested', succeeds=False)
        self.assertFalse((self.repo / 'nested').exists())
        self.export()
        target = self.output / 'ARES-FTC-Starter/main.txt'
        target.write_text('user changes')
        self.export(succeeds=False)
        self.assertEqual(target.read_text(), 'user changes')
