# Project Backup

ARES can save named versions of a robot project and optionally synchronize them to a private GitHub
repository. This feature is independent of Google Drive session/log synchronization.

## What students need

- No separate Git installation. ARES includes JGit for project history and synchronization.
- A name and email only when a student or mentor saves a manually reviewed version.
- A GitHub account only if the team wants an off-computer backup.

Open **Profile & Settings → Project History & Backup**.

1. Projects created by ARES already have local history and one clean baseline version. ARES authors
   that mechanical baseline as **ARES Robotics Studio**; it does not pretend a student wrote the starter.
   For an older or imported project, choose **Start local history** once. Neither path uploads anything.
2. Review the exact changed-file list, describe the change, then choose **Save this version**.
   A content-bound confirmation token prevents a file changed after preview from being committed.
3. Optionally choose **Sign in with GitHub** and approve the short device code in the browser.
4. Choose a personal account or team organization, then choose a private repository that a team
   owner has approved for the ARES GitHub App. GitHub backup is allowed only from a clean saved
   version.
5. Use **Check for newer version** when another computer or teammate may have updated the GitHub
   copy. ARES shows the exact file list before enabling **Restore this reviewed version**.
6. If desired, enable **Back up each saved version automatically**. This setting is local to this
   project and is off by default. ARES waits briefly to combine rapid checkpoints, never uploads
   unsaved working files, retries temporary connection failures a bounded number of times, and
   reports whether it is queued, syncing, offline, or needs attention without relying on color.

The recent-versions timeline shows the saved description, author, time, and a short version ID.
Students do not need to understand branches or Git commands to use it.

After local history is enabled (automatically for app-created projects), the primary zero-code editors create narrowly scoped automatic
checkpoints after a successful canonical save. Subsystem Builder, Controller Bindings, Drivebase
Builder, Superstructure Studio, Autonomous Routine Builder, and reviewed Tuning profile promotion
commit only the files that editor just saved. Unrelated hand-written or mentor-edited files remain
unsaved and visible for review.

Use **Export portable project archive** to create a `.aresproject.zip` for transfer to another
computer. The export includes canonical documents and source while excluding Git internals, build
caches, machine-local settings, and known credential paths. ARES never silently replaces an
existing archive.

A student does not paste a token, install Git, or configure SSH. For a team repository, an
organization owner creates the empty private repository and installs ARES for that repository once;
students then see it in the destination picker when their GitHub account has access.

## Safety and privacy

- GitHub App device authorization uses a public application client ID and never a client secret.
- ARES requests an expiring GitHub App user token and rotates its refresh token. It does not request
  the broad legacy `repo` OAuth scope.
- On Windows the GitHub credentials are protected with DPAPI for the current user. Other platforms use
  the existing owner-only ARES credential-file policy until a native keychain backend is added.
- Tokens are never embedded in remote URLs, robot project files, terminal arguments, or logs.
- Known secret-bearing paths such as `credentials.json`, keystores, `.env`, and `.ares/secrets/`
  block a save if they are not already ignored.
- Push is non-destructive. A non-fast-forward or permission conflict fails visibly instead of
  rewriting remote history.
- Automatic online backup is opt-in. Temporary network failures retain the local commit, show a
  retry status, and never weaken permission, clean-tree, or fast-forward checks.
- Restore is fast-forward only. ARES rejects divergent histories, sensitive paths, links/special
  files, non-canonical projects, and oversized unreviewed content. Immediately before a restore it
  creates a local safety checkpoint that retains the previous commit.
- The restore confirmation is bound to the exact local commit, remote commit, and reviewed file
  list. If GitHub changes after preview, ARES requires a new review.
- A prior restore can be undone from **Recent saved versions**. ARES previews the reverse file list,
  requires confirmation, and preserves the version being left as another recovery point so the
  operation is reversible.
- Every sync rechecks the exact installation ID, repository ID, private visibility, and current
  write permission. Removed sharing or organization access fails closed.
- **Change destination** removes only ARES's local remote metadata. **Sign out** deletes the saved
  app credential. Neither action deletes local versions or the GitHub repository.
- If repository access is removed or the App installation is suspended, ARES keeps local history
  unchanged and directs the user to restore App access and choose **Refresh destinations**.

## Administrator setup

The official installer must receive `ARES_GITHUB_APP_CLIENT_ID` and `ARES_GITHUB_APP_SLUG` from
protected repository variables at build time. The corresponding public GitHub App must have Device
Flow and expiring user tokens enabled. The client ID and slug are public application identity;
never configure or bundle a GitHub client secret or private key in the desktop application.

Configure repository **Contents: read and write** and metadata read. Do not grant Administration,
Members, Actions, Secrets, or webhook access. Prefer **Only select repositories** for organizations.
ARES deliberately does not create organization repositories because doing so would require broad
Administration permission; a team owner retains that responsibility and chooses the approved
repositories. See [GitHub App project-backup architecture](../GITHUB_PROJECT_BACKUP_ARCHITECTURE.md).
