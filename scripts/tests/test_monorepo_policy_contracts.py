"""Repository contract tests for CI hardening, wrappers, toolchains, and policy parity."""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"

_BLOCK_SCALAR_RE = re.compile(r"^([|>])([+-]?)(?:\d+)?$")
_KV_RE = re.compile(r"^([A-Za-z0-9_.-]+|\"[^\"]+\"|'[^']+')\s*:(?:\s+(.*))?$")


def _strip_yaml_comment(line: str) -> str:
    in_single = False
    in_double = False
    i = 0
    while i < len(line):
        ch = line[i]
        if ch == "'" and not in_double:
            if in_single and i + 1 < len(line) and line[i + 1] == "'":
                i += 2
                continue
            in_single = not in_single
        elif ch == '"' and not in_single:
            if in_double and i + 1 < len(line) and line[i + 1] == "\\":
                i += 2
                continue
            in_double = not in_double
        elif ch == "#" and not in_single and not in_double:
            if i == 0 or line[i - 1].isspace():
                return line[:i].rstrip()
        i += 1
    return line.rstrip()


def _unquote_key(raw: str) -> str:
    if len(raw) >= 2 and raw[0] == "'" and raw[-1] == "'":
        return raw[1:-1].replace("''", "'")
    if len(raw) >= 2 and raw[0] == '"' and raw[-1] == '"':
        return raw[1:-1]
    return raw


def _parse_scalar(raw: str):
    val = raw.strip()
    if not val:
        return None
    if len(val) >= 2 and val[0] == "'" and val[-1] == "'":
        return val[1:-1].replace("''", "'")
    if len(val) >= 2 and val[0] == '"' and val[-1] == '"':
        return val[1:-1]
    if val in ("true", "True"):
        return True
    if val in ("false", "False"):
        return False
    if val in ("null", "~"):
        return None
    if re.fullmatch(r"-?\d+", val):
        return int(val)
    if val.startswith("[") and val.endswith("]"):
        inner = val[1:-1].strip()
        if not inner:
            return []
        return [_parse_scalar(part) for part in inner.split(",")]
    return val


def _next_meaningful(lines: list[str], idx: int):
    while idx < len(lines):
        raw = lines[idx]
        stripped = _strip_yaml_comment(raw)
        if stripped.strip():
            indent = len(raw) - len(raw.lstrip(" "))
            return idx, indent, stripped.lstrip(" ")
        idx += 1
    return None, -1, ""


def _parse_block_scalar(lines: list[str], idx: int, parent_indent: int, style: str, chomp: str) -> tuple[str, int]:
    collected: list[tuple[int, str]] = []
    block_indent = None
    while idx < len(lines):
        raw = lines[idx]
        if not raw.strip():
            collected.append((-1, ""))
            idx += 1
            continue
        indent = len(raw) - len(raw.lstrip(" "))
        if indent <= parent_indent:
            break
        if block_indent is None:
            block_indent = indent
        collected.append((indent, raw[min(block_indent, len(raw)) :]))
        idx += 1

    while collected and collected[-1][0] == -1:
        if chomp == "+":
            break
        collected.pop()

    if style == "|":
        text = "\n".join(content for _, content in collected)
    else:
        paragraphs: list[str] = []
        current: list[str] = []
        for indent, content in collected:
            if indent == -1:
                if current:
                    paragraphs.append(" ".join(current))
                    current = []
                else:
                    paragraphs.append("")
            else:
                current.append(content.strip())
        if current:
            paragraphs.append(" ".join(current))
        text = "\n".join(paragraphs)

    if chomp == "" and text:
        text += "\n"
    return text, idx


def _parse_value(lines: list[str], idx: int, parent_indent: int, rest: str):
    block_match = _BLOCK_SCALAR_RE.match(rest)
    if block_match:
        return _parse_block_scalar(lines, idx + 1, parent_indent, block_match.group(1), block_match.group(2))
    if rest:
        return _parse_scalar(rest), idx + 1
    next_idx, next_indent, next_content = _next_meaningful(lines, idx + 1)
    if next_idx is not None and (
        next_indent > parent_indent
        or (next_indent == parent_indent and (next_content == "-" or next_content.startswith("- ")))
    ):
        return _parse_node(lines, next_idx, next_indent)
    return None, idx + 1


