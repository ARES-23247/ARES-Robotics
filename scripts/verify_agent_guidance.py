"""Check that a fresh checkout carries one portable set of agent instructions."""

from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
ROOT_LIMIT = 8 * 1024
SKILLS = ("ares-workspace", "ares-build-release", "ares-subsystem-authoring", "compose-desktop-tester")
ADAPTERS = {"GEMINI.md": "@./AGENTS.md", ".agents/rules/ares-workspace.md": "@../../AGENTS.md"}


def git(root, *args):
    result = subprocess.run(["git", "-C", str(root), *args], capture_output=True,
                            text=True, encoding="utf-8", check=False)
    if result.returncode not in (0, 1):
        raise RuntimeError(result.stderr.strip())
    return result.stdout


def check(root):
    root = root.resolve()
    errors = []
    required = {"AGENTS.md", *ADAPTERS, "docs/agents/README.md", "docs/agents/WORKSPACE_GUIDE.md",
                "ARESLib-Kotlin/GEMINI.md", "ARES-FTC-Starter/AGENTS.md", "ARES-FRC-Starter/AGENTS.md"}
    required.update(f".agents/skills/{name}/SKILL.md" for name in SKILLS)
    tracked = set(git(root, "ls-files", "-z").split("\0")) - {""}
    # Include untracked assets so forgetting to stage a skill reference fails locally.
    for folder in (".agents/skills", ".agents/rules", "docs/agents"):
        required.update(p.relative_to(root).as_posix() for p in (root / folder).rglob("*")
                        if p.is_file() and "__pycache__" not in p.parts and p.suffix != ".pyc")
    for name in sorted(required):
        path = root / name
        if not path.is_file():
            errors.append(f"Missing guidance: {name}")
        if name not in tracked:
            errors.append(f"Untracked guidance (stage it): {name}")
    ignored = git(root, "check-ignore", "--no-index", "--", *sorted(required))
    errors.extend(f"Ignored guidance: {name}" for name in ignored.splitlines())
    guide = root / "AGENTS.md"
    if guide.is_file() and guide.stat().st_size > ROOT_LIMIT:
        errors.append(f"AGENTS.md exceeds {ROOT_LIMIT} bytes; move details to the engineering guide")
    for name, expected in ADAPTERS.items():
        path = root / name
        if not path.is_file():
            continue
        content = path.read_text(encoding="utf-8")
        imports = re.findall(r"^@[^\s]+$", content, re.MULTILINE)
        if imports != [expected]:
            errors.append(f"Adapter must import only {expected}: {name}")
        if name.startswith(".agents/") and not content.startswith("---\ntrigger: always_on\n---\n"):
            errors.append(f"Antigravity rule must be Always On: {name}")
        if len(content.encode("utf-8")) > 1024:
            errors.append(f"Adapter contains too much policy; use AGENTS.md: {name}")
    for name in sorted(required):
        path = root / name
        if not path.is_file() or path.suffix != ".md":
            continue
        content = path.read_text(encoding="utf-8")
        if re.search(r"(?:[A-Z]:[/\\]Users[/\\][^/\\\s]+|/home/[^/\s]+)", content):
            errors.append(f"Machine-specific home path: {name}")
        fenced = False
        for line in content.splitlines():
            if re.match(r"\s*(```|~~~)", line):
                fenced = not fenced
                continue
            if fenced:
                continue
            line = re.sub(r"`[^`]*`", "", line)
            for target in re.findall(r"\[[^\]]*\]\(([^)]+)\)", line):
                target = target.strip().strip("<>")
                if urlsplit(target).scheme or target.startswith("#"):
                    continue
                target = unquote(target.split("#", 1)[0])
                resolved = (path.parent / target).resolve()
                if not resolved.is_relative_to(root) or not resolved.exists():
                    errors.append(f"Missing/outside-repo link in {name}: {target}")
    return errors


def main():
    errors = check(ROOT)
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("Shared agent guidance verified: tracked files, ignore rules, adapters, size, and links.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
