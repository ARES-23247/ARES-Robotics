import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'verify-monorepo-policy.ps1'
PRODUCTS = ('ARESLib-Kotlin', 'ARES-FTC', 'ARES-FRC', 'ARES-FTC-Starter', 'ARES-FRC-Starter', 'ARES-Analytics')

class MonorepoPolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.shells = [p for name in ('pwsh', 'powershell') if (p := shutil.which(name))]
        if not cls.shells or not shutil.which('git'):
            raise unittest.SkipTest('Git and PowerShell required')

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='ares-policy-audit-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.env = {k:v for k,v in os.environ.items() if not k.startswith('GIT_')}
        # Let each PowerShell edition load its own built-in modules.
        self.env = {k:v for k,v in self.env.items() if k.upper() != 'PSMODULEPATH'}
        self.env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull)
        self.git('init', '--quiet')
        self.write('scripts/verify-monorepo-policy.ps1', SCRIPT.read_text(encoding='utf-8'))
        # These dependencies have their own real test suites. Isolate this policy's behavior.
        self.write('scripts/verify_agent_guidance.py', 'pass\n')
        self.write('scripts/verify-doc-links.ps1', '$global:LASTEXITCODE = 0\n')
        self.write('release/ares-versions.properties', '\n'.join(f'{k}=1.0.0' for k in ('aresVersion','studioVersion','ftcStarterVersion','frcStarterVersion','xrpStarterVersion','lightbotExampleVersion'))+'\ngithubMavenRepository=https://example.invalid/maven\n')
        pins=[]
        for name,key in [('ARES-FTC-Starter','ftcStarterSha256'),('ARES-FRC-Starter','frcStarterSha256'),('ARES-XRP-Starter','xrpStarterSha256'),('ARES-Lightbot-Example','lightbotExampleSha256')]:
            self.write(f'ARES-Analytics/app/src/main/resources/project-templates/{name}-1.0.0.zip', 'fixture archive')
            pins.append(f'{key}={hashlib.sha256(b"fixture archive").hexdigest()}')
        self.write('release/starter-artifacts.properties','\n'.join(pins)+'\n')
        for product in PRODUCTS: self.write(f'{product}/gradle.properties','# fixture\n')
        for name in ('analytics-validation','build-distributions','codeql','monorepo-ci','verify-autos'):
            self.write(f'.github/workflows/{name}.yml','on:\n  pull_request:\n  merge_group:\n')
        self.write('verify-autos.ps1','# fixture\n'); self.write('verify-autos.sh','# fixture\n')
        self.write('ARES-Analytics/scripts/run-local-ares.ps1', '# release\\ares-versions.properties\n')
        guidance=['AGENTS.md','GEMINI.md','docs/agents/README.md','docs/agents/WORKSPACE_GUIDE.md','.agents/rules/ares-workspace.md','ARESLib-Kotlin/GEMINI.md','ARES-FTC-Starter/AGENTS.md','ARES-FRC-Starter/AGENTS.md','ARES-Analytics/docs/admin/GOOGLE_CLOUD_OAUTH.md','ARES-Analytics/docs/VALIDATION.md']
        guidance += ['.agents/skills/compose-desktop-tester/'+p for p in ['SKILL.md','references/startup-recovery.md','scripts/capture_app.ps1','scripts/inspect_app_window.ps1','scripts/interact_app.ps1']]
        for path in guidance: self.write(path,'# fixture\n')
        self.write('templates/ftc/runtime/src/main/kotlin/org/firstinspires/ftc/teamcode/dsl/FtcGeneratedProjectRuntime.kt','// fixture\n')
        self.write('ARES-FRC/src/main/kotlin/Robot.kt','package robot\n')
        self.write('.gitignore','.tmp/\n.gradle/\nbuild/\n')
        self.git('add','.')
        self.git('-c','user.name=Audit','-c','user.email=audit@example.invalid','commit','--quiet','-m','fixture')
        tree=self.git('rev-parse','HEAD:ARESLib-Kotlin').stdout.strip()
        self.write('release/ares-source-tree.txt',tree+'\n')

    def git(self,*args):
        return subprocess.run(['git','-C',str(self.root),*args],env=self.env,capture_output=True,text=True,check=True,timeout=20)

    def write(self,path,text):
        p=self.root/path; p.parent.mkdir(parents=True,exist_ok=True)
        p.write_text(text,encoding='utf-8',newline='\n')
        return p

    def verify(self,success,diagnostic=None):
        for shell in self.shells:
            with self.subTest(shell=shell):
                run=subprocess.run([shell,'-NoProfile','-NonInteractive','-File',str(self.root/'scripts/verify-monorepo-policy.ps1')],cwd=self.root.parent,env=self.env,capture_output=True,text=True,timeout=30)
                output=run.stdout+run.stderr
                self.assertEqual(run.returncode==0,success,output)
                if diagnostic: self.assertIn(diagnostic,output)

    def test_healthy_fixture_passes(self):
        self.verify(True)

    def test_ignored_validation_checkout_is_not_product_source(self):
        self.write('.tmp/export/build.gradle.kts','repositories { mavenLocal() }\n')
        self.write('.tmp/export/src/main/kotlin/ARESController.kt','// historical scratch source\n')
        self.verify(True)

    def test_untracked_build_violation_is_still_detected(self):
        self.write('new module/build.gradle.kts','repositories { mavenLocal() }\n')
        self.verify(False,'Ambient mavenLocal() is forbidden')

    def test_tracked_ignored_source_is_checked(self):
        self.write('.tmp/src/main/kotlin/ARESController.kt','// tracked source\n')
        self.git('add','-f','.tmp/src/main/kotlin/ARESController.kt')
        self.verify(False,'Retired production type ARESController returned')

    def test_hidden_tracked_build_file_is_checked(self):
        p=self.write('hidden/build.gradle.kts','repositories { mavenLocal() }\n')
        self.git('add','hidden/build.gradle.kts')
        if os.name=='nt':
            import ctypes
            self.assertTrue(ctypes.windll.kernel32.SetFileAttributesW(str(p),2))
            self.addCleanup(ctypes.windll.kernel32.SetFileAttributesW,str(p),128)
        self.verify(False,'Ambient mavenLocal() is forbidden')

    def test_nonempty_line_limit_boundary(self):
        p=self.write('ARES-FRC/src/main/kotlin/Large.kt','package robot\n'+'// line\n'*999)
        self.verify(True)
        p.write_text('package robot\n'+'// line\n'*1000,encoding='utf-8',newline='\n')
        self.verify(False,'exceed 1000 lines')

    def test_deleted_source_does_not_break_inventory(self):
        (self.root/'ARES-FRC/src/main/kotlin/Robot.kt').unlink()
        self.verify(True)

    def test_existing_retired_namespace_is_rejected(self):
        self.write('ARES-FRC/src/test/kotlin/Namespace.kt','package com.areslib.frc.old\n')
        self.verify(False,'retired library namespace')

    def test_unicode_and_space_source_paths(self):
        self.write('module caf\u00e9/src/main/kotlin/ARESController.kt','// forbidden\n')
        self.verify(False,'Retired production type ARESController returned')

    def test_root_production_source_is_checked(self):
        self.write('src/main/kotlin/ARESController.kt','// forbidden\n')
        self.verify(False,'Retired production type ARESController returned')

    def test_explicit_build_output_exclusions_remain(self):
        self.write('module/build/build.gradle.kts','mavenLocal()\n')
        self.write('module/.gradle/build.gradle.kts','mavenLocal()\n')
        self.write('module/build/src/main/kotlin/ARESController.kt','// generated\n')
        self.git('add','-f','module')
        self.verify(True)

    def test_git_inventory_failure_is_not_an_empty_success(self):
        (self.root/'.git/index').write_bytes(b'corrupt fixture index')
        self.verify(False,'Unable to enumerate monorepo source files')

if __name__=='__main__': unittest.main()