def _parse_sequence(lines: list[str], idx: int, seq_indent: int):
    items = []
    while True:
        cur_idx, indent, content = _next_meaningful(lines, idx)
        if cur_idx is None or indent < seq_indent:
            break
        if indent > seq_indent or not (content == "-" or content.startswith("- ")):
            break
        after_dash = content[1:].lstrip(" ")
        if not after_dash:
            next_idx, next_indent, _ = _next_meaningful(lines, cur_idx + 1)
            if next_idx is not None and next_indent > seq_indent:
                val, idx = _parse_node(lines, next_idx, next_indent)
            else:
                val, idx = None, cur_idx + 1
            items.append(val)
            continue

        kv = _KV_RE.match(after_dash)
        if kv:
            item_map = {}
            key = _unquote_key(kv.group(1))
            rest = (kv.group(2) or "").strip()
            item_indent = seq_indent + (len(content) - len(after_dash))
            val, idx = _parse_value(lines, cur_idx, item_indent, rest)
            item_map[key] = val

            while True:
                n_idx, n_indent, n_content = _next_meaningful(lines, idx)
                if n_idx is None or n_indent <= seq_indent:
                    break
                if n_indent < item_indent:
                    break
                if n_content == "-" or n_content.startswith("- "):
                    break
                n_kv = _KV_RE.match(n_content)
                if not n_kv:
                    break
                n_key = _unquote_key(n_kv.group(1))
                n_rest = (n_kv.group(2) or "").strip()
                n_val, idx = _parse_value(lines, n_idx, n_indent, n_rest)
                item_map[n_key] = n_val
            items.append(item_map)
        else:
            val, idx = _parse_value(lines, cur_idx, seq_indent, after_dash)
            items.append(val)
    return items, idx


def _parse_mapping(lines: list[str], idx: int, map_indent: int):
    mapping = {}
    while True:
        cur_idx, indent, content = _next_meaningful(lines, idx)
        if cur_idx is None or indent < map_indent:
            break
        if indent > map_indent:
            raise ValueError(f"Unexpected indentation at line {cur_idx + 1}: {lines[cur_idx]!r}")
        if content == "-" or content.startswith("- "):
            break
        kv = _KV_RE.match(content)
        if not kv:
            raise ValueError(f"Expected mapping key at line {cur_idx + 1}: {lines[cur_idx]!r}")
        key = _unquote_key(kv.group(1))
        rest = (kv.group(2) or "").strip()
        val, idx = _parse_value(lines, cur_idx, map_indent, rest)
        mapping[key] = val
    return mapping, idx


def _parse_node(lines: list[str], idx: int, indent: int):
    _, _, content = _next_meaningful(lines, idx)
    if content == "-" or content.startswith("- "):
        return _parse_sequence(lines, idx, indent)
    return _parse_mapping(lines, idx, indent)


def parse_workflow_yaml(text: str) -> dict:
    lines = text.splitlines()
    start_idx, start_indent, _ = _next_meaningful(lines, 0)
    if start_idx is None:
        return {}
    data, _ = _parse_node(lines, start_idx, start_indent)
    if not isinstance(data, dict):
        raise ValueError("Top-level workflow YAML must be a mapping")
    return data


