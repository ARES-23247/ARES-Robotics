#!/usr/bin/env python3
"""Classify changed monorepo paths into the smallest safe CI product matrix."""

from __future__ import annotations

import argparse
import os
import re
import subprocess
from pathlib import Path
from typing import Iterable


OUTPUT_KEYS = (
    "full", "lib", "ftc", "frc", "ftc_starter", "frc_starter", "xrp_starter",
    "analytics", "analytics_app", "analytics_shared", "analytics_gateway",
    "dashboard", "autos", "packages", "jvm",
)
REVIEW_EVENTS = {"pull_request", "merge_group"}


def classify_paths(paths: Iterable[str], event_name: str = "pull_request") -> dict[str, bool]:
    result = {key: event_name not in REVIEW_EVENTS for key in OUTPUT_KEYS}
    if event_name not in REVIEW_EVENTS:
        return result

    for raw_path in paths:
        path = raw_path.replace("\\", "/")
        if not path:
            continue
        if path.startswith("ARESLib-Kotlin/"):
            result["full"] = True
            result["lib"] = True
        elif path.startswith("ARES-FTC/"):
            result["ftc"] = True
        elif path.startswith("ARES-FRC/"):
            result["frc"] = True
        elif path.startswith("ARES-FTC-Starter/"):
            result["ftc_starter"] = True
        elif path.startswith("ARES-FRC-Starter/"):
            result["frc_starter"] = True
        elif path.startswith("ARES-XRP-Starter/"):
            result["xrp_starter"] = True
        elif path.startswith("ARES-Analytics/app/"):
            result["analytics_app"] = True
        elif path.startswith("ARES-Analytics/gateway/"):
            result["analytics_gateway"] = True
        elif path.startswith("ARES-Analytics/shared/"):
            result["analytics_shared"] = True
        elif path.startswith("ARES-Analytics/docs/"):
            continue
        elif path.startswith("ARES-Analytics/"):
            # Root Gradle/settings/scripts/resources can affect every Studio module.
            result["analytics_shared"] = True
        elif _requires_full_matrix(path):
            result["full"] = True
        elif _is_policy_only(path):
            continue
        else:
            # Unknown root-level build or policy inputs receive the conservative full matrix.
            result["full"] = True

    if result["full"]:
        for key in OUTPUT_KEYS:
            if key == "lib":
                continue  # Distinguish changed library source from a shared-input full run.
            result[key] = True
        return result

    if result["analytics_shared"]:
        result["analytics_app"] = result["analytics_gateway"] = True
    # Studio owns starter import/generation and embeds all starter archives plus Lightbot.
    if any(result[key] for key in ("ftc_starter", "frc_starter", "xrp_starter")):
        result["analytics_app"] = True
    result["analytics"] = any(result[key] for key in (
        "analytics_app", "analytics_shared", "analytics_gateway",
    ))
    result["dashboard"] = result["analytics_app"]
    result["autos"] = result["ftc"] or result["frc"] or result["analytics_app"]
    result["packages"] = result["analytics_app"] or result["ftc"]
    result["jvm"] = any(result[key] for key in (
        "lib", "ftc", "frc", "ftc_starter", "frc_starter", "analytics",
    ))
    return result


def _requires_full_matrix(path: str) -> bool:
    full_prefixes = ("release/", "build-logic/", "templates/", "scripts/", ".github/workflows/")
    full_files = {"build.ps1", "setup.ps1", "setup.sh", "verify-autos.ps1", "verify-autos.sh"}
    return path.startswith(full_prefixes) or path in full_files


def _is_policy_only(path: str) -> bool:
    return (
        path == "AGENTS.md"
        or ("/" not in path and path.endswith(".md"))
        or path.startswith(".agents/")
        or path.startswith("docs/")
        or path == ".github/dependabot.yml"
        or path in {".gitignore", ".gitattributes"}
    )


def _git_changed_paths(base_sha: str, head_sha: str) -> list[str]:
    if not base_sha or not head_sha:
        raise ValueError("Review classification requires both base and head SHAs")
    if not all(re.fullmatch(r"[0-9a-fA-F]{40,64}", sha) for sha in (base_sha, head_sha)):
        raise ValueError("Expected full hexadecimal Git object IDs")
    completed = subprocess.run(
        # Both sides of a rename must be classified, including moves between products.
        # NUL separators avoid Git's quoted-path output and preserve newlines in filenames.
        ["git", "diff", "--name-only", "--no-renames", "-z", base_sha, head_sha, "--"],
        check=True,
        capture_output=True,
    )
    return [path.decode("utf-8", errors="surrogateescape")
            for path in completed.stdout.split(b"\0") if path]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--event-name", required=True)
    parser.add_argument("--base-sha", default="")
    parser.add_argument("--head-sha", default="")
    parser.add_argument("--github-output", required=True, type=Path)
    parser.add_argument("--github-summary", type=Path)
    args = parser.parse_args()

    paths = [] if args.event_name not in REVIEW_EVENTS else _git_changed_paths(args.base_sha, args.head_sha)
    result = classify_paths(paths, event_name=args.event_name)
    with args.github_output.open("a", encoding="utf-8", newline="\n") as output:
        for key in OUTPUT_KEYS:
            output.write(f"{key}={'true' if result[key] else 'false'}\n")
    selected = ", ".join(key for key, enabled in result.items() if enabled) or "policy only"
    summary = (
        f"### CI test scopes\n\nEvent: `{args.event_name}`; changed paths: {len(paths)}.\n\n"
        f"Selected: {selected}.\n\n"
        "Manual and scheduled runs select all scopes. Library/shared build inputs select all "
        "dependent products. See `docs/ci-test-scopes.md` for the dependency rules.\n"
    )
    print(summary)
    summary_path = args.github_summary or os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with Path(summary_path).open("a", encoding="utf-8") as output:
            output.write(summary)


if __name__ == "__main__":
    main()
