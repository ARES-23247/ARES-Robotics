# ARES-Analytics Gateway Audit Report

## 1. Executive Summary
This report outlines the findings from a comprehensive security and architecture audit of the Ktor Cloud Gateway (`ARES-Analytics/gateway`). The focus of the audit was to verify the Firebase + GitHub OAuth role validation, Delta Sync logic, and GCS Signed URL generation security, comparing the implementation against the architectural constraints detailed in `ARCHITECTURE.md`.

## 2. Strengths
- **Decoupled Identity Provisioning**: The gateway successfully leverages Firebase for initial authentication and seamlessly enriches user claims via GitHub OAuth.
- **Time-bound GCS Signed URLs**: The gateway securely delegates direct-to-cloud file uploads by generating short-lived (15-minute) pre-signed URLs with restricted `PUT` methods, minimizing exposure windows.
- **Efficient Delta Computation**: The delta sync logic properly queries Firestore and filters against the client's `knownSessionIds` to ensure only the missing summary payloads are returned, maintaining the lightweight multi-client summary sync architecture.

## 3. Findings

### Finding 1: Broken Role Validation due to Firestore Collection Mismatch (High Severity)
**Description:** In `AuthRoutes.kt`, upon successful GitHub token verification, the gateway provisions or updates the user profile with their computed `role` (VIEWER or ADMIN) and `githubOrgs` into the `users` Firestore collection. However, the `isUserAdmin` helper function in `ArchiveRoutes.kt` attempts to verify administrator privileges by querying a completely different collection: `authorized_users`.
**Impact:** Because the collections differ, `userDoc.exists()` will always evaluate to false. Legitimate mentors and administrators will be completely locked out from adding or deleting robot profiles via the `/api/team/robots/*` routes.

### Finding 2: Missing Tenant Authorization (Data Leakage & Forgery) (Critical Severity)
**Description:** The gateway is designed for strict hierarchical workspace isolation (`team_id -> season_id -> ...`). While the `/api/archive/upload-url` and `/api/archive/sync` endpoints verify that the user possesses a valid Firebase token, they do *not* cross-reference the user's authorized GitHub organizations against the requested `teamId`.
**Impact:** Any user with a valid Firebase account can request GCS Signed URLs and upload forged telemetry data to any team's workspace. Similarly, they can call the sync endpoint to pull down session summaries for arbitrary teams, resulting in severe data leakage.

### Finding 3: Lack of Tenant Namespacing in GCS Signed URLs (Medium Severity)
**Description:** The signed URL generation uses a flat directory structure path: `telemetry/${req.sessionId}.parquet`.
**Impact:** Without tenant namespacing, global session ID collisions across different teams could theoretically result in data overwrites in the GCS bucket. Furthermore, a flat structure prevents the application of strict, team-based bucket lifecycle policies or IAM boundaries in the future.

## 4. Prioritized Roadmap to Compliance

1. **Unify Firestore Collections (Immediate)**
   - **Action:** Update the `isUserAdmin` function in `ArchiveRoutes.kt` to point to the `users` collection instead of `authorized_users`:
     `db.collection("users").document(uid).get().get()`

2. **Enforce Tenant Isolation in Delta Sync & Uploads (Immediate)**
   - **Action:** Introduce an `isAuthorizedForTeam(db, uid, targetTeamId)` helper function.
   - **Implementation:** The helper must fetch the user's document from the `users` collection and verify that `targetTeamId` is present within their `githubOrgs` string array.
   - **Integration:** Inject this authorization check into `/api/archive/sync` (using `req.teamId`) and `/api/archive/upload-url` (using `req.summary.teamId`) before processing Firestore queries or issuing Signed URLs.

3. **Namespace GCS Upload Paths (Short-term)**
   - **Action:** Modify the `BlobInfo` builder in `/api/archive/upload-url` to namespace telemetry uploads strictly by team and season.
   - **Target Path:** `telemetry/${req.summary.teamId}/${req.summary.seasonId}/${req.sessionId}.parquet`
