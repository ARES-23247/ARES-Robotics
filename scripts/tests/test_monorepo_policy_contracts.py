"""Repository contract tests for CI hardening, wrappers, toolchains, and policy parity."""

from pathlib import Path
import re
import unittest
import yaml


ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"


class MonorepoPolicyContractsTest(unittest.TestCase):
    def test_workflows_pin_external_actions_and_timeouts_and_avoid_run_interpolation(self):
        sha_pattern = re.compile(r"^[^/]+/[^@]+@[0-9a-f]{40}$")
        for workflow in sorted(WORKFLOWS.glob("*.yml")):
            with self.subTest(workflow=workflow.name):
                data = yaml.safe_load(workflow.read_text(encoding="utf-8"))
                for job_name, job in data.get("jobs", {}).items():
                    if "uses" in job:
                        self.assertTrue(
                            job["uses"].startswith("./.github/workflows/"),
                            f"{workflow.name}:{job_name} external reusable workflow must be pinned",
                        )
                        continue
                    self.assertIn(
                        "timeout-minutes",
                        job,
                        f"{workflow.name}:{job_name} is missing timeout-minutes",
                    )
                    for step in job.get("steps", []):
                        uses = step.get("uses")
                        if uses:
                            self.assertRegex(
                                uses,
                                sha_pattern,
                                f"{workflow.name}:{job_name} must SHA-pin {uses}",
                            )
                        run = step.get("run")
                        if run:
                            self.assertNotIn(
                                "${{",
                                run,
                                f"{workflow.name}:{job_name} must bind expressions via env:",
                            )

    def test_checkout_credentials_and_concurrency_hardening(self):
        for workflow in sorted(WORKFLOWS.glob("*.yml")):
            data = yaml.safe_load(workflow.read_text(encoding="utf-8"))
            for job_name, job in data.get("jobs", {}).items():
                for step in job.get("steps", []):
                    uses = step.get("uses", "")
                    if uses.startswith("actions/checkout@"):
                        if workflow.name == "promote-release-candidate.yml" and job_name == "publish-release":
                            # publish-release intentionally retains checkout credentials for origin/maven push.
                            continue
                        with_cfg = step.get("with", {})
                        self.assertIs(
                            with_cfg.get("persist-credentials"),
                            False,
                            f"{workflow.name}:{job_name} checkout must set persist-credentials: false",
                        )

        verify_autos = yaml.safe_load((WORKFLOWS / "verify-autos.yml").read_text(encoding="utf-8"))
        self.assertIn("concurrency", verify_autos)

        for name in ("analytics-validation.yml", "codeql.yml"):
            data = yaml.safe_load((WORKFLOWS / name).read_text(encoding="utf-8"))
            self.assertEqual(
                data["concurrency"]["cancel-in-progress"],
                "${{ github.event_name == 'pull_request' }}",
            )

    def test_critical_ci_security_and_coverage_gates(self):
        build_dist = (WORKFLOWS / "build-distributions.yml").read_text(encoding="utf-8")
        self.assertIn(
            "ARES_WINDOWS_UPDATE_SIGNER_THUMBPRINTS: ${{ vars.ARES_WINDOWS_UPDATE_SIGNER_THUMBPRINTS }}",
            build_dist,
        )
        build_dist_yaml = yaml.safe_load(build_dist)
        seal_if = build_dist_yaml["jobs"]["seal-candidate"].get("if", "")
        self.assertIn("github.event.pull_request.head.repo.full_name == github.repository", seal_if)

        monorepo_ci = (WORKFLOWS / "monorepo-ci.yml").read_text(encoding="utf-8")
        for token in (
            ":verifyProductionKotlinFileSizes",
            ":shared:koverVerify",
            ":mcp:koverVerify",
            ":gateway:koverVerify",
            ":app:koverVerify",
        ):
            self.assertIn(token, monorepo_ci)

        codeql = (WORKFLOWS / "codeql.yml").read_text(encoding="utf-8")
        for path in (
            "ARES-FTC/biobuzz/TeamCode/src",
            "ARES-FTC/biobuzz/simulator/src",
            "ARES-FTC/biobuzz/shared/src",
        ):
            self.assertIn(path, codeql)

        promote = (WORKFLOWS / "promote-release-candidate.yml").read_text(encoding="utf-8")
        self.assertIn("Get-FileHash", promote)
        self.assertIn("shasum -a 256 -c", promote)

    def test_non_areslib_wrappers_pin_sha256_and_analytics_has_toolchain_17(self):
        expected = {
            "ARES-Analytics": "6f74b601422d6d6fc4e1f9a1ab6522f642c2fdcbc15ae33ebd30ba3d7198e854",
            "ARES-FRC": "6f74b601422d6d6fc4e1f9a1ab6522f642c2fdcbc15ae33ebd30ba3d7198e854",
            "ARES-FRC-Starter": "6f74b601422d6d6fc4e1f9a1ab6522f642c2fdcbc15ae33ebd30ba3d7198e854",
            "ARES-FTC": "a17ddd85a26b6a7f5ddb71ff8b05fc5104c0202c6e64782429790c933686c806",
            "ARES-FTC-Starter": "a17ddd85a26b6a7f5ddb71ff8b05fc5104c0202c6e64782429790c933686c806",
        }
        for product, digest in expected.items():
            wrapper = (ROOT / product / "gradle" / "wrapper" / "gradle-wrapper.properties").read_text(encoding="utf-8")
            self.assertIn(f"distributionSha256Sum={digest}", wrapper, product)
        analytics_wrapper = (ROOT / "ARES-Analytics" / "gradle" / "wrapper" / "gradle-wrapper.properties").read_text(encoding="utf-8")
        self.assertIn("networkTimeout=10000", analytics_wrapper)
        self.assertIn("validateDistributionUrl=true", analytics_wrapper)

        analytics_build = (ROOT / "ARES-Analytics" / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn("jvmToolchain(17)", analytics_build)

    def test_frc_starter_namespace_task_and_setup_sh_checks(self):
        frc_starter_build = (ROOT / "ARES-FRC-Starter" / "build.gradle").read_text(encoding="utf-8")
        self.assertIn("verifyNamespaceOwnership", frc_starter_build)
        self.assertIn("dependsOn tasks.named('verifyNamespaceOwnership')", frc_starter_build)

        setup_sh = (ROOT / "setup.sh").read_text(encoding="utf-8")
        for token in ("verify_agent_guidance.py", "verify_kotlin_toolchain.py", "generate_codebase_ledger.py", "--verify"):
            self.assertIn(token, setup_sh)


if __name__ == "__main__":
    unittest.main()
