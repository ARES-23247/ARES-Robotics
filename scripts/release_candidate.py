#!/usr/bin/env python3
"""Seal and verify immutable ARES release-candidate directories.

The manifest deliberately hashes every regular file except itself. Promotion jobs
must also verify the GitHub artifact attestation for the enclosing archive.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import subprocess
import tarfile
from typing import Any


SCHEMA_VERSION = 1
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
TREE_PATTERN = re.compile(r"^[0-9a-f]{40}$")
VERSION_PATTERN = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")


class CandidateError(ValueError):
    """Raised when release-candidate provenance is incomplete or inconsistent."""


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _candidate_files(root: Path, manifest: Path) -> list[dict[str, Any]]:
    manifest_resolved = manifest.resolve()
    files: list[dict[str, Any]] = []
    for path in sorted(root.rglob("*"), key=lambda candidate: candidate.as_posix()):
        if not path.is_file() or path.resolve() == manifest_resolved:
            continue
        relative = path.relative_to(root).as_posix()
        files.append({
            "path": relative,
            "size": path.stat().st_size,
            "sha256": _sha256(path),
        })
    return files


def _require_text(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise CandidateError(f"{label} must be a non-empty string")
    return value


def _validate_identity(manifest: dict[str, Any]) -> None:
    if manifest.get("schemaVersion") != SCHEMA_VERSION:
        raise CandidateError(f"unsupported candidate schema {manifest.get('schemaVersion')!r}")

    source = manifest.get("source")
    versions = manifest.get("versions")
    if not isinstance(source, dict) or not isinstance(versions, dict):
        raise CandidateError("candidate source and versions objects are required")

    tree = _require_text(source.get("tree"), "source.tree")
    if not TREE_PATTERN.fullmatch(tree):
        raise CandidateError("source.tree must be a lowercase 40-character Git tree ID")

    commit = _require_text(source.get("commit"), "source.commit")
    if not TREE_PATTERN.fullmatch(commit):
        raise CandidateError("source.commit must be a lowercase 40-character Git commit ID")

    run_id = source.get("runId")
    run_attempt = source.get("runAttempt")
    if not isinstance(run_id, int) or run_id <= 0:
        raise CandidateError("source.runId must be a positive integer")
    if not isinstance(run_attempt, int) or run_attempt <= 0:
        raise CandidateError("source.runAttempt must be a positive integer")

    event = _require_text(source.get("event"), "source.event")
    if event not in {"pull_request", "merge_group", "workflow_dispatch"}:
        raise CandidateError(f"unsupported candidate event {event!r}")
    _require_text(source.get("repository"), "source.repository")
    _require_text(source.get("workflowRef"), "source.workflowRef")

    for key in ("ares", "studio"):
        version = _require_text(versions.get(key), f"versions.{key}")
        if not VERSION_PATTERN.fullmatch(version):
            raise CandidateError(f"versions.{key} must use MAJOR.MINOR.PATCH")


def seal(args: argparse.Namespace) -> None:
    root = args.root.resolve()
    manifest_path = args.manifest.resolve()
    if not root.is_dir():
        raise CandidateError(f"candidate root does not exist: {root}")
    if root not in manifest_path.parents:
        raise CandidateError("manifest must be inside the candidate root")
    if manifest_path.exists():
        manifest_path.unlink()

    manifest = {
        "schemaVersion": SCHEMA_VERSION,
        "source": {
            "repository": args.repository,
            "tree": args.source_tree,
            "commit": args.source_commit,
            "event": args.event,
            "workflowRef": args.workflow_ref,
            "runId": args.run_id,
            "runAttempt": args.run_attempt,
        },
        "versions": {
            "ares": args.ares_version,
            "studio": args.studio_version,
        },
        "files": _candidate_files(root, manifest_path),
    }
    _validate_identity(manifest)
    if not manifest["files"]:
        raise CandidateError("candidate contains no promotable files")
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def verify(args: argparse.Namespace) -> dict[str, Any]:
    root = args.root.resolve()
    manifest_path = args.manifest.resolve()
    if not root.is_dir() or not manifest_path.is_file():
        raise CandidateError("candidate root and manifest must exist")
    if root not in manifest_path.parents:
        raise CandidateError("manifest must be inside the candidate root")

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if not isinstance(manifest, dict):
        raise CandidateError("candidate manifest must contain a JSON object")
    _validate_identity(manifest)

    source = manifest["source"]
    versions = manifest["versions"]
    expectations = {
        "repository": (source["repository"], args.expected_repository),
        "tree": (source["tree"], args.expected_tree),
        "event": (source["event"], args.expected_event),
        "run ID": (str(source["runId"]), str(args.expected_run_id) if args.expected_run_id else None),
        "run attempt": (
            str(source["runAttempt"]),
            str(args.expected_run_attempt) if args.expected_run_attempt else None,
        ),
        "ARES version": (versions["ares"], args.expected_ares_version),
        "Studio version": (versions["studio"], args.expected_studio_version),
    }
    for label, (actual, expected) in expectations.items():
        if expected is not None and actual != expected:
            raise CandidateError(f"{label} mismatch: expected {expected!r}, found {actual!r}")

    declared = manifest.get("files")
    if not isinstance(declared, list) or not declared:
        raise CandidateError("candidate manifest must declare at least one file")
    declared_paths: set[str] = set()
    for index, entry in enumerate(declared):
        if not isinstance(entry, dict):
            raise CandidateError(f"files[{index}] must be an object")
        path = entry.get("path")
        size = entry.get("size")
        digest = entry.get("sha256")
        if not isinstance(path, str) or not path or Path(path).is_absolute() or ".." in Path(path).parts:
            raise CandidateError(f"files[{index}].path is unsafe")
        if path in declared_paths:
            raise CandidateError(f"duplicate candidate path: {path}")
        declared_paths.add(path)
        if not isinstance(size, int) or size < 0:
            raise CandidateError(f"files[{index}].size must be a non-negative integer")
        if not isinstance(digest, str) or not SHA256_PATTERN.fullmatch(digest):
            raise CandidateError(f"files[{index}].sha256 is invalid")
    actual_files = _candidate_files(root, manifest_path)
    if declared != actual_files:
        declared_by_path = {entry.get("path"): entry for entry in declared if isinstance(entry, dict)}
        actual_by_path = {entry["path"]: entry for entry in actual_files}
        missing = sorted(set(declared_by_path) - set(actual_by_path))
        extra = sorted(set(actual_by_path) - set(declared_by_path))
        changed = sorted(
            path for path in set(declared_by_path) & set(actual_by_path)
            if declared_by_path[path] != actual_by_path[path]
        )
        raise CandidateError(
            f"candidate file manifest mismatch; missing={missing}, extra={extra}, changed={changed}"
        )
    return manifest


def verify_provenance(args: argparse.Namespace) -> None:
    """Bind manifest claims to certificate identity, then resolve that verified commit's tree.

    GitHub CLI's source/signer constraints check certificate fields, not the
    workflow-controlled SLSA predicate. No candidate-provided code is executed.
    """
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    _validate_identity(manifest)
    source = manifest["source"]
    workflow = args.expected_repository + "/.github/workflows/build-distributions.yml"
    if source["repository"] != args.expected_repository or not source["workflowRef"].startswith(workflow + "@refs/"):
        raise CandidateError("candidate workflow identity is not the trusted builder")
    commit = source["commit"]
    subprocess.run([
        "gh", "attestation", "verify", str(args.archive),
        "--repo", args.expected_repository,
        "--signer-workflow", workflow,
        "--source-digest", commit,
        "--signer-digest", commit,
        "--cert-identity", "https://github.com/" + source["workflowRef"],
        "--deny-self-hosted-runners",
    ], check=True)
    # A PR merge SHA can differ from the eventual protected merge SHA; trees
    # must still match exactly. Fetch by the now-verified digest, never a branch.
    subprocess.run(["git", "fetch", "--no-tags", "origin", commit], check=True)
    tree = subprocess.run(["git", "rev-parse", commit + "^{tree}"],
                          check=True, capture_output=True, text=True).stdout.strip()
    if tree != source["tree"] or tree != args.expected_tree:
        raise CandidateError("attested source Git tree does not match the manifest and protected main")


def extract(args: argparse.Namespace) -> None:
    archive = args.archive.resolve()
    root = args.root.resolve()
    if not archive.is_file():
        raise CandidateError(f"candidate archive does not exist: {archive}")
    root.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, mode="r:gz") as bundle:
        members = bundle.getmembers()
        if not members:
            raise CandidateError("candidate archive is empty")
        for member in members:
            destination = (root / member.name).resolve()
            if destination != root and root not in destination.parents:
                raise CandidateError(f"candidate archive path escapes extraction root: {member.name}")
            if not (member.isdir() or member.isfile()):
                raise CandidateError(f"candidate archive contains a non-file entry: {member.name}")
        bundle.extractall(root, members=members, filter="data")


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    seal_parser = subparsers.add_parser("seal")
    seal_parser.add_argument("--root", type=Path, required=True)
    seal_parser.add_argument("--manifest", type=Path, required=True)
    seal_parser.add_argument("--repository", required=True)
    seal_parser.add_argument("--source-tree", required=True)
    seal_parser.add_argument("--source-commit", required=True)
    seal_parser.add_argument("--event", required=True)
    seal_parser.add_argument("--workflow-ref", required=True)
    seal_parser.add_argument("--run-id", type=int, required=True)
    seal_parser.add_argument("--run-attempt", type=int, required=True)
    seal_parser.add_argument("--ares-version", required=True)
    seal_parser.add_argument("--studio-version", required=True)

    verify_parser = subparsers.add_parser("verify")
    verify_parser.add_argument("--root", type=Path, required=True)
    verify_parser.add_argument("--manifest", type=Path, required=True)
    verify_parser.add_argument("--expected-repository")
    verify_parser.add_argument("--expected-tree")
    verify_parser.add_argument("--expected-event")
    verify_parser.add_argument("--expected-run-id", type=int)
    verify_parser.add_argument("--expected-run-attempt", type=int)
    verify_parser.add_argument("--expected-ares-version")
    verify_parser.add_argument("--expected-studio-version")

    provenance_parser = subparsers.add_parser("verify-provenance")
    provenance_parser.add_argument("--archive", type=Path, required=True)
    provenance_parser.add_argument("--manifest", type=Path, required=True)
    provenance_parser.add_argument("--expected-repository", required=True)
    provenance_parser.add_argument("--expected-tree", required=True)

    extract_parser = subparsers.add_parser("extract")
    extract_parser.add_argument("--archive", type=Path, required=True)
    extract_parser.add_argument("--root", type=Path, required=True)
    return parser


def main() -> int:
    args = _parser().parse_args()
    try:
        if args.command == "seal":
            seal(args)
        elif args.command == "verify-provenance":
            verify_provenance(args)
        elif args.command == "verify":
            manifest = verify(args)
            print(json.dumps(manifest, separators=(",", ":"), sort_keys=True))
        else:
            extract(args)
        return 0
    except (CandidateError, json.JSONDecodeError, OSError, subprocess.CalledProcessError) as error:
        print(f"release candidate rejected: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
