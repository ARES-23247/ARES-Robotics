# Google OAuth and multi-team Drive architecture

## Application identity is not data ownership

The production `ARES-Analytics Desktop Client` in the `aresfirst-portal` Google Cloud project is a
public OAuth application identifier. It tells Google which desktop application is requesting
consent. It does not grant Team 23247 access to a user's Drive and it does not select where data is
stored. Every user authenticates directly with Google; Google remains authoritative for identity,
ownership, sharing, revocation, and quotas.

ARES uses Authorization Code + PKCE and the fixed desktop loopback callback
`http://127.0.0.1:5805/callback`. It requests `openid`, `email`, `profile`, and the narrow
`drive.file` scope. The public client ID and managed broker URL are compiled into official
installers; no client secret is stored in or requested by the desktop app.

Google currently requires the generated secret for the active ARES Desktop client during token
exchange. A narrowly scoped HTTPS broker therefore adds the protected secret while exchanging a
one-time authorization code or refresh token with Google. The broker returns Google's token
response to the desktop and does not persist it. The desktop stores the user's tokens and performs
all Drive list, upload, download, manifest, and delete operations directly. The broker never
receives a workspace folder ID, Drive file content, telemetry, or the local database.

```text
browser consent -> loopback code + PKCE verifier -> ARES token broker -> Google token endpoint
                                                     |
                                                     v
desktop token store -> selected workspace root -> Google Drive API
```

## Destination behavior

Each `WorkspaceConfig` may contain one `DriveDestinationConfig` with a stable root folder ID,
destination type, display name, signed-in Google subject/email, and optional Shared Drive ID.

| Destination | Google owner | How access is granted | ARES behavior |
|---|---|---|---|
| Personal folder | Signed-in user | User owns it | Creates and scopes all ARES files below it |
| Team folder | Creating user | Owner shares it | Same technical model as My Drive, visibly labeled team-shared |
| Existing shared folder | Existing owner | Owner grants Editor access, then user selects it in Google Picker | Picker grants per-folder `drive.file` access; ARES never searches Drive for it |
| Shared Drive | Google Workspace organization | User selects a folder in Picker and has Shared Drive membership | Stores both selected root and owning Shared Drive ID; uses `supportsAllDrives` |

## Isolation and permissions

The stored root ID is a security boundary. Sync no longer searches My Drive for an
`ARES-Analytics` folder. Before list/read/write/delete, the Drive service verifies that the target
is the root or a descendant. Manifest file IDs are checked the same way before bytes are read or
deleted. A workspace/account mismatch fails before a network file operation.

Each new session object also embeds a stable league/team/season/robot workspace key. This is
checked against the active workspace after download, so moving or copying an index entry across
workspace roots cannot relabel another robot's data as local evidence.

## Session object format

New uploads are immutable versioned `.ares-session.zip` bundles rather than telemetry-only files.
The bundle contains a Parquet telemetry entry and a bounded JSON manifest with session metadata,
Redux actions, annotations, alerts, console messages, derived analysis diagnostics, and import
reports. The Drive index records the bundle version, exact object name, byte count, SHA-256, and
workspace key. The bundle separately records and verifies the inner telemetry byte count and
SHA-256 before DuckDB sees it.

Download/import is one database transaction across the raw timeline, session/summary, and all
ancillary records. A failure cannot leave a session that looks complete while actions or notes are
missing. Existing legacy `.parquet` objects remain downloadable for backward compatibility; new
uploads always use the complete bundle.

ARES does not call `drives.list`, because that endpoint requires a broader Drive scope. Google
Picker is a second PKCE authorization requesting only `drive.file`; it returns the one folder the
user selected. Its code exchange uses the same broker, but the desktop calls Drive directly with
the returned access token. ARES verifies the picker account matches the signed-in account before
storing the ID.

Google permissions are always authoritative. ARES may describe a person as a student or mentor for
teaching workflows, but that label does not grant cloud access. If an ARES role conflicts with the
Google role, the operation fails closed. HTTP 401 clears the unusable session; 403/404 reports lost
permission, removed sharing, or deletion rather than presenting an empty cloud.

## Advanced OAuth client ownership

Official builds receive the team-owned public client ID and HTTPS broker URL through protected
release configuration. The broker receives the matching client ID and secret through protected
Cloud Run configuration; neither value is returned in an error. The normal UI has one **Sign in
with Google** action and never displays a secret field.

Schools may explicitly enable a custom Desktop OAuth client in **Advanced administrator settings**.
That path requires both the organization's public Desktop client ID and the URL of an
organization-managed HTTPS token broker configured for that same client. ARES never asks the
administrator to put the matching client secret in the app or workspace file. Existing client IDs
in old workspace files are ignored unless the custom-client switch is enabled, which migrates users
away from the deleted legacy client safely.

## Token storage and revocation

On Windows, new releases protect the serialized OAuth token record with current-user DPAPI and
migrate `~/.ares-analytics/auth.json` to `auth.dpapi` after the first successful read. macOS and
Linux currently retain the owner-only atomic token file; native Keychain/Secret Service storage is
a documented follow-up rather than an unverified shell integration. Client IDs are public, but ARES
does not print them in OAuth errors. Tokens and authorization response bodies are never logged.

Saved tokens record the OAuth client ID that issued them. A client mismatch, `deleted_client`,
`invalid_grant`, revoked access, or an expired refresh token clears unusable state and requires a
new sign-in. The broker does not retain refresh tokens between requests. Changing the selected
workspace does not transfer authentication or a destination: the saved account subject/email and
root ID must match before Drive work begins.

## Migration, disconnect, and export

Changing or disconnecting a destination never deletes remote files. The recommended migration is:

1. Verify the old destination and download/import remote-only sessions.
2. Export the non-secret destination record for audit or handoff.
3. Select the new personal/team/shared destination.
4. Explicitly sync local sessions to the new destination.
5. Let the Google owner archive or delete the old folder after independent review.

Local DuckDB data, archived robot logs, and exported Parquet/CSV/WPILOG files remain independent of
Google Drive, so a team is never locked into cloud synchronization. A Drive session bundle is a
portable analyzed-session backup, not the only copy of the original robot log.

## Production release gate

The broker design, unit tests, dashboard validation, packaged-runtime checks, and MSI build are
necessary but not sufficient evidence for release. A new installer remains blocked until a clean
installation completes production sign-in with the active `ARES-Analytics Desktop Client`, selects
each supported destination type, and proves upload/download plus revocation and permission-loss
recovery. Passing source-level tests must not be described as production OAuth verification.
