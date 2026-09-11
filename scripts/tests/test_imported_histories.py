"""Exercise the real shell verifiers against disposable Git object graphs."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
PS = ROOT / 'scripts/verify-imported-histories.ps1'
SH = ROOT / 'scripts/verify-imported-histories.sh'


def shells():
    result = []
    for name in ('pwsh', 'powershell'):
        executable = shutil.which(name)
        if executable:
            result.append((name, executable, 'ps1'))
    # Windows' system32/bash.exe is a WSL launcher, not the Git Bash used by CI.
    git = shutil.which('git')
    bash = Path(git).resolve().parents[1] / 'bin/bash.exe' if os.name == 'nt' and git else None
    executable = str(bash) if bash and bash.is_file() else (shutil.which('bash') if os.name != 'nt' else None)
    if executable:
        result.append(('bash', executable, 'sh'))
    return result


class ImportedHistoriesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.shells = shells()
        if not cls.shells or not shutil.which('git'):
            raise unittest.SkipTest('Git and a supported PowerShell or Bash interpreter are required')
        cls.ps_records = re.findall(r"Path = '([^']+)'; Import = '([0-9a-f]{40})'; Source = '([0-9a-f]{40})'", PS.read_text())
        cls.sh_records = re.findall(r"'([^'|]+)\|([0-9a-f]{40})\|([0-9a-f]{40})'", SH.read_text())

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='ares-history-audit-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / 'repository with spaces'
        self.root.mkdir()
        self.env = os.environ.copy()
        # Do not inherit a caller's repository, index, object store or identity settings.
        for key in list(self.env):
            if key.startswith('GIT_'):
                del self.env[key]
        self.env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull,
                        GIT_AUTHOR_NAME='Audit Fixture', GIT_AUTHOR_EMAIL='audit@example.invalid',
                        GIT_COMMITTER_NAME='Audit Fixture', GIT_COMMITTER_EMAIL='audit@example.invalid',
                        GIT_AUTHOR_DATE='2000-01-01T00:00:00Z', GIT_COMMITTER_DATE='2000-01-01T00:00:00Z')
        self.git('init', '--quiet', '--initial-branch=main')
        self.empty = self.git('mktree', input='')
        self.base = self.commit(self.empty, [], 'base')

    def git(self, *args, input=None):
        # Binary pipes keep Windows newline translation from adding CR to mktree names.
        data = input.encode('utf-8') if input is not None else None
        return subprocess.run(['git', '-C', str(self.root), *args], input=data,
                              capture_output=True, env=self.env, check=True, timeout=15).stdout.decode('utf-8').strip()

    def commit(self, tree, parents, message):
        args = ['commit-tree', tree]
        for parent in parents:
            args.extend(['-p', parent])
        return self.git(*args, input=message + '\n')

    def fixture(self, scenario):
        current = self.base
        sources = []
        replacements = {}
        entries = []
        for i, (path, old_import, old_source) in enumerate(self.ps_records):
            blob = self.git('hash-object', '-w', '--stdin', input=path + '\n')
            tree = self.git('mktree', input=f'100644 blob {blob}\tcontent.txt\n')
            source = self.commit(tree, [], 'source ' + path)
            sources.append(source)
            entries.append(f'040000 tree {tree}\t{path}\n')
            imported_tree = self.git('mktree', input=''.join(entries))
            if scenario == 'wrong-tree' and i == 0:
                imported_tree = self.git('mktree', input=f'040000 tree {self.empty}\t{path}\n')
            parents = [current] if scenario == 'source-unreachable' else [current, source]
            imported = self.commit(imported_tree, parents, 'import ' + path)
            replacements[old_import] = imported
            replacements[old_source] = source
            current = imported
        if scenario == 'import-unreachable':
            # Every recorded object exists and every source is reachable. Only the
            # import commits are absent from HEAD's ancestry: the historical false pass.
            current = self.commit(self.empty, [self.base, *sources], 'alternate merge')
        elif scenario == 'missing-import':
            replacements[self.ps_records[0][1]] = '1' * 40
        elif scenario == 'missing-source':
            replacements[self.ps_records[0][2]] = '2' * 40
        self.git('update-ref', 'HEAD', current)
        script_dir = self.root / 'scripts'
        script_dir.mkdir()
        for original in (PS, SH):
            text = original.read_text()
            for old, new in replacements.items():
                text = text.replace(old, new)
            (script_dir / original.name).write_text(text, encoding='utf-8', newline='\n')

    def verify(self, scenario, success):
        self.fixture(scenario)
        self.assertEqual(len(self.ps_records), 6)
        for name, executable, extension in self.shells:
            with self.subTest(shell=name, scenario=scenario):
                script = self.root / f'scripts/verify-imported-histories.{extension}'
                args = [executable, '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', str(script)] if extension == 'ps1' else [executable, str(script)]
                # A different working directory proves the scripts locate their own root.
                run = subprocess.run(args, cwd=self.temporary.name, env=self.env, text=True,
                                     capture_output=True, timeout=30)
                output = run.stdout + run.stderr
                self.assertEqual(run.returncode == 0, success, output)
                if success:
                    self.assertEqual(run.stdout.count('verified '), 6, output)

    def test_shells_record_the_same_six_imports(self):
        self.assertEqual(len(self.ps_records), 6)
        self.assertEqual(self.ps_records, self.sh_records)

    def test_complete_preserved_history_passes(self):
        self.verify('healthy', True)

    def test_missing_import_is_rejected(self):
        self.verify('missing-import', False)

    def test_missing_source_is_rejected(self):
        self.verify('missing-source', False)

    def test_unreachable_source_is_rejected(self):
        self.verify('source-unreachable', False)

    def test_unreachable_import_is_rejected(self):
        self.verify('import-unreachable', False)

    def test_imported_subtree_mismatch_is_rejected(self):
        self.verify('wrong-tree', False)
