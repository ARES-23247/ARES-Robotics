"""Fail a stable workflow check when classification or an affected job fails."""

import json
import os


def unsuccessful_jobs(needs: dict) -> list[str]:
    failures = [name for name, job in needs.items()
                if job.get("result") not in {"success", "skipped"}]
    if needs.get("changes", {}).get("result") != "success" and "changes" not in failures:
        failures.append("changes")
    return failures


if __name__ == "__main__":
    needs = json.loads(os.environ["NEEDS_JSON"])
    failures = unsuccessful_jobs(needs)
    if failures:
        raise SystemExit("CI failed or was cancelled: " + ", ".join(failures))
    print("All selected scopes passed; unaffected jobs were skipped.")
