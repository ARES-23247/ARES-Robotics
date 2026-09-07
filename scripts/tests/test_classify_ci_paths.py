import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "classify_ci_paths.py"
SPEC = importlib.util.spec_from_file_location("classify_ci_paths", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ClassifyCiPathsTest(unittest.TestCase):
    def test_docs_and_agent_guidance_run_policy_only(self):
        result = MODULE.classify_paths([
            "AGENTS.md", ".agents/skills/example/SKILL.md", "docs/ci-test-scopes.md",
            "ARES-Analytics/docs/OPERATIONS.md",
        ])
        self.assertFalse(any(result.values()))

    def test_analytics_change_runs_only_studio_consumer(self):
        result = MODULE.classify_paths(["ARES-Analytics/app/src/main/kotlin/App.kt"])
        self.assertTrue(result["analytics"])
        self.assertFalse(result["full"])
        self.assertFalse(result["lib"])
        self.assertFalse(result["ftc"])
        self.assertTrue(result["analytics_app"])
        self.assertFalse(result["analytics_gateway"])
        self.assertFalse(result["analytics_shared"])
        self.assertTrue(result["packages"])

    def test_library_change_runs_candidate_and_every_consumer(self):
        result = MODULE.classify_paths(["ARESLib-Kotlin/core/src/main/kotlin/Clock.kt"])
        self.assertTrue(all(result.values()))

    def test_xrp_change_covers_python_and_studio_template_consumer(self):
        result = MODULE.classify_paths(["ARES-XRP-Starter/tools/ares_project.py"])
        self.assertTrue(result["xrp_starter"])
        self.assertFalse(result["full"])
        self.assertTrue(result["analytics_app"])
        self.assertTrue(result["packages"])
        self.assertFalse(result["ftc"])
        self.assertFalse(result["frc"])

    def test_shared_release_change_runs_every_consumer_without_marking_library_source(self):
        result = MODULE.classify_paths(["release/ares-versions.properties"])
        self.assertTrue(result["full"])
        self.assertFalse(result["lib"])
        self.assertTrue(result["ftc_starter"])
        self.assertTrue(result["analytics"])

    def test_manual_scheduled_and_unknown_events_run_full_matrix(self):
        for event in ("push", "schedule", "workflow_dispatch", "unexpected"):
            with self.subTest(event=event):
                self.assertTrue(all(MODULE.classify_paths([], event_name=event).values()))

    def test_unknown_root_file_fails_safe_to_full_matrix(self):
        result = MODULE.classify_paths(["new-build-contract.toml"])
        self.assertTrue(result["full"])
        self.assertTrue(result["frc"])

    def test_merge_queue_uses_same_affected_scopes_as_pull_requests(self):
        paths = ["ARES-FRC/src/main/kotlin/Robot.kt"]
        self.assertEqual(MODULE.classify_paths(paths), MODULE.classify_paths(paths, "merge_group"))
        self.assertFalse(MODULE.classify_paths(paths, "merge_group")["ftc"])

    def test_gateway_change_does_not_run_desktop_or_robot_tests(self):
        result = MODULE.classify_paths(["ARES-Analytics/gateway/src/main/kotlin/Route.kt"])
        self.assertEqual({key for key, value in result.items() if value},
                         {"analytics", "analytics_gateway", "jvm"})

    def test_shared_models_run_all_studio_modules(self):
        result = MODULE.classify_paths(["ARES-Analytics/shared/src/main/kotlin/Models.kt"])
        for key in ("analytics", "analytics_app", "analytics_gateway", "analytics_shared",
                    "dashboard", "packages"):
            self.assertTrue(result[key], key)
        self.assertFalse(result["ftc"])

    def test_studio_build_configuration_runs_all_studio_modules(self):
        for path in ("ARES-Analytics/build.gradle.kts", "ARES-Analytics/gradle/libs.versions.toml",
                     "ARES-Analytics/settings.gradle.kts"):
            with self.subTest(path=path):
                result = MODULE.classify_paths([path])
                self.assertTrue(result["analytics_shared"])
                self.assertTrue(result["analytics_app"])
                self.assertTrue(result["analytics_gateway"])

    def test_starter_source_runs_its_consumer_and_packaging(self):
        for product, key in (("ARES-FTC-Starter", "ftc_starter"),
                             ("ARES-FRC-Starter", "frc_starter")):
            with self.subTest(product=product):
                result = MODULE.classify_paths([product + "/src/Robot.kt"])
                self.assertTrue(result[key])
                self.assertTrue(result["analytics_app"])
                self.assertTrue(result["packages"])
                self.assertFalse(result["ftc"])
                self.assertFalse(result["frc"])

    def test_lightbot_is_packaged_but_frc_season_is_not(self):
        ftc = MODULE.classify_paths(["ARES-FTC/TeamCode/src/Robot.kt"])
        frc = MODULE.classify_paths(["ARES-FRC/src/Robot.kt"])
        self.assertTrue(ftc["packages"])
        self.assertFalse(frc["packages"])
        self.assertTrue(ftc["autos"])
        self.assertTrue(frc["autos"])

    def test_mixed_changes_union_the_affected_products(self):
        result = MODULE.classify_paths(["ARES-FRC/src/Robot.kt",
                                       "ARES-Analytics/gateway/build.gradle.kts"])
        self.assertTrue(result["frc"])
        self.assertTrue(result["analytics_gateway"])
        self.assertFalse(result["analytics_app"])
        self.assertFalse(result["packages"])

    def test_shared_build_and_ci_inputs_select_every_scope(self):
        for path in ("scripts/classify_ci_paths.py", ".github/workflows/ci-scopes.yml",
                     "build-logic/plugin.gradle.kts", "build.ps1"):
            with self.subTest(path=path):
                result = MODULE.classify_paths([path])
                self.assertTrue(result["full"])
                self.assertTrue(all(value for key, value in result.items() if key != "lib"))

    def test_docs_embedded_in_source_resources_are_not_ignored(self):
        result = MODULE.classify_paths(["ARES-Analytics/app/src/main/resources/docs/help.md"])
        self.assertTrue(result["analytics_app"])

    def test_no_changed_paths_runs_policy_only(self):
        for event in ("pull_request", "merge_group"):
            self.assertFalse(any(MODULE.classify_paths([], event).values()))

    def test_diff_failure_does_not_emit_an_empty_successful_scope(self):
        with patch.object(MODULE.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "git")):
            with self.assertRaises(subprocess.CalledProcessError):
                MODULE._git_changed_paths("a" * 40, "b" * 40)
        with self.assertRaises(ValueError):
            MODULE._git_changed_paths("", "b" * 40)
        with self.assertRaises(ValueError):
            MODULE._git_changed_paths("--all", "b" * 40)

    def test_nul_diff_preserves_unusual_filenames_and_has_no_api_file_limit(self):
        paths = [f"ARES-Analytics/gateway/src/File{i}.kt" for i in range(4000)]
        paths += ["ARES-FRC/src/space and\nnewline.kt", "ARES-FTC/src/é.kt"]
        completed = subprocess.CompletedProcess([], 0, ("\0".join(paths) + "\0").encode("utf-8"))
        with patch.object(MODULE.subprocess, "run", return_value=completed):
            self.assertEqual(paths, MODULE._git_changed_paths("a" * 40, "b" * 40))
        result = MODULE.classify_paths(paths)
        self.assertTrue(result["ftc"])
        self.assertTrue(result["frc"])

    def test_cli_classifies_both_sides_of_a_real_cross_product_rename(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(["git", *args], cwd=root, text=True).strip()
            git("init", "-q")
            git("config", "user.name", "CI Scope Test")
            git("config", "user.email", "ci-scope@example.invalid")
            source = root / "ARES-Analytics/gateway/renamed.txt"
            source.parent.mkdir(parents=True)
            source.write_text("unchanged content")
            git("add", ".")
            git("commit", "-qm", "base")
            base = git("rev-parse", "HEAD")
            target = root / "ARES-FRC/renamed.txt"
            target.parent.mkdir()
            git("mv", str(source), str(target))
            git("commit", "-qm", "move")
            head = git("rev-parse", "HEAD")
            for event in ("pull_request", "merge_group"):
                output = root / (event + ".outputs")
                summary = root / (event + ".summary")
                subprocess.run([sys.executable, str(SCRIPT), "--event-name", event,
                                "--base-sha", base, "--head-sha", head,
                                "--github-output", str(output), "--github-summary", str(summary)],
                               cwd=root, check=True, capture_output=True, text=True,
                               env={**os.environ, "GIT_CONFIG_NOSYSTEM": "1"})
                values = dict(line.split("=", 1) for line in output.read_text().splitlines())
                self.assertEqual(values["frc"], "true")
                self.assertEqual(values["analytics_gateway"], "true")
                self.assertEqual(values["analytics_app"], "false")
                self.assertIn("changed paths: 2", summary.read_text())


if __name__ == "__main__":
    unittest.main()
