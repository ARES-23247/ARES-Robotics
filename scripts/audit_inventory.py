#!/usr/bin/env python3
"""Account for every tracked file without confusing test execution with review coverage."""

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import subprocess


ROOT = Path(__file__).resolve().parents[1]


def fingerprint(path):
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


def inventory(root, paths, records):
    files = []
    for path in sorted(set(paths)):
        file = root / path
        digest = fingerprint(file) if file.is_file() else None
        record = records.get(path, {})
        current = digest is not None and record.get("sha256") == digest
        review = record.get("review", "pending") if current else ("stale" if record else "pending")
        validation = record.get("validation", "pending") if current else "pending"
        # Both dimensions need explicit evidence. A suite pass alone never closes review.
        complete = (current and review == "reviewed" and validation in {"passed", "not-applicable"}
                    and bool(record.get("evidence")) and bool(record.get("scope")))
        files.append({
            "path": path, "sha256": digest, "category": category(path),
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--records", type=Path, default=ROOT / "docs/audits/file-reviews.json")
    parser.add_argument("--output", type=Path, default=ROOT / ".codex-validation/audit-inventory.json")
    args = parser.parse_args()
    paths = [path for path in subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode("utf-8").split("\0") if path]
    records = json.loads(args.records.read_text(encoding="utf-8"))["files"]
    result = inventory(ROOT, paths, records)
    result["head"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    result["workingTreeStatus"] = subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "files"}, indent=2))


if __name__ == "__main__":
    main()
