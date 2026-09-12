"""Validate migration reports against real temporary repositories, without migrating files."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'prepare-clean-monorepo-checkout.ps1'


class MigrationInventoryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.shells = [p for name in ('pwsh', 'powershell') if (p := shutil.which(name))]
        if not cls.shells or not shutil.which('git'):
            raise unittest.SkipTest('PowerShell and Git are required')

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='ares-migration-audit-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.legacy = self.root / 'legacy workspace'
        self.legacy.mkdir()
        self.destination = self.root / 'proposed checkout'
        self.report = self.root / 'inventory.txt'
        self.env = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
        self.env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull,
                        GIT_AUTHOR_NAME='Audit Fixture', GIT_AUTHOR_EMAIL='audit@example.invalid',
                        GIT_COMMITTER_NAME='Audit Fixture', GIT_COMMITTER_EMAIL='audit@example.invalid')

    def git(self, directory, *args):
        return subprocess.run(['git', '-C', str(directory), *args], env=self.env,
                              capture_output=True, text=True, check=True, timeout=15).stdout.strip()

    def repository(self, directory, committed=True):
        directory.mkdir(exist_ok=True)
        self.git(directory, 'init', '--quiet', '--initial-branch=main')
        if committed:
            (directory / 'tracked.txt').write_text('original\n')
            self.git(directory, 'add', 'tracked.txt')
            self.git(directory, 'commit', '--quiet', '-m', 'fixture')
        return directory

    def run_script(self, shell, destination=None):
        return subprocess.run([shell, '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
            '-File', str(SCRIPT), '-LegacyWorkspace', str(self.legacy),
            '-Destination', str(destination or self.destination), '-OutputReport', str(self.report)],
            cwd=self.root, env=self.env, capture_output=True, text=True, timeout=30)

    def test_root_and_child_reports_list_all_untracked_files_without_modifying_repositories(self):
        self.repository(self.legacy)
        child = self.repository(self.legacy / 'child project')
        (self.legacy / 'tracked.txt').write_text('modified\n')
        (child / 'new directory').mkdir()
        for name in ('one.txt', 'two.txt'):
            (child / 'new directory' / name).write_text(name)
        before = [(p, p.read_bytes()) for d in (self.legacy, child) for p in (d / '.git/HEAD', d / '.git/index')]
        for shell in self.shells:
            with self.subTest(shell=shell):
                run = self.run_script(shell)
                self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
                report = self.report.read_text(encoding='utf-8-sig')
                self.assertEqual(report.count('Repository: '), 2)
                self.assertIn('Branch: main', report)
                self.assertIn('new directory/one.txt', report)
                self.assertIn('new directory/two.txt', report)
                self.assertIn(self.git(child, 'rev-parse', 'HEAD'), report)
                self.assertFalse(self.destination.exists())
                for path, contents in before:
                    self.assertEqual(path.read_bytes(), contents, str(path))
                self.assertEqual((self.legacy / 'tracked.txt').read_text(), 'modified\n')

    def test_detached_linked_worktree_is_reported(self):
        origin = self.repository(self.root / 'origin')
        linked = self.legacy / 'detached checkout'
        self.git(origin, 'worktree', 'add', '--detach', str(linked), 'HEAD')
        for shell in self.shells:
            with self.subTest(shell=shell):
                run = self.run_script(shell)
                self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
                report = self.report.read_text(encoding='utf-8-sig')
                self.assertIn('Branch: (detached)', report)
                self.assertIn(self.git(linked, 'rev-parse', 'HEAD'), report)

    def test_unborn_branch_has_explicit_head_state(self):
        self.repository(self.legacy, committed=False)
        (self.legacy / 'new.txt').write_text('uncommitted')
        for shell in self.shells:
            with self.subTest(shell=shell):
                run = self.run_script(shell)
                self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
                report = self.report.read_text(encoding='utf-8-sig')
                self.assertIn('HEAD: (initial)', report)
                self.assertIn('Branch: main', report)
                self.assertIn('new.txt', report)

    def test_git_failure_cannot_overwrite_a_previous_report_with_a_clean_inventory(self):
        self.repository(self.legacy)
        (self.legacy / '.git/index').write_bytes(b'corrupt index')
        for shell in self.shells:
            with self.subTest(shell=shell):
                self.report.write_text('previous verified report')
                run = self.run_script(shell)
                self.assertNotEqual(run.returncode, 0, run.stdout + run.stderr)
                self.assertEqual(self.report.read_text(), 'previous verified report')

    def test_trailing_separator_cannot_disguise_the_same_destination(self):
        for shell in self.shells:
            with self.subTest(shell=shell):
                self.report.write_text('previous verified report')
                run = self.run_script(shell, str(self.legacy) + os.sep)
                self.assertNotEqual(run.returncode, 0, run.stdout + run.stderr)
                self.assertEqual(self.report.read_text(), 'previous verified report')

    def test_folder_without_git_is_reported_without_creating_a_checkout(self):
        for shell in self.shells:
            with self.subTest(shell=shell):
                run = self.run_script(shell)
                self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
                report = self.report.read_text(encoding='utf-8-sig')
                self.assertNotIn('Repository: ', report)
                self.assertFalse(self.destination.exists())
