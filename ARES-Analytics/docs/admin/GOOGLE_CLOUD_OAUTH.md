# Google Cloud OAuth administration

This page is for ARES release administrators and schools using their own Google Cloud project.
Students should use the one-click flow in [Google Drive setup](../start/GOOGLE_DRIVE_SETUP.md).

## Official ARES client

Official installers use the active **ARES-Analytics Desktop Client** in the `aresfirst-portal`
project. The Google Auth Platform configuration must remain:

- App name: **ARES Robotics Studio**
- Homepage: <https://aresfirst.org>
- Privacy policy: <https://aresfirst.org/privacy>
- Terms: <https://aresfirst.org/terms>
- Authorized domain: `aresfirst.org`
- Audience: External, published for users outside Team 23247
- APIs: Google Drive API enabled
- Declared scopes: OpenID, email, profile, and `drive.file`

The Desktop client ID is public application identity, not a password. Store it as the protected
GitHub repository variable `ARES_GOOGLE_OAUTH_CLIENT_ID` so official package jobs inject it into the
installer without echoing it in command arguments. Store the stable HTTPS broker origin as
`ARES_GOOGLE_OAUTH_BROKER_URL`. Official packages require both values.

Google currently requires the generated Desktop client secret at its token endpoint. The secret
must be stored in the production secret manager and exposed only to the gateway process as
`ARES_GOOGLE_OAUTH_CLIENT_SECRET`; the matching gateway client ID is
`ARES_GOOGLE_OAUTH_CLIENT_ID`. Never put the secret in Gradle properties, an installer, a workspace
file, screenshots, logs, or student instructions. Rotate it through the protected cloud/release
procedure if it may have been exposed.

The gateway accepts only the fixed loopback redirect `http://127.0.0.1:5805/callback`, validates the
PKCE verifier and input sizes, rate-limits exchange requests, and exposes only:

- `POST /api/oauth/google/token` for a one-time authorization code; and
- `POST /api/oauth/google/refresh` for a refresh token.

It adds the protected client credentials when calling Google's token endpoint, redacts identifiers
from returned failures, and does not persist request or response bodies. Drive API calls and token
storage remain desktop-owned. Do not add Drive file, folder, manifest, telemetry, or database
handling to this broker.

Google Picker is used for existing team folders and folders inside Shared Drives. Picker performs a
second PKCE authorization with only `drive.file`, then returns the selected folder ID. Do not replace
this with `drives.list` or broad Drive search unless a reviewed product requirement and Google scope
verification justify the expanded access.

## Bring your own OAuth client

A school may create a Google OAuth **Desktop app** client in its own project, enable Drive API, and
configure the same branding, audience, and scopes. It must also operate an HTTPS instance of the
ARES token broker (or an equivalent reviewed implementation) configured with that client's ID and
secret. In ARES, open **Advanced administrator settings**, enable the custom-client option, and enter
the public client ID ending in `.apps.googleusercontent.com` plus the organization's broker URL.
Do not enter the secret in ARES.

The custom client changes consent branding, quota, publishing policy, application identity, and who
operates the token broker. It does not change who owns Drive data or bypass folder and Shared Drive
permissions. The broker client ID must exactly match the ID entered in ARES. If Google reports
`deleted_client`, disable the custom option to return to managed sign-in or replace the custom
client and broker configuration together.

## Secret and broker operations

- Restrict deployment and secret access to release administrators; students do not need Google
  Cloud access.
- Inject the secret from the cloud secret manager rather than a plaintext deployment file.
- Do not enable request-body logging, tracing, or exception dumps on the two exchange routes.
- Keep the broker URL stable across installer releases. A URL change requires rebuilding the
  official installer or deliberately configuring the custom-client path.
- A `broker_unavailable` response means the gateway lacks usable protected configuration. Repair
  the service; do not ask a student for a secret.
- On secret rotation, update the broker atomically, verify sign-in and refresh, then disable the old
  secret through the protected procedure.
- Deploy reviewed gateway changes only from protected `main` with the **Deploy Analytics Gateway** workflow.
  Configure its `gateway-production` environment with required reviewers and the public variables
  `GOOGLE_WORKLOAD_IDENTITY_PROVIDER` and `GOOGLE_GATEWAY_DEPLOY_SERVICE_ACCOUNT`. The OIDC service
  account needs `roles/cloudbuild.builds.editor`, `roles/serviceusage.serviceUsageConsumer`, and
  `roles/run.viewer`. Give it `roles/storage.objectAdmin` and
  `roles/storage.legacyBucketReader` only on the dedicated `aresfirst-portal_cloudbuild` source
  bucket. The workflow pins `--gcs-source-staging-dir` to that bucket so this identity does not need
  project-wide bucket listing or storage access. The broker must use an enabled default URL and
  Cloud Run's explicit `--no-invoker-iam-check` public-service mode; route validation, PKCE, request
  limits, and rate limits remain enforced by the application. Do not store a service-account key in
  GitHub.

## Release verification

Before publishing an installer:

1. Confirm consent-screen branding verification and publishing status in Google Cloud.
2. Confirm the Drive API and exact requested scopes are configured.
3. Confirm the production broker has the matching protected client ID/secret and passes
   `/health`; confirm the installer contains the stable HTTPS broker URL but no secret.
4. Build the MSI through the protected workflow so the managed ID and broker URL are present.
5. On a clean Windows user profile, complete one-click sign-in with the production client.
6. Create a personal/team destination and upload/download a small test session.
7. Select an existing shared folder through Picker and repeat the round trip.
8. If the account supports Shared Drives, select a folder inside one and repeat the round trip.
9. Revoke access, remove folder permission, switch accounts, and verify ARES fails visibly while
   local authoring and analysis remain available.
10. Sign out and verify the current-user vault credential is removed (DPAPI, Keychain, or Secret Service).

Do not publish merely because unit tests, broker tests, and packaging pass. Production OAuth consent
and Drive round-trip verification with the active client are release gates. Until that manual
end-to-end check succeeds, the installer is a release candidate, not a verified Google sign-in
release.
