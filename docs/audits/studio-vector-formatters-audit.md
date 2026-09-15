# Studio vector drawing and date formatter audit - pass 166

Read VectorDrawingUtils, its three current drawing call sites, AresFormatters and the
existing formatter tests. Arrowhead trigonometry correctly places both wings behind
the endpoint. However, nonfinite coordinates/lengths/angles and invalid stroke widths
could reach drawing calls because NaN bypassed the short-vector comparison. The helper
now rejects these inputs before drawing, computes endpoint differences in Double to
avoid Float subtraction overflow, and checks converted wing coordinates before painting
the shaft. Zero-length behavior remains a no-op. No before-fix execution is claimed.

Three offscreen CanvasDrawScope tests rasterize actual ImageBitmap output. They verify
horizontal/vertical shafts and rear-facing wings, filled-head interior versus open-head
gap, and entirely blank output for zero/representative invalid inputs. These are observed
offscreen raster results, not a rendered Studio-window or native pointer interaction test.
The current Mecanum/Swerve callers all use open heads, so the filled-head Path allocation
does not occur in those calls. No shared mutable path cache was introduced.

AresFormatters owns immutable DateTimeFormatter instances with explicit Locale.US and
the system zone captured at initialization. Its implementation required no change.
Replaced a same-call-equals-itself test with 200 operations across four worker threads,
comparing all three formats to expectations prepared separately with SimpleDateFormat.
The mutable reference formatters remain on the test thread; workers use only Studio's
formatters. Cases include negative epoch milliseconds, epoch zero, leap-day-era and
modern dates, and a timestamp beyond signed 32-bit Unix seconds. The test does not mutate
global locale or time-zone defaults and shuts down its own executor.

Both source files and their complete test files are reviewed within these utility
contracts. Runtime changes to the OS default time zone require formatter reinitialization;
that existing captured-zone behavior is not changed here. Extreme calendar chronology,
physical display fidelity and arbitrary renderer color-space inputs are not certified.
No timing benchmark or robot-loop improvement is claimed.

Evidence is retained under `ARESLib-Kotlin/build/audit-pass166-verified-evidence/`.
Candidate remains `17.0.3-rc.100852e472fb`; no simulator, external service, Studio window
launch or physical robot operation occurred.

Validation: all six focused vector/formatter tests pass. The full app suite reports 1,828 tests: 1,822 passed and 6 opt-in skips, with no failures or errors. Unchanged shared/gateway suites were not rerun. Monorepo policy, documentation links and staged whitespace checks pass.
