# Branding, upgrades, and repair

The desktop product is named **ARES Robotics Studio** with the description **Design • Simulate •
Operate • Analyze**. It was previously distributed as **ARES Analytics**. The new name reflects the
complete student workflow: create a robot, learn the concepts, generate and verify code, simulate,
operate, tune, and analyze evidence.

## What the rename changes

- Window, installer, shortcut, onboarding, help, and documentation names
- Windows, macOS, Linux, and taskbar icons
- Public descriptions and generated project-history author text

## What deliberately stays compatible

- Existing `.ares` project documents and robot repositories
- `~/.ares-analytics` settings, local database, layouts, learning progress, and secure token files
- Google OAuth and GitHub App identities until their administrators update public display branding
- `com.ares.analytics` packages, NT4 client identity, update repository, and diagnostic prefixes
- Windows installer upgrade UUID

Keeping those technical identifiers stable prevents a cosmetic rename from losing local history,
disconnecting cloud accounts, breaking telemetry filters, or installing a second copy of the app.

## Upgrade

Download and run the newest installer normally. Windows recognizes the former ARES Analytics
installation through the same upgrade UUID and upgrades it in place. Projects are ordinary folders
and are never removed by an application upgrade.

The downloaded installer must have a newer version number than the installed application. Published
installers are immutable: ARES never intentionally publishes two different packages under the same
version. If a locally built package and a public package happen to share a version, Windows rejects
the replacement instead of guessing which bytes should win. Install the next public patch release;
do not uninstall the application or delete a robot project.

Each GitHub release publishes a checksum beside the installer. Until the project configures a
publicly trusted Authenticode certificate, Windows packages use the explicitly disclosed
`checksum` bootstrap policy: Studio and its update helper verify the exact MSI against the named
SHA-256 asset from the same immutable GitHub release, while Windows may still show an unknown
publisher warning. When a release is built in `authenticode` mode, the installed app pins the
certificate thumbprint and refuses to downgrade to checksum-only verification. The release's
`windows-installer-trust.json` records the selected policy, digest, signature status, and signer
thumbprint when present.

## Repair or reinstall the same version on Windows

Run the exact same downloaded `.msi` again. Windows Installer opens **Change, repair, or remove
installation**; choose **Repair**. Repair restores missing or corrupt installed program files and shortcuts without
deleting projects, `.ares` documents, the local telemetry database, settings, or user-bound secure
credentials.

You can also use Windows' standard repair command from an administrator terminal:

```powershell
msiexec.exe /fa "ARES Robotics Studio-<version>.msi"
```

The release build (`verifyWindowsInstallerMaintenance` running `ARES-Analytics/scripts/verify-windows-installer.ps1`)
inspects every generated MSI and fails if the stable upgrade identity, maintenance dialog, or Repair
button disappears. `ARES-Analytics/scripts/verify-windows-installer-upgrade.ps1` provides the local
elevated end-to-end upgrade, repair, and uninstall verification harness (not yet wired into the
GitHub-hosted `build-distributions.yml` workflow).

After local verification, the MSI is copied to the repository-root `dist` directory so it is not
buried under Gradle's intermediate build tree. `dist` is intentionally ignored by Git; installers
belong in GitHub Releases rather than source history.

## GitHub Releases

Release packaging and publication use a two-stage attested workflow:

- **Build Desktop Packages** (`.github/workflows/build-distributions.yml`) runs on pull requests,
  merge queue, or manual dispatch. It validates `studioReleaseVerification`, starter mirror
  acceptance, Windows MSI and macOS DMG packaging, deterministic starter/example archives, and
  seals an attested 30-day release candidate artifact without creating a public tag or release.
- **Promote Verified Release Candidate** (`.github/workflows/promote-release-candidate.yml`) is
  dispatched manually from protected `main` with the successful `candidate_run_id`. Leaving
  `publish` disabled performs a verification-only promotion rehearsal; enabling `publish` (with
  `windows_installer_trust` set to `checksum` or `authenticode`) publishes the immutable Maven
  artifacts first and creates the GitHub Release without rebuilding.

## Clean removal

Uninstalling removes the installed application, not robot projects or the local workspace data under
`~/.ares-analytics`. Export important runs and projects before intentionally deleting those folders.
