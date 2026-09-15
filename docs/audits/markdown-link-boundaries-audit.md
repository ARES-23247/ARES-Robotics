# Markdown link verification boundaries

Pass 188, 2026-09-11. The preceding pass completed starter-export fixes and exact
archive checks. This pass covers the previously pending documentation validator.

## Confirmed corrections

The old checker toggled code-block state on every line starting with three
backticks or tildes. Shorter inner fences, mismatched delimiters and trailing text
could expose example links as real links or hide real links after a code block.
The checker now remembers the opening character and length and accepts only an
appropriate closing fence. The top-level fence rules follow the
[CommonMark specification](https://spec.commonmark.org/0.31.2/#fenced-code-blocks).

An angle-bracket destination followed by a quoted title could bypass validation
entirely, especially when its path contained spaces. Destination extraction now
handles the tested angle/title forms before resolving the path. Git enumeration
uses NUL-delimited paths, and Markdown is explicitly read as UTF-8, allowing the
tested Unicode document and target names. An explicit character-array overload
keeps NUL splitting compatible with Windows PowerShell as well as PowerShell 7.

Lines without inline-link syntax bypass inline-code stripping and link matching.
Fence tracking still processes those lines. No wall-time speedup is claimed.

## Evidence and remaining scope

Eight new tests run under both installed PowerShell versions. Five cases failed
in each interpreter before the fix (ten failures): malformed fence state, hidden
real links, titled angle destinations and Unicode paths. All eight focused cases
pass, including historical exclusions, ordinary inline code, remote links,
existing encoded paths/fragments and rejection of machine-local file URLs.
A refinement run caught the .NET overload difference before final validation.

The real repository scan passed for 348 then-current documents, with 38 explicitly
historical records excluded. The full tooling suite passed 88 tests with no skips; final repository policy
passed and the updated scan checked 349 current documents, excluding the same
38 historical records.
Evidence is preserved under `ARESLib-Kotlin/build/audit-pass188-verified-evidence/`.

The checker remains partially reviewed. It is a scoped inline-link validator,
not a complete Markdown parser: reference links, nested container blocks,
multiline constructs, escaped or balanced-parenthesis destinations and anchor
existence need further work. Passing this check does not prove that every possible
Markdown link form is valid. No runtime source or release identity changed.
