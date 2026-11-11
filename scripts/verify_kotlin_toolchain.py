#!/usr/bin/env python3
"""Verify the Kotlin compiler cohort across isolated ARES builds and generated-code tests."""

import argparse
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
COMMENT_OR_STRING = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/')


def active_source(text):
    return COMMENT_OR_STRING.sub(
        lambda match: match.group() if match.group()[0] in "\"'" else " ", text)


def kotlin_plugin(name):
    return rf'kotlin\("{re.escape(name)}"\)\s+version\s+"([^"]+)"'


def plugin_id(name):
    return rf'id\("{re.escape(name)}"\)\s+version\s+"([^"]+)"'


def coordinate(name):
    return rf'[\"\']org\.jetbrains\.kotlin:{re.escape(name)}:([^\"\']+)[\"\']'


def verify(root):
    """Return the canonical version, number of checked pins, and actionable violations."""
    pins = []
    errors = []

    def inspect(path, patterns):
        file = root / path
        if not file.is_file():
            errors.append(f"{path}: compiler configuration is missing")
            return
        text = active_source(file.read_text(encoding="utf-8"))
        for label, pattern in patterns:
            values = re.findall(pattern, text)
            if len(values) != 1:
                errors.append(f"{path}: expected one active {label} pin, found {len(values)}")
            else:
                pins.append((path, label, values[0]))

    standard_library = [(artifact, coordinate(artifact)) for artifact in
                        ("kotlin-stdlib", "kotlin-stdlib-jdk7", "kotlin-stdlib-jdk8", "kotlin-stdlib-common")]
    inspect("ARESLib-Kotlin/build.gradle.kts", [
        ("Kotlin/JVM", kotlin_plugin("jvm")),
        ("serialization", kotlin_plugin("plugin.serialization")),
        ("Kotlin/Android", plugin_id("org.jetbrains.kotlin.android")),
        *standard_library,
    ])
    inspect("ARES-Analytics/build.gradle.kts", [
        ("Kotlin/JVM", kotlin_plugin("jvm")),
        ("serialization", kotlin_plugin("plugin.serialization")),
        ("Compose compiler", plugin_id("org.jetbrains.kotlin.plugin.compose")),
    ])
    for product in ("ARES-FTC", "ARES-FTC-Starter"):
        inspect(f"{product}/build.gradle", [
            ("Kotlin Gradle plugin", coordinate("kotlin-gradle-plugin")), *standard_library,
        ])
    for product in ("ARES-FRC", "ARES-FRC-Starter"):
        inspect(f"{product}/build.gradle", [
            ("Kotlin/JVM", r'id\s+[\"\']org\.jetbrains\.kotlin\.jvm[\"\']\s+version\s+[\"\']([^\"\']+)[\"\']'),
        ])
    inspect("ARESLib-Kotlin/codegen/build.gradle.kts", [
        ("embedded generated-code compiler", coordinate("kotlin-compiler-embeddable")),
    ])
    inspect("ARES-Analytics/app/build.gradle.kts", [
        ("embedded generated-code compiler", coordinate("kotlin-compiler-embeddable")),
    ])
    version = next((value for path, label, value in pins
                    if path == "ARESLib-Kotlin/build.gradle.kts" and label == "Kotlin/JVM"), None)
    if version is not None:
        for path, label, value in pins:
            if value != version:
                errors.append(f"{path}: {label} is {value}; expected the library compiler cohort {version}")
    return version, len(pins), errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args()
    version, count, errors = verify(args.root)
    if errors:
        parser.exit(1, "Kotlin compiler alignment failed:\n" + "\n".join(errors) + "\n")
    print(f"Kotlin compiler cohort verified: {version}, {count} aligned pins across six products and codegen tests.")


if __name__ == "__main__":
    main()
