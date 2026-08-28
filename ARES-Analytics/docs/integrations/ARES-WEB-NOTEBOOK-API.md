# ARES Engineering Notebook Draft API

Status: Studio client implemented; ARESWEB server implementation is a separate-repository handoff.

## Purpose and portability boundary

ARES Robotics Studio treats a team website as one optional notebook publisher. The durable notebook model, review state, evidence, local Markdown export, Google Drive export, notifications, and retry queue remain owned by Studio. No ARESWEB or Firestore types are allowed into the shared model.

ARESWEB may implement the HTTP contract below to accept a reviewed Studio revision as a CMS draft. Other teams can point the same adapter at a compatible CMS or omit CMS publishing entirely.

## Endpoint

`POST /api/integrations/robotics-studio/v1/notebook-drafts`

Required request headers:

- `Authorization: Bearer <installation-token>`
- `Content-Type: application/json`
- `Idempotency-Key: <entryId>:<contentHash>`
- `User-Agent: ares-robotics-studio-integrations/1`

The endpoint must use HTTPS. The bearer token is an installation credential scoped only to creating and reading the status of engineering-notebook drafts. It must not be a user session, service-account key, or general CMS administration credential.

## Request body

The server must reject unknown schema versions and bodies larger than 1 MiB. The current request envelope is:

```json
{
  "schemaVersion": 1,
  "entry": {
    "entryId": "software-change-2026-08-27-a1b2c3",
    "revision": 1,
    "entryType": "SOFTWARE_CHANGE",
    "workspace": {
      "teamId": "23247",
      "seasonId": "2026",
      "robotId": "competition"
    },
    "markdownBody": "# Change summary\n\n...",
    "evidence": [
      {
        "kind": "git_commit",
        "referenceId": "a1b2c3d4",
        "sha256": null,
        "label": "Source commit",
        "uri": null
      }
    ],
    "visibility": "PRIVATE",
    "reviewState": "APPROVED",
    "humanAuthorId": null,
    "humanReviewerId": "local-reviewer",
    "aiProvenance": null,
    "contentHash": "<64 lowercase hexadecimal characters>",
    "createdAtMs": 1787860800000,
    "updatedAtMs": 1787860800000,
    "schemaVersion": 1
  }
}
```

The server must independently recompute the content hash using the versioned Studio canonical model before accepting the request. It must reject a hash mismatch, a non-`APPROVED` review state, invalid identifiers, unsupported enum values, or Markdown that exceeds its configured limit.

## Authorization and ownership

The installation token determines the CMS organization/team, allowed robot workspaces, and the server-side actor recorded in the audit log. The request must not be permitted to choose CMS roles, organization membership, publication state, author permissions, or raw database/Firestore document paths.

An accepted request always creates or resolves to a CMS **draft pending human editorial approval**. It never directly publishes a public post. ARESWEB owns its editorial workflow and may map Studio visibility into a more restrictive CMS visibility, never a less restrictive one.

## Successful response

Return `201 Created` for the first accepted submission and `200 OK` for an idempotent replay:

```json
{
  "draftId": "eng-2026-0042",
  "reviewUrl": "https://aresfirst.org/admin/engineering-notebook/eng-2026-0042",
  "contentHash": "<the accepted content hash>",
  "duplicate": false
}
```

`draftId` is required. `reviewUrl` is optional, but if present must be an HTTPS URL. Studio records these values as a publication receipt; it does not infer that the draft is publicly published.

## Idempotency and revisions

The unique idempotency record is `(installationId, Idempotency-Key)`. Replaying the same key with the same canonical body returns the original draft and `duplicate: true` without creating a second CMS revision. Reusing the key with a different body returns `409 Conflict`.

Each Studio revision has a new content hash and therefore a new idempotency key. ARESWEB should associate revisions with the stable `entryId`, preserve prior revisions for audit, and expose only the revision selected by its editorial workflow.

## Error responses

All error bodies should use a small, non-secret JSON shape such as `{"code":"invalid_entry","message":"..."}`.

| Status | Meaning | Studio behavior |
|---|---|---|
| `400` / `422` | Invalid envelope, schema, hash, or review state | Permanent rejection; human correction required |
| `401` / `403` | Invalid, expired, or insufficiently scoped token | Permanent authentication failure until reconfigured |
| `404` | Endpoint is unavailable for this installation | Configuration failure |
| `409` | Idempotency key was reused for different content | Permanent integrity failure |
| `413` | Body exceeds the server limit | Permanent payload failure |
| `429` | Installation rate limit exceeded | Retry using `Retry-After` when present |
| `5xx` | Temporary CMS failure | Retry with Studio's bounded exponential backoff |

## Security and operations requirements

- Store only a salted hash of each installation token. Show the clear token once at creation and support rotation/revocation without changing the installation ID.
- Apply per-installation and per-IP rate limits, a 1 MiB request limit, strict JSON parsing, and Markdown sanitization at render time.
- Record token ID, installation ID, entry ID, revision, content hash, result, timestamp, and CMS draft ID in an append-only audit trail. Never log the bearer token or full private notebook body.
- Keep server-derived ownership and editorial approval checks inside the same transaction that creates the draft and idempotency record.
- Do not fetch evidence URIs automatically. Treat all Markdown and evidence labels as untrusted content.
- Return generic authentication failures and do not reveal whether another team's entry or draft exists.

## ARESWEB acceptance tests

The server repository should add emulator/integration tests proving:

1. A valid approved revision creates one pending draft.
2. An identical retry returns the same draft without duplication.
3. The same idempotency key with altered content returns `409`.
4. Draft, reviewed, or malformed entries cannot bypass approval or ownership checks.
5. A token for one team cannot submit to another team's workspace.
6. Revoked, expired, and insufficiently scoped tokens fail without leaking details.
7. Oversized bodies, unknown fields/schema versions, and invalid hashes are rejected.
8. Rate limiting returns `429` and a usable `Retry-After` value.
9. Stored and rendered Markdown is safe against script and link injection.
10. Audit records contain identifiers and outcomes but no credential or private body content.

## Studio implementation reference

The portable client is `CmsNotebookPublisher` in `app/src/main/kotlin/com/ares/analytics/service/integration/NotebookPublishers.kt`. Non-secret endpoint configuration is stored separately from its protected installation credential. CMS delivery is disabled unless explicitly configured and only approved notebook revisions are eligible.
