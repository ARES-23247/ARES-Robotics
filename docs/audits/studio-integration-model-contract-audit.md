# Studio integration model contract audit - pass 144

Reviewed all fields, enums, defaults and custom diagnostic rendering in
`IntegrationSettingsModels`, `GoogleOAuthBrokerModels` and `WorkspaceModels`.
No production defect was established in these declarations.

## Configuration contracts

Empty integration settings contain no destinations. Notification targets distinguish
Zulip and webhook payloads; the settings service validates schema, unique IDs,
nonempty event routing, matching target kind and HTTPS URLs without user information.
CMS publishers default to requiring approval, and the service separately rejects
CMS configurations that explicitly disable it. No publisher or notification was run.

Drive destinations retain explicit folder/account identity. Their default collaboration
mode is personal only for a personal folder; all other destination types default to
team. Workspace optional cloud configuration defaults to absent, custom OAuth remains
opt-in, and the existing custom `toString` avoids rendering secret-bearing fields.
Existing tests cover synthetic-secret redaction and legacy workspace defaults.

Added two real-serializer tests for the dependent defaults: all publisher kinds when
approval is omitted, empty integration settings, and all Drive destination kinds when
collaboration mode is omitted. Drive destination round trips retain the resulting mode.

## OAuth wire contract

The desktop and gateway share authorization-code/PKCE and refresh request models.
The gateway maps Google's snake-case response into the shared camel-case response;
the desktop reads the same response type. Optional refresh/identity tokens remain
nullable and token type defaults to Bearer. Existing mocked gateway tests cover code
and refresh exchanges, invalid inputs, missing configuration and safe error mapping.

These DTO reviews do not certify live provider availability, Google permissions,
token-lifetime arithmetic in consumers, or the referenced AI provider model's current
availability. Data-class fields alone do not enforce endpoint or credential policy;
the identified receiving services own that validation.

## Validation

All 27 shared tests pass; the unchanged 18-test gateway suite is up-to-date and passing.
Policy, documentation links and staged whitespace checks pass. Only tests and audit
records changed, so the unchanged app suite was not rerun. No network exchange,
external notification, publication, device action or physical validation was performed.

Logs, XML and reviewed source hashes are retained under
`ARESLib-Kotlin/build/audit-pass144-verified-evidence/` with unchanged library candidate
`17.0.3-rc.100852e472fb`. Changes remain local.