class MonorepoPolicyContractsTest(unittest.TestCase):
    def test_stdlib_workflow_parser_detects_nested_and_multiline_constructs(self):
        sample = """
        name: Sample
        concurrency:
          group: sample-${{ github.ref }}
          cancel-in-progress: ${{ github.event_name == 'pull_request' }}
        jobs:
          call:
            uses: ./.github/workflows/ci-scopes.yml
          build:
            timeout-minutes: 15
            if: >-
              github.event_name != 'pull_request' ||
              github.event.pull_request.head.repo.full_name == github.repository
            steps:
              - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
                with:
                  persist-credentials: false
              - name: Unsafe run
                run: |
                  echo "${{ github.event.inputs.bad }}"
        """
        dedented = "\n".join(line[8:] if len(line) >= 8 else line for line in sample.strip("\n").splitlines())
        parsed = parse_workflow_yaml(dedented)
        self.assertEqual(parsed["concurrency"]["cancel-in-progress"], "${{ github.event_name == 'pull_request' }}")
        self.assertEqual(parsed["jobs"]["call"]["uses"], "./.github/workflows/ci-scopes.yml")
        self.assertEqual(parsed["jobs"]["build"]["timeout-minutes"], 15)
        self.assertIn("github.event.pull_request.head.repo.full_name == github.repository", parsed["jobs"]["build"]["if"])
        self.assertIs(parsed["jobs"]["build"]["steps"][0]["with"]["persist-credentials"], False)
        self.assertIn("${{", parsed["jobs"]["build"]["steps"][1]["run"])

    def test_workflows_pin_external_actions_and_timeouts_and_avoid_run_interpolation(self):
        sha_pattern = re.compile(r"^[^/]+/[^@]+@[0-9a-f]{40}$")
        for workflow in sorted(WORKFLOWS.glob("*.yml")):
            with self.subTest(workflow=workflow.name):
                data = parse_workflow_yaml(workflow.read_text(encoding="utf-8"))
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
            data = parse_workflow_yaml(workflow.read_text(encoding="utf-8"))
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

        verify_autos = parse_workflow_yaml((WORKFLOWS / "verify-autos.yml").read_text(encoding="utf-8"))
        self.assertIn("concurrency", verify_autos)

        for name in ("analytics-validation.yml", "codeql.yml"):
            data = parse_workflow_yaml((WORKFLOWS / name).read_text(encoding="utf-8"))
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
        build_dist_yaml = parse_workflow_yaml(build_dist)
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

    def test_non_starter_wrappers_pin_sha256_and_analytics_has_toolchain_17(self):
        # ARES-FTC, ARES-FTC-Starter, ARES-FRC-Starter wrappers feed release/starter-artifacts.properties
        # and are deferred to the next coordinated starter/Studio release bump; ARES-Analytics and ARES-FRC pin now.
        expected = {
            "ARES-Analytics": "6f74b601422d6d6fc4e1f9a1ab6522f642c2fdcbc15ae33ebd30ba3d7198e854",
            "ARES-FRC": "6f74b601422d6d6fc4e1f9a1ab6522f642c2fdcbc15ae33ebd30ba3d7198e854",
        }
        for product, digest in expected.items():
            wrapper = (ROOT / product / "gradle" / "wrapper" / "gradle-wrapper.properties").read_text(encoding="utf-8")
            self.assertIn(f"distributionSha256Sum={digest}", wrapper, product)
        analytics_wrapper = (ROOT / "ARES-Analytics" / "gradle" / "wrapper" / "gradle-wrapper.properties").read_text(encoding="utf-8")
        self.assertIn("networkTimeout=10000", analytics_wrapper)
        self.assertIn("validateDistributionUrl=true", analytics_wrapper)

        analytics_build = (ROOT / "ARES-Analytics" / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn("jvmToolchain(17)", analytics_build)

    def test_frc_starter_namespace_policy_and_setup_sh_checks(self):
        policy_script = (ROOT / "scripts" / "verify-monorepo-policy.ps1").read_text(encoding="utf-8")
        self.assertIn("ARES-FRC-Starter/src/main/", policy_script)

        setup_sh = (ROOT / "setup.sh").read_text(encoding="utf-8")
        for token in ("verify_agent_guidance.py", "verify_kotlin_toolchain.py", "generate_codebase_ledger.py", "--verify"):
            self.assertIn(token, setup_sh)


if __name__ == "__main__":
    unittest.main()
