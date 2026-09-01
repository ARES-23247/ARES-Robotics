# GitHub App Project Backup Architecture

ARES Project Backup has two independent layers:

1. Embedded JGit creates reviewed, named versions inside the robot project. It works offline and
   requires no separate Git installation.
2. An optional public GitHub App sends clean saved versions to one explicitly approved private
   repository.

## Identity and ownership

The ARES GitHub App identifies the desktop application; it does not own a student's files and it
does not make Team 23247 the owner of another team's project. A repository remains owned by the
selected personal account or GitHub organization. GitHub membership, repository permissions,
organization policy, and the App installation remain authoritative.

The desktop uses GitHub's Device Flow with the App's public client ID. It never embeds a client
secret or GitHub App private key. Expiring user access and refresh tokens are required. The access
token is refreshed and rotated without a client secret, and unusable legacy, revoked, corrupt, or
expired credentials are cleared with an actionable sign-in message.

App-created FTC and FRC projects receive their Git repository and first clean commit while the
verified starter is still in its protected staging directory. The project directory becomes
visible only after both template validation and history initialization succeed. The mechanical
baseline is authored by `ARES Robotics Studio <local-history@aresfirst.org>`; later reviewed commits use
the student or team identity entered in Project History. Existing imported projects remain an
explicit opt-in so ARES never silently takes ownership of an arbitrary source tree.

## Organization workflow

The normal team workflow is:

1. A GitHub organization owner creates an empty private repository.
2. The owner installs the ARES GitHub App for that repository (prefer **Only select repositories**).
3. A student signs in to ARES with GitHub and chooses the organization and approved repository.
4. ARES records the non-secret installation ID and repository ID in local Git configuration.
5. Every sync or restore check fetches current installation and repository permissions before
   contacting GitHub.

ARES does not create organization repositories. GitHub requires Administration write permission
for that operation, which is substantially broader than backup needs. Keeping creation with a team
owner makes repository ownership, naming, retention, and policy explicit.

## Permissions and isolation

The GitHub App needs repository metadata read and Contents read/write. It does not need
Administration, Members, Actions, Secrets, Issues, or webhook permissions. A destination is usable
only when all of these are true:

- it belongs to the selected installation;
- its stable repository ID is present in the fresh installation catalog;
- it is private, writable, enabled, and not archived;
- the local `origin` identifies the same repository.

The UI cannot manufacture an installation or repository ID that is absent from GitHub's current
catalog. A removed installation, removed selected-repository grant, lost team membership, changed
write permission, public repository, or account mismatch blocks synchronization before JGit sends
bytes. Repository URLs never contain credentials.

## Restore and history semantics

The student-facing history viewer intentionally is not a general Git client. It exposes named local
versions, concept-grouped changed-file previews, explicit local/online status, portable export, and
reviewed restore/recovery operations. It does not expose arbitrary checkout, force push, rebase,
branch deletion, or conflict resolution.

A GitHub restore is accepted only when the local commit is an ancestor of the selected remote
`main` commit. Equal histories are reported as up to date; a local-ahead history directs the user
to synchronize; divergent histories stop and require an explicit student or teammate conflict review. Incoming trees are validated
before working files change, and the confirmation token binds both commit identities plus the diff.
ARES writes a `refs/ares/restore-backups/...` safety ref before the fast-forward so the prior local
version remains recoverable. Recovery is itself review-bound and creates a new safety ref before
moving the working tree, which preserves a redo path.

Zero-code editors depend on a narrow `ProjectCheckpointRecorder` boundary. When local history is
enabled, the editor supplies only the exact canonical current/history files it successfully wrote.
The JGit commit uses path-limited staging and commit semantics, so unrelated working-tree or staged
changes are not absorbed into an automatic checkpoint. If local history is disabled, the recorder
is a no-op and never creates `.git` implicitly. This latter case applies to older/imported projects;
new projects created by ARES have history from their first visible moment.

Automatic GitHub backup is a separate local repository preference (`aresBackup.autoSync`). It is
off by default and is not a tracked project file, so enabling it on one computer does not surprise
another teammate. A successful local checkpoint sends a conflated signal to one background worker.
The worker debounces rapid saves, requires a clean tree and an approved stable destination, and then
uses the same permission revalidation and non-force push as the manual action. Temporary network
failures use bounded retries and a visible offline status; permission, account, destination, or
history conflicts stop with an attention-required status. Local commits remain durable regardless
of online outcome.

Portable export uses a same-directory durable temporary ZIP followed by atomic placement. The
archive omits `.git`, build/cache/IDE directories, machine-local properties, and known credential
paths; it rejects links, oversized content, destinations inside the project, and existing targets.

## Credential storage and recovery

The credential record is stored in the current user's DPAPI vault on Windows, Keychain on macOS,
or Secret Service on Linux. Vault failures never fall back to plaintext. The robot project
contains only non-secret repository identity and its credential-free HTTPS remote.

**Sign out** removes the saved GitHub credential. **Change destination** removes the ARES-managed
remote and destination metadata. Neither action deletes local history or remote files. Teams can
always export or copy the ordinary project folder and `.git` history, so Project Backup does not
lock projects into ARES.

## Release configuration

Official installers receive the public `ARES_GITHUB_APP_CLIENT_ID` and
`ARES_GITHUB_APP_SLUG` through protected build variables. Device Flow and expiring user tokens must
be enabled on the GitHub App. The release gate must exercise a personal installation and an
organization installation against private selected repositories before publishing the installer.
