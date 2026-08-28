import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "classify_ci_paths.py"
SPEC = importlib.util.spec_from_file_location("classify_ci_paths", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ClassifyCiPathsTest(unittest.TestCase):
    def test_docs_and_agent_guidance_run_policy_only(self):
        result = MODULE.classify_paths(["AGENTS.md", ".agents/skills/example/SKILL.md"])
        self.assertFalse(any(result.values()))

    def test_analytics_change_runs_only_studio_consumer(self):
        result = MODULE.classify_paths(["ARES-Analytics/app/src/main/kotlin/App.kt"])
        self.assertTrue(result["analytics"])
        self.assertFalse(result["full"])
        self.assertFalse(result["lib"])
        self.assertFalse(result["ftc"])

    def test_library_change_runs_candidate_and_every_consumer(self):
        result = MODULE.classify_paths(["ARESLib-Kotlin/core/src/main/kotlin/Clock.kt"])
        self.assertTrue(all(result.values()))

    def test_shared_release_change_runs_every_consumer_without_marking_library_source(self):
        result = MODULE.classify_paths(["release/ares-versions.properties"])
        self.assertTrue(result["full"])
        self.assertFalse(result["lib"])
        self.assertTrue(result["ftc_starter"])
        self.assertTrue(result["analytics"])

    def test_push_and_merge_group_always_run_full_matrix(self):
        for event in ("push", "merge_group", "workflow_dispatch"):
            with self.subTest(event=event):
                self.assertTrue(all(MODULE.classify_paths([], event_name=event).values()))

    def test_unknown_root_file_fails_safe_to_full_matrix(self):
        result = MODULE.classify_paths(["new-build-contract.toml"])
        self.assertTrue(result["full"])
        self.assertTrue(result["frc"])


if __name__ == "__main__":
    unittest.main()
