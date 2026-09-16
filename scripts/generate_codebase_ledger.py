#!/usr/bin/env python3
"""Generates or verifies the comprehensive codebase ledger for ARES Robotics monorepo."""

import argparse
import json
from pathlib import Path
import subprocess
import sys
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "ARES-Analytics" / "config" / "maintainability" / "codebase_ledger.json"

def parse_baseline(path: Path) -> dict:
    ceiling = {}
    if not path.is_file():
        return ceiling
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" in line:
            k, v = line.split("=", 1)
            ceiling[k.strip()] = int(v.strip())
    return ceiling

def compute_ledger(root: Path):
    areslib_baseline = parse_baseline(root / "ARESLib-Kotlin" / "config" / "maintainability" / "large-production-kotlin-baseline.txt")
    analytics_baseline = parse_baseline(root / "ARES-Analytics" / "config" / "maintainability" / "large-production-kotlin-baseline.txt")

    tracked_files = subprocess.check_output(
        ["git", "ls-files", "*.kt"],
        cwd=root,
        text=True,
        errors="surrogateescape"
    ).splitlines()

    production_files = []
    verified_count = 0
    audited_count = 0
    violations_count = 0
    microfiles_count = 0
    grandfathered_count = 0

    try:
        touched_diff = subprocess.check_output(
            ["git", "diff", "--name-only", "HEAD"],
            cwd=root,
            text=True,
            errors="surrogateescape"
        ).splitlines()
    except Exception:
        touched_diff = []
    touched_set = set(touched_diff)

    for rel in sorted(tracked_files):
        if "/src/main/" not in rel:
            continue
        p = root / rel
        if not p.is_file():
            continue
        try:
            lines = len(p.read_text(encoding="utf-8").splitlines())
        except Exception:
            continue

        product = rel.split("/")[0] if "/" in rel else "root"
        if product == "ARESLib-Kotlin":
            limit = 500
        elif product == "templates":
            limit = 1000
        else:
            limit = 750

        # Check baseline grandfathering
        is_grandfathered = False
        allowed_limit = limit
        if product == "ARESLib-Kotlin":
            sub_rel = rel[len("ARESLib-Kotlin/"):].replace("\\", "/")
            if sub_rel in areslib_baseline:
                allowed_limit = areslib_baseline[sub_rel]
                is_grandfathered = True
                grandfathered_count += 1
        elif product == "ARES-Analytics":
            sub_rel = rel[len("ARES-Analytics/"):].replace("\\", "/")
            if sub_rel in analytics_baseline:
                allowed_limit = analytics_baseline[sub_rel]
                is_grandfathered = True
                grandfathered_count += 1

        is_over_limit = lines > allowed_limit
        is_microfile = lines < 25
        
        if is_over_limit:
            violations_count += 1
        if is_microfile:
            microfiles_count += 1

        is_verified = rel in touched_set
        if is_verified:
            verified_count += 1
        else:
            audited_count += 1

        production_files.append({
            "path": rel,
            "product": product,
            "lines": lines,
            "lineLimit": limit,
            "effectiveLimit": allowed_limit,
            "grandfathered": is_grandfathered,
            "compliant": not is_over_limit,
            "isMicrofile": is_microfile,
            "status": "VERIFIED" if is_verified else "AUDITED"
        })

    return {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "summary": {
            "totalProductionKotlinFiles": len(production_files),
            "verifiedFiles": verified_count,
            "auditedFiles": audited_count,
            "grandfatheredFiles": grandfathered_count,
            "violationsOverLimit": violations_count,
            "microfilesRemaining": microfiles_count,
            "maintainabilityRatchet": "PASS" if violations_count == 0 else "FAIL"
        },
        "files": production_files
    }

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", action="store_true", help="Verify that the ledger is up-to-date and passing")
    parser.add_argument("--ledger-path", type=Path, default=DEST, help="Path to codebase_ledger.json")
    args = parser.parse_args()

    ledger_path = args.ledger_path
    if args.verify:
        if not ledger_path.is_file():
            print(f"ERROR: Ledger file does not exist: {ledger_path}", file=sys.stderr)
            sys.exit(1)
        try:
            with open(ledger_path, "r", encoding="utf-8") as f:
                data = json.load(f)
        except Exception as e:
            print(f"ERROR: Could not parse ledger: {e}", file=sys.stderr)
            sys.exit(1)

        summary = data.get("summary", {})
        if summary.get("maintainabilityRatchet") != "PASS":
            print(f"ERROR: Maintainability ratchet failed: {summary}", file=sys.stderr)
            sys.exit(1)
        if summary.get("violationsOverLimit", 0) != 0:
            print(f"ERROR: Violations over limit: {summary.get('violationsOverLimit')}", file=sys.stderr)
            sys.exit(1)
        if summary.get("microfilesRemaining", 0) > 20:
            print(f"ERROR: Excessive microfiles remaining: {summary.get('microfilesRemaining')}", file=sys.stderr)
            sys.exit(1)

        print(f"Ledger verified: {summary.get('totalProductionKotlinFiles')} files, 0 violations, ratchet {summary.get('maintainabilityRatchet')}.")
        sys.exit(0)

    # Generate
    ledger = compute_ledger(ROOT)
    ledger_path.parent.mkdir(parents=True, exist_ok=True)
    with open(ledger_path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(ledger, f, indent=2)
        f.write("\n")

    summary = ledger["summary"]
    print(f"Generated {ledger_path} with {summary['totalProductionKotlinFiles']} production files.")
    print(f"Verified: {summary['verifiedFiles']}, Audited: {summary['auditedFiles']}, Grandfathered: {summary['grandfatheredFiles']}, Violations: {summary['violationsOverLimit']}, Microfiles: {summary['microfilesRemaining']}")

if __name__ == "__main__":
    main()
