# Set up optional Google Drive sync

Google Drive is optional. Robot connections, project authoring, simulation, log import, replay, and
analysis keep working without an account or internet connection.

## Student setup

1. Open **Profile & Settings → Google Drive** or expand **Cloud sync** during first-run setup.
2. Read the access summary, then choose **Sign in with Google**.
3. Google opens in your browser. Choose the account that owns or can edit the team's destination.
   The browser may say that authorization was received before the app finishes sign-in; return to
   ARES and wait for the signed-in account to appear.
4. Choose exactly one destination for this ARES workspace:
   - **Personal Drive folder** creates a private folder in your My Drive.
   - **Create a team folder** creates a My Drive folder that its owner can share with students and mentors.
   - **Join an existing shared folder** opens Google Picker; choose the folder after its owner gives your account Editor access.
   - **Google Shared Drive** opens the same picker; choose a folder inside a Shared Drive where your account is a Contributor or higher.
5. Confirm that ARES reports **Read and write verified** before synchronizing.

The ARES OAuth client ID identifies the application to Google. It does **not** give Team 23247,
ARES, or another team ownership of your files. Google account permissions remain authoritative.
ARES requests `openid`, basic profile/email identity, and the narrow `drive.file` scope. Existing
folders are granted through Google Picker rather than a broad Drive search. ARES then lists and
changes files only under the stable destination ID saved for the current workspace.

The official installer contains a public ARES client ID and the address of the ARES sign-in
service. That service completes Google's token exchange because the matching secret cannot safely
be placed in a student app. It does not store tokens or receive Drive folders, files, telemetry, or
the local database. The laptop stores the login and talks directly to Drive.

## Personal versus team workspaces

- A personal destination is useful for experiments and private logs. Sharing is off until the owner
  changes it in Google Drive.
- A team folder is still owned by a Google account. The owner must share it and Google decides who
  may view or edit it.
- A Shared Drive is owned by the Google Workspace organization, not an individual. Shared Drive
  membership and Google roles control access.
- ARES does not invent cloud roles. Labels such as student or mentor are instructional only and
  never override Google permissions.

## Recovery

- **Wrong account:** sign out, then reconnect as the email shown next to the saved destination.
- **Access removed or folder deleted:** ask its Google owner to restore Editor/Contributor access,
  or select a new destination. ARES fails visibly and does not treat the folder as empty.
- **Revoked or expired login:** ARES clears the unusable local session and asks you to sign in again.
- **Sign-in service unavailable:** keep working locally and retry later. A student should never be
  asked to supply a client secret.
- **`deleted_client`:** disable a custom OAuth client in Advanced settings to return to the
  ARES-managed client, or ask the custom client's administrator to replace it.
- **Offline:** keep working locally and retry synchronization later.

Disconnecting or changing a destination never deletes Drive files. Before switching, import any
remote-only sessions you need. Export the destination record for handoff, choose the new destination,
then sync local sessions into it.

## Administrator: bring your own OAuth client

Official installers contain the public ARES Desktop OAuth client ID and managed sign-in-service
address. A school or team may instead open **Advanced administrator settings**, enable **Use your
organization's OAuth client**, and enter its own Desktop client ID and administrator-managed HTTPS
token-broker URL. This changes application identity, policy, quota, consent branding, and broker
operator; it does not change the selected Drive destination or Google permissions.

Create a Google OAuth **Desktop app** client, enable the Drive API, publish the consent screen for
the intended audience, and configure the exact scopes above. Configure the matching secret only in
the organization's protected broker. Never enter or distribute it through ARES. See
[Google Cloud OAuth administration](../admin/GOOGLE_CLOUD_OAUTH.md) before enabling this option.

References: [OAuth for desktop apps](https://developers.google.com/identity/protocols/oauth2/native-app)
and [Google Drive API scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth).
