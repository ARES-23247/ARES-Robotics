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

## Repair or reinstall the same version on Windows

Run the exact same downloaded `.msi` again. Windows Installer opens **Change, repair, or remove
installation**; choose **Repair**. Repair restores missing or corrupt installed program files and shortcuts without
deleting projects, `.ares` documents, the local telemetry database, settings, or user-bound secure
credentials.

You can also use Windows' standard repair command from an administrator terminal:

```powershell
msiexec.exe /fa "ARES Robotics Studio-<version>.msi"
```

The release build inspects every generated MSI and fails if the stable upgrade identity,
maintenance dialog, or Repair button disappears. The protected Windows package job also downloads
the previous public MSI on a clean runner, installs it, upgrades it with the candidate MSI, verifies
that only the candidate version remains, repairs that installation, and removes it. A table-only MSI
inspection is not treated as proof that an upgrade works.

After local verification, the MSI is copied to the repository-root `dist` directory so it is not
buried under Gradle's intermediate build tree. `dist` is intentionally ignored by Git; installers
belong in GitHub Releases rather than source history.

## GitHub Releases

The protected **Build Desktop Packages** workflow creates Windows and macOS packages only after the
fresh-project acceptance test and native package checks pass. There are two supported release paths:

- Push a protected semantic-version tag such as `v1.4.0`; the workflow publishes the verified
  packages automatically.
- Run the workflow manually, enter the semantic version, and explicitly enable **publish**. The
  workflow creates the tag and GitHub Release only after every required job succeeds.

A manual run with **publish** disabled is a packaging rehearsal: its installers remain downloadable
as temporary workflow artifacts but no public tag or release is created.

## Clean removal

Uninstalling removes the installed application, not robot projects or the local workspace data under
`~/.ares-analytics`. Export important runs and projects before intentionally deleting those folders.
