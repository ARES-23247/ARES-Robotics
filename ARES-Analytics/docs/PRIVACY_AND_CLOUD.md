# Privacy and cloud data

ARES Robotics Studio is local-first. Live robot telemetry, imported logs, authoring files, simulation,
and analysis run on the laptop. Robots do not sign into Google and do not upload directly.

Choosing a log file copies it into the currently selected robot project's local archive. The
selected original is not edited or deleted. Parsed sessions are stored in the current Windows
user's local DuckDB database with exact team, season, and robot identity. ARES filters Dashboard,
Run History, Guided Review, and Cloud lists by that identity so one workspace cannot disclose
another workspace's sessions through normal application state.

Historical replay reads only the selected local session. It does not republish recorded values to
the live NT4 connection, send robot controls, or merge current live topics into missing historical
fields. Navigating to replay does not upload the run; cloud synchronization remains a separate,
explicit workspace action governed by the destination and account rules below.

Run comparison is also local and read-only. It accepts only sessions whose team, season, and robot
identity exactly match the active workspace, and it never fills missing historical evidence from a
live connection or another workspace. Export creates a new Markdown file in a location the user
chooses; it does not upload the report. Reports include session identifiers, topic names, and exact
recorded timestamps, so a student or mentor should review those details before sharing a report
outside the team.

Guided tuning experiment records are stored under the selected project at
`.ares/local/tuning/experiments`. They snapshot hashes and resolved tuning values rather than robot
credentials, and they are not uploaded automatically. A user-chosen mentor/student report may
include team and robot identifiers, telemetry topic names, exact run timestamps, parameter values,
and student-authored notes. Review that report before sharing it. Removing or rolling back a staged
candidate does not delete the historical experiment evidence and never changes a canonical tuning
profile without the separate structured-review workflow.

Google Drive synchronization is opt-in. Before consent, the app explains that it requests basic
Google identity and `drive.file`. This scope is limited to files ARES creates or the user explicitly
selects for ARES. ARES stores the selected workspace folder/Shared Drive ID and does not scan
unrelated Drive files.

Official installers include a public OAuth client ID and an HTTPS broker URL, never a client
secret. During sign-in, the broker temporarily receives the one-time authorization code and PKCE
verifier; during refresh, it receives the refresh token. It adds the protected server-side client
secret when calling Google and returns the token response to the app without persisting it. The
broker does not receive Drive folder IDs, file content, telemetry, or the DuckDB database. Drive
operations go directly from the signed-in desktop to Google.

OAuth access and refresh tokens are sensitive. Windows installations protect them with DPAPI for
the current Windows user. Existing plaintext token files are migrated after a successful secure
write. macOS/Linux currently use an atomically replaced owner-only file. Workspace configuration
may contain public OAuth client IDs, an HTTPS broker URL, and stable Drive folder/account
identifiers, but never contains a Google client secret. An organization using its own OAuth client
must protect the matching secret in its own broker rather than on student computers.

Signing out removes the local token record. Disconnecting a workspace destination leaves both local
data and Drive files intact. Removing ARES access in the Google Account permissions page revokes
Google's grant; ARES detects the unusable session and asks the user to reconnect.

Each workspace stores one explicit destination root and the signed-in account identity. ARES fails
closed when they do not match or Google reports lost permissions; it does not fall back to listing
another folder or unrelated Drive content. Teams can export the non-secret destination record,
download remote-only sessions, choose a new root, and explicitly resynchronize local data without
cloud lock-in.
