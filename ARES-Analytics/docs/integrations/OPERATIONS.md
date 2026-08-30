# Studio Integrations Operations

## Architecture

All integrations run on the desktop after data is persisted locally. FTC and FRC robot code remains offline-first and never contacts Zulip, a webhook, a CMS, Google Drive, or an AI provider.

Studio-recorded simulator sessions are also local by default. Their `simulation` tag suppresses all
automatic Zulip, webhook, notebook-publisher, and cloud-completion notification deliveries while
preserving local recording, analysis, alerts, notebook drafts, replay, comparison, and reports.
Explicit integration tests and explicit publication remain separate user actions.

Studio writes an immutable domain event and one durable delivery row per configured destination. Delivery uses leases, bounded retries, stable event IDs, and provider idempotency keys. A notification or publisher outage therefore cannot turn a successful import, analysis, alert, or cloud upload into a failed local operation.

## Configuration and credentials

Non-secret configuration is stored in `~/.ares-analytics/integrations.json`. Credentials are stored separately in `integrations.dpapi` on Windows using current-user DPAPI. Other supported desktop platforms use an owner-only secret file. Credentials must be provisioned through `IntegrationSettingsService.saveCredential`; do not put them in `integrations.json`.

A representative configuration is:

```json
{
  "notificationProviders": [
    {
      "providerId": "team-zulip",
      "displayName": "Team Zulip",
      "kind": "ZULIP",
      "enabled": true,
      "eventTypes": ["ROBOT_ISSUE_OPENED", "ANALYSIS_READY", "CLOUD_UPLOAD_COMMITTED"],
      "minimumIssueSeverity": "WARNING",
      "zulip": {
        "siteUrl": "https://example.zulipchat.com",
        "stream": "robot-status",
        "topic": "ARES Studio"
      },
      "webhook": null
    }
  ],
  "notebookPublishers": [
    {
      "publisherId": "team-notebook",
      "displayName": "Local engineering notebook",
      "kind": "LOCAL_MARKDOWN",
      "enabled": true,
      "localDirectory": "C:/Users/example/Documents/engineering-notebook",
      "driveFolderName": "engineering-notebook",
      "cmsEndpoint": null,
      "requireApproval": false
    }
  ],
  "schemaVersion": 1
}
```

For Zulip, save a credential whose `principal` is the bot email and whose `secret` is the bot API key. For a generic webhook, the secret is the HMAC-SHA256 signing secret. For a CMS publisher, the secret is its least-privilege installation bearer token. Provider IDs are stable identifiers and must match between settings and the protected credential store.

After changing settings or credentials, call `NotificationIntegrationService.reload()`. Invalid providers remain disabled and appear in `configurationErrors()` without stopping valid providers or local operation.

## Provider behavior

- Zulip posts HTTPS stream messages using bot basic authentication and a configured topic. Robot issues below the configured severity are filtered locally.
- Generic webhooks receive a versioned JSON envelope, stable idempotency key, timestamp, and HMAC-SHA256 signature.
- Local Markdown and Google Drive publishers use content-addressed filenames, so retries resolve to the same artifact.
- CMS publishers submit only human-approved revisions over HTTPS. The website always receives a draft; see [ARES-WEB-NOTEBOOK-API.md](ARES-WEB-NOTEBOOK-API.md).

Secrets and raw authorization failures are never included in notification content, notebook exports, delivery receipts, or logged error messages.

## Delivery recovery

`PENDING`, `IN_FLIGHT`, and `RETRY` deliveries survive restart in DuckDB. Expired leases become claimable again. Authentication, configuration, and invalid-payload failures become terminal `DEAD` deliveries; transient and rate-limit failures retry with bounded backoff. Provider-side idempotency prevents duplicate effects after an ambiguous response.

When diagnosing a delivery:

1. Inspect the provider's configuration error first.
2. Confirm the credential exists for the exact provider ID.
3. Test HTTPS reachability and the provider account independently.
4. Correct settings or rotate credentials, then reload integrations.
5. Retain the original event and receipt rows for audit; do not edit immutable event payloads in place.

## Engineering notebook and AI

Every notebook edit creates a sequential immutable revision with a content hash and evidence references. AI drafting is optional. The deterministic draft remains the fallback if AI is unavailable or returns unsupported, uncited, oversized, or otherwise invalid structured output.

AI output always remains `DRAFT`. `VERIFIED_IMPROVEMENT` claims require cited test or measurement evidence, and a human must approve a revision before a CMS adapter will submit it. Public publication remains a separate CMS editorial action.

## Windows update operations

Studio may stage Windows MSI assets only when all of the following hold:

- the GitHub release is stable and contains exactly one MSI and its named `.sha256` file;
- both asset URLs are GitHub HTTPS release URLs;
- the declared size is bounded and sufficient disk space exists;
- the streaming download has the expected size and SHA-256 digest;
- the installer satisfies the trust policy compiled into the installed Studio build:
  - official bootstrap builds verify the exact MSI against the named SHA-256 asset from the same immutable GitHub release;
  - once trusted signer thumbprints are configured, Studio additionally requires a valid Authenticode signature from one of those pinned certificates and never falls back to checksum-only trust.

Public release jobs select one explicit Windows trust mode. `checksum` is the bootstrap mode while no publicly trusted certificate exists. `authenticode` requires:

- protected secret `WINDOWS_SIGNING_PFX_BASE64`;
- protected secret `WINDOWS_SIGNING_PFX_PASSWORD`;
- repository variable `ARES_WINDOWS_UPDATE_SIGNER_THUMBPRINTS`, containing one or more comma-separated SHA-1 certificate thumbprints.

The release workflow signs and verifies the MSI before checksums are generated when `authenticode` is selected. It rejects that mode if any protected setting is absent. In either mode it publishes `windows-installer-trust.json` with the exact digest and honest signature status. Signing private keys never enter source or application artifacts.

Installation requires an explicit user action and an empty safety-blocker list. Studio re-verifies the staged digest and, when configured, the pinned signature, starts a separate hidden helper, and then follows the normal bounded shutdown path. The helper waits for Studio to release its single-instance lock, verifies the MSI under the same policy again, invokes `msiexec /passive /norestart`, records `install-result.json`, and relaunches the installed executable. A build with pinned signer thumbprints never downgrades to checksum-only trust.

Recovery behavior:

- Interrupted downloads retain only a `.partial` staging file and can resume.
- Digest or signer failures delete the executable staging file and require a fresh download.
- A busy robot, simulator, import, analysis, delivery, database migration, or dirty critical state defers installation without deleting the verified package.
- Installer failure leaves the current installation maintained by Windows Installer and records a recovery result beside the staged package.
- Exit code `3010` is recorded as restart-required; the application is still relaunched so the user can save work and reboot deliberately.
- Manual download from the GitHub release page remains the fallback for unsupported platforms or helper failure. Windows may display an unknown-publisher warning for an honestly disclosed checksum-bootstrap release.

The stable MSI `upgradeUuid`, runtime snapshot, single-instance lock, Compose window ownership, and shutdown watchdog are unchanged.
