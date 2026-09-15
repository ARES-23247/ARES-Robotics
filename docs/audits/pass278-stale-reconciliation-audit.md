# Monorepo stale record reconciliation, orphan pruning, and verification audit

Pass 278 audits and reconciles 69 tracked files whose content digests diverged during recent
monorepo development, version alignment (ARES 19.0.5, Studio 7.0.59), and the XRP MicroPython
platform integration. It prunes 5 orphaned records from the review ledger and resolves a
Windows PowerShell character-encoding defect across git-invoking verification scripts.

Source commit: 121da5b65cce4f8a466a10588714156dbd6b2061.
Target branch: ntigravity/audit-pass278.

## Confirmed findings and fixes

1. **Host deadline timing was vulnerable to frozen or mocked robot time.**
   In RobotClock.kt, hostNanoTime() now reads monotonic system time directly (System.nanoTime()),
   preserving deadline progression in simulation and test hosts even when RobotClock.useMockTime()
   freezes or rewinds simulated robot time. Contract tests in RobotClockContractTest.kt prove host
   time monotonicity across Long.MAX_VALUE mock boundaries.
2. **PowerShell native stdout decoding corrupted UTF-8 and accented paths.**
   scripts/verify-doc-links.ps1, scripts/verify-monorepo-policy.ps1, and scripts/export-starter-mirrors.ps1
   relied on default Windows PowerShell console output decoding (OEM 437 / ASCII), causing
   git ls-files -z with accented or Unicode paths (e.g., café.md, 
ésumé.md) to be misdecoded
   into corrupted filenames (cafÃ©.md), failing Test-Path and dropping files from validation.
   Configuring $OutputEncoding = [System.Text.UTF8Encoding]::new(False) and
   [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(False) guarantees byte-accurate
   UTF-8 decoding across both Windows PowerShell 5.1 and PowerShell 7+.
3. **XRP simulator and embedded link safety contracts verified.**
   The XRP MicroPython runtime (res_micro/robot.py, 	elemetry.py) and starter simulator
   (ARES-XRP-Starter/simulator/xrp_simulator.py, main.py) were validated against loop timing,
   control lease expiry, bound transport (16 KiB limit), loopback socket binding on desktop,
   and deterministic Wi-Fi connection retry timeouts.
4. **FTC hardware abstraction and Lightbot mock alignment.**
   FtcBaseRobot.kt, FtcImuCache.kt, RevI2CSensorManager.kt, and SensorMocks.kt were
   reconciled to reflect Lightbot mock heading feedback, Android lifecycle initialization,
   and cached IMU read reuse without memory allocation during 50 Hz control loops.
5. **Ledger orphan cleanup.**
   Five orphaned records referencing files that were refactored or superseded were cleanly removed:
   - ARES-Analytics/app/src/main/kotlin/com/ares/analytics/viewmodel/field/FieldDocumentMapper.kt
     (modularized into focused store, transaction, and projection components in earlier passes).
   - Four superseded starter project template archives:
     ARES-FRC-Starter-19.0.0.zip, ARES-FTC-Starter-19.0.0.zip,
     ARES-Lightbot-Example-3.0.54.zip, and ARES-XRP-Starter-3.0.54.zip.

## Validation evidence

- **ARESLib Core & FTC Hardware Suites**:
  - .\gradlew.bat :core:test :ftc-hardware:test :ftc-mocks:test passed in 1m 47s (19 actionable tasks, all passed).
- **XRP Runtime & Starter Suites**:
  - python -m unittest discover -s ARESLib-Kotlin/ares-micro/tests: 132 tests ran and passed (0 failures, 0 errors).
  - python ARES-XRP-Starter/tools/ares_project.py verify: 124 tests ran and passed (0 failures, 0 errors).
- **Scripts, Governance & Monorepo Policy Suites**:
  - python -m unittest discover -s scripts/tests: 104 tests ran and passed in 170s (0 failures, 0 errors), including 	est_doc_links.py, 	est_monorepo_policy.py, 	est_starter_export.py, and 	est_audit_ledger_io.py.
