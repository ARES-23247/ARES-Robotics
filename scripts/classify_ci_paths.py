#!/usr/bin/env python3
"""Classify changed monorepo paths into the smallest safe CI product matrix."""

from __future__ import annotations

import argparse
import subprocess
from pathlib import Path
from typing import Iterable


OUTPUT_KEYS = ("full", "lib", "ftc", "frc", "ftc_starter", "frc_starter", "xrp_starter", "analytics")


def classify_paths(paths: Iterable[str], event_name: str = "pull_request") -> dict[str, bool]:
    result = {key: event_name != "pull_request" for key in OUTPUT_KEYS}
    if event_name != "pull_request":
        return result

    for raw_path in paths:
        path = raw_path.strip().replace("\\", "/")
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
        elif path.startswith("ARES-Analytics/"):
            result["analytics"] = True
        elif _requires_full_matrix(path):
            result["full"] = True
        elif _is_policy_only(path):
            continue
        else:
            # Unknown root-level build or policy inputs receive the conservative full matrix.
            result["full"] = True

    if result["full"]:
        for key in ("ftc", "frc", "ftc_starter", "frc_starter", "xrp_starter", "analytics"):
            result[key] = True
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
        or path == ".github/dependabot.yml"
        or path in {".gitignore", ".gitattributes"}
    )


def _git_changed_paths(base_sha: str, head_sha: str) -> list[str]:
    if not base_sha or not head_sha:
        raise ValueError("Pull-request classification requires both base and head SHAs")
    completed = subprocess.run(
        ["git", "diff", "--name-only", base_sha, head_sha],
        check=True,
        capture_output=True,
        text=True,
    )
    return completed.stdout.splitlines()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--event-name", required=True)
    parser.add_argument("--base-sha", default="")
    parser.add_argument("--head-sha", default="")
    parser.add_argument("--github-output", required=True, type=Path)
    args = parser.parse_args()

    paths = [] if args.event_name != "pull_request" else _git_changed_paths(args.base_sha, args.head_sha)
    result = classify_paths(paths, event_name=args.event_name)
    with args.github_output.open("a", encoding="utf-8", newline="\n") as output:
        for key in OUTPUT_KEYS:
            output.write(f"{key}={'true' if result[key] else 'false'}\n")


if __name__ == "__main__":
    main()
