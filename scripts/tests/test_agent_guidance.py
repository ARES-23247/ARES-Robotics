"""Exercise guidance failures against disposable Git repositories."""

import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location(
    "agent_guidance", Path(__file__).resolve().parents[1] / "verify_agent_guidance.py")
guidance = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(guidance)


class AgentGuidanceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git("init", "--quiet")
        self.git("config", "core.excludesFile", str(self.root / "empty-global-ignore"))
        names = ["AGENTS.md", "docs/agents/README.md", "docs/agents/WORKSPACE_GUIDE.md",
                 "ARESLib-Kotlin/GEMINI.md", "ARES-FTC-Starter/AGENTS.md", "ARES-FRC-Starter/AGENTS.md"]
        names.extend(f".agents/skills/{name}/SKILL.md" for name in guidance.SKILLS)
        for name in names:
            self.write(name, "# Shared guidance\n")
        for name, target in guidance.ADAPTERS.items():
            prefix = "---\ntrigger: always_on\n---\n" if name.startswith(".agents/") else ""
            self.write(name, prefix + target + "\n")
        self.git("add", ".")

    def git(self, *args):
        subprocess.run(["git", "-C", str(self.root), *args], check=True, capture_output=True)

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def test_complete_checkout(self):
        self.assertEqual([], guidance.check(self.root))

    def test_untracked_reference(self):
        self.write(".agents/skills/ares-workspace/references/new.md", "New instructions")
        self.assertTrue(any("Untracked guidance" in e for e in guidance.check(self.root)))

    def test_ignore_detected_even_for_tracked_file(self):
        self.write(".gitignore", "AGENTS.md\n")
        self.assertTrue(any("Ignored guidance: AGENTS.md" in e for e in guidance.check(self.root)))

    def test_missing_link_and_missing_skill(self):
        self.write("AGENTS.md", "[Missing](docs/missing.md)\n")
        (self.root / ".agents/skills/ares-workspace/SKILL.md").unlink()
        errors = guidance.check(self.root)
        self.assertTrue(any("Missing/outside-repo link" in e for e in errors))
        self.assertTrue(any("Missing guidance" in e for e in errors))

    def test_adapter_drift_and_size(self):
        self.write("GEMINI.md", "@./different.md\n")
        self.write(".agents/rules/ares-workspace.md", "@../../AGENTS.md\n")
        self.write("AGENTS.md", "x" * (guidance.ROOT_LIMIT + 1))
        errors = guidance.check(self.root)
        self.assertTrue(any("Adapter must import" in e for e in errors))
        self.assertTrue(any("Always On" in e for e in errors))
        self.assertTrue(any("exceeds" in e for e in errors))

    def test_repo_exceptions_override_broad_personal_ignores(self):
        source_root = Path(__file__).resolve().parents[2]
        self.write(".gitignore", (source_root / ".gitignore").read_text(encoding="utf-8"))
        self.write("empty-global-ignore", "AGENTS.md\nGEMINI.md\n.agents/\n")
        self.assertEqual([], guidance.check(self.root))


if __name__ == "__main__":
    unittest.main()
