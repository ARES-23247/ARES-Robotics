#!/usr/bin/env python3
"""Account for every tracked file without confusing test execution with review coverage."""

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
LEDGER_PATH = "docs/audits/file-reviews.json"


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate ledger JSON key: {key!r}")
        result[key] = value
    return result


def _invalid_constant(value):
    raise ValueError(f"Non-JSON numeric constant in ledger: {value}")


def _validate_path(path):
    if (not isinstance(path, str) or not path or path == "." or "\0" in path or
            PurePosixPath(path).is_absolute() or str(PurePosixPath(path)) != path or
            ".." in PurePosixPath(path).parts or
            (os.name == "nt" and ("\\" in path or ":" in path))):
        raise ValueError(f"Expected a repository-relative Git file path: {path!r}")


def validate_records(records):
    if not isinstance(records, dict):
        raise ValueError("Ledger files must be an object")
    for path, record in records.items():
        _validate_path(path)
        if not isinstance(record, dict):
            raise ValueError(f"Ledger record must be an object: {path}")
        digest = record.get("sha256")
        if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise ValueError(f"Invalid SHA-256 in ledger record: {path}")
        if record.get("review") not in ("pending", "partial", "reviewed"):
            raise ValueError(f"Invalid review status: {path}")
        if record.get("validation") not in ("pending", "passed", "failed", "not-applicable"):
            raise ValueError(f"Invalid validation status: {path}")
        if not isinstance(record.get("scope"), str):
            raise ValueError(f"Scope must be a string: {path}")
        evidence = record.get("evidence")
        if not isinstance(evidence, list) or not all(isinstance(item, str) for item in evidence):
            raise ValueError(f"Evidence must be a list of strings: {path}")


def load_ledger(path):
    document = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_unique_object,
                          parse_constant=_invalid_constant)
    if (not isinstance(document, dict) or type(document.get("schemaVersion")) is not int or
            document["schemaVersion"] != 1):
        raise ValueError("Unsupported audit ledger schemaVersion")
    validate_records(document.get("files"))
    return document


def ledger_fingerprint(document, ledger_key):
    """Bind every semantic value except the self-record's SHA field; do not mutate the input."""
    canonical = dict(document)
    canonical["files"] = dict(document["files"])
    if ledger_key in canonical["files"]:
        own_record = dict(canonical["files"][ledger_key])
        own_record.pop("sha256", None)
        canonical["files"][ledger_key] = own_record
    encoded = json.dumps(canonical, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("utf-8")
    return hashlib.sha256(b"ARES audit ledger JSON v1\n" + encoded).hexdigest()


def fingerprint(path, *, ledger_key=None):
    # A tracked symlink owns its link text, not the contents or existence of its target.
    if path.is_symlink():
        return hashlib.sha256(os.fsencode(os.readlink(path))).hexdigest()
    if ledger_key is not None:
        return ledger_fingerprint(load_ledger(path), ledger_key)
    # Git text files may be checked out with CRLF; normalize only text line endings.
    data = path.read_bytes()
    try:
        data.decode("utf-8")
        text = b"\0" not in data
    except UnicodeDecodeError:
        text = False
    if text:
        data = data.replace(b"\r\n", b"\n")
    return hashlib.sha256(data).hexdigest()


def category(path):
    name = Path(path).name.lower()
    suffix = Path(path).suffix.lower()
    if suffix in {".md", ".txt", ".adoc"}:
        return "documentation"
    if suffix in {".png", ".jpg", ".jpeg", ".gif", ".ico", ".icns", ".jar", ".zip", ".pdf", ".ttf", ".woff2"}:
        return "asset-or-binary"
    if "/src/test/" in path or "/tests/" in path or name.startswith("test_"):
        return "test"
    if name in {"gradlew", "gradlew.bat", "ares", "ares.bat"} or suffix in {".gradle", ".kts", ".ps1", ".sh", ".bat"}:
        return "build-or-tooling"
    if suffix in {".kt", ".java", ".py", ".js", ".ts", ".cpp", ".h"}:
        return "source"
    return "configuration-or-resource"


def inventory(root, paths, records, *, records_path=None):
    validate_records(records)
    root = Path(os.path.abspath(root))
    ledger = Path(os.path.abspath(records_path)) if records_path is not None else root / LEDGER_PATH
    paths = sorted(set(paths))  # Reuse materialized input, including when callers supply a generator.
    for path in paths:
        _validate_path(path)
    files = []
    for path in paths:
        file = root / path
        is_ledger = file == ledger and not file.is_symlink()
        digest = fingerprint(file, ledger_key=path if is_ledger else None) if file.is_symlink() or file.is_file() else None
        record = records.get(path, {})
        current = digest is not None and record.get("sha256") == digest
        review = record.get("review", "pending") if current else ("stale" if record else "pending")
        validation = record.get("validation", "pending") if current else "pending"
        # Both dimensions need explicit evidence. A suite pass alone never closes review.
        complete = (current and review == "reviewed" and validation in {"passed", "not-applicable"}
                    and bool(record.get("scope", "").strip()) and bool(record.get("evidence"))
                    and all(item.strip() for item in record["evidence"]))
        files.append({
            "path": path, "sha256": digest, "category": category(path),
            "fingerprintKind": "ledger-json-v1" if is_ledger else "content-sha256-v1",
            "product": path.split("/")[0] if "/" in path else "workspace",
            "review": review, "validation": validation, "complete": bool(complete),
            "record": record or None,
        })
    return {
        "schemaVersion": 1,
        "total": len(files),
        "complete": sum(file["complete"] for file in files),
        "reviewCounts": dict(sorted(Counter(file["review"] for file in files).items())),
        "categoryCounts": dict(sorted(Counter(file["category"] for file in files).items())),
        "orphanedRecords": sorted(set(records) - set(paths)),
        "files": files,
    }


def write_json(path, value):
    """Replace a complete JSON file atomically; failures preserve the previous destination."""
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", newline="\n", dir=path.parent,
                                         prefix=path.name + ".", suffix=".tmp", delete=False) as output:
            temporary = Path(output.name)
            json.dump(value, output, indent=2)
            output.write("\n")
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--records", type=Path, default=ROOT / LEDGER_PATH)
    parser.add_argument("--output", type=Path, default=ROOT / ".codex-validation/audit-inventory.json")
    parser.add_argument("--refresh-ledger-fingerprint", action="store_true",
                        help="Refresh only an existing ledger self-record hash after reviewing ledger changes")
    args = parser.parse_args()
    if args.output.resolve() == args.records.resolve():
        raise ValueError("Inventory output must not overwrite its review ledger input")
    paths = [path for path in subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT)
             .decode("utf-8", errors="surrogateescape").split("\0") if path]
    document = load_ledger(args.records)
    if args.refresh_ledger_fingerprint:
        if args.records.is_symlink():
            raise ValueError("Cannot refresh a symlinked ledger")
        ledger = Path(os.path.abspath(args.records))
        key = next((path for path in paths if ROOT / path == ledger), None)
        if key is None or key not in document["files"]:
            raise ValueError("Refresh requires a tracked ledger with an existing self-review record")
        document["files"][key]["sha256"] = ledger_fingerprint(document, key)
        write_json(args.records, document)
    result = inventory(ROOT, paths, document["files"], records_path=args.records)
    result["head"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    result["workingTreeStatus"] = subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True)
    write_json(args.output, result)
    print(json.dumps({key: value for key, value in result.items() if key != "files"}, indent=2))


if __name__ == "__main__":
    main()
