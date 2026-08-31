# ARES Monorepo Release Transition

This is the current release and repository policy. Historical milestone and cycle records describe
the state at the time they were written and are not operational instructions.

## Authoritative source and public artifacts

- `ARES-23247/ARES-Robotics` is the only authoritative source repository.
- Final ARES Maven artifacts are published first to the monorepo `maven` branch under immutable
  `org.aresfirst.ares` coordinates.
- One protected Studio release contains the Windows MSI, macOS DMG, deterministic FTC and FRC
  standalone starter archives, and SHA-256 checksums.
- Maven Central is an optional additional channel. It is never required to validate a release.
- Old component repositories and their Maven/release assets remain readable so existing projects
  continue to resolve immutable releases.

## Required dependency order

1. A pull-request or merge-queue `Build Desktop Packages` run tests ARESLib and publishes a unique
   isolated RC repository.
2. That same run tests FTC, FRC, both starters, simulators, and Studio against the exact RC.
3. It stages final ARES coordinates, verifies deterministic starter hashes, and builds the Windows
   MSI and macOS DMG once.
4. After every gate passes, it seals all promotable files into one 30-day candidate. The candidate
   manifest binds every path, size, and SHA-256 to the complete Git tree, canonical versions,
   repository, event, workflow reference, run ID, and attempt. GitHub records a build-provenance
   attestation for the enclosing archive.
5. Merge through protected `main`. If the resulting full Git tree differs at all, discard the
   candidate and run the gates again; never promote artifacts built from a merely similar commit.
6. Dispatch `Promote Verified Release Candidate` with the successful candidate run ID. Leave its
   `publish` input false for a verification-only rehearsal; set it true only for publication. Promotion
   verifies the originating workflow and repository, rejects forks and failed runs, verifies the
   GitHub attestation, rehashes every file, checks the exact `main` tree and release manifest, and
   rejects an existing release tag.
7. Promotion publishes the already-tested Maven tree first and the already-tested Studio/starter
   assets second. It does not compile, test, generate, simulate, or package again. Checksum mode
   publishes the exact attested MSI. Authenticode mode signs and verifies that MSI as a bounded
   post-build trust overlay, without rebuilding it.

The older `publish=true` input on `Build Desktop Packages` remains a full-rebuild emergency fallback
until the candidate-promotion path completes a protected production cycle. Normal releases must use
candidate promotion so PR evidence and published bytes are the same files.

Studio must never advertise a final dependency that is not available in the same completed release
sequence.

## Legacy repository retirement

Do not archive component repositories yet. Keep them clearly labeled as legacy history/release
sources until **two successful protected monorepo release cycles** complete. Then source repositories
may be archived; immutable Maven branches and release assets must remain available. Starter
repositories may remain as read-only discovery mirrors.

## Local checkout migration

Never reset or clean the former workspace during migration. Use
`scripts/prepare-clean-monorepo-checkout.ps1` to inventory it, clone the monorepo elsewhere, review
user-owned files explicitly, and switch only after the clean checkout passes setup and validation.

