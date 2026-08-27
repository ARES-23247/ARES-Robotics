# Phase 2 Gateway Audit Report: Ktor Cloud Gateway

**Target:** `ARES-Analytics/gateway`
**Focus:** Payload robustness, edge cases (Parquet parsing), and rate limiting vulnerabilities.
**Reviewer:** Lead Code Reviewer, ARES-Analytics

---

## 1. Strengths

* **Edge-First Parquet Processing:** The system architecture cleanly offloads the generation, flattening, and parsing of complex Parquet data matrices entirely to the Desktop (edge) client. By brokering direct-to-GCS Pre-Signed URLs in `ArchiveRoutes.kt` rather than passing raw file payloads through the Ktor server, the Gateway is completely immunized against Parquet-related memory exhaustion, schema mismatch errors, and parsing crashes.
* **Stateless Firebase Authorization:** The application utilizes Firebase Admin for stateless JWT validation. Authentication logic properly checks `githubOrgs` claims against `teamId` targets before issuing signed URLs or performing Firestore syncs, preventing cross-tenant data leakage.
* **Non-Blocking Architecture:** Ktor's native coroutine-based architecture, combined with the Netty engine, ensures that downstream I/O calls to Firestore, Vertex AI, and Google Cloud Storage do not block the underlying thread pool.

---

## 2. Findings & Vulnerabilities

### Finding 2.1: Complete Lack of Rate Limiting (Denial of Wallet Risk)
**Severity: CRITICAL**
The `Application.kt` configuration does not install the Ktor `RateLimit` plugin. Even though endpoints are protected by `authenticate("firebase")`, a compromised client, a malicious user, or a bug in the desktop app (e.g., an infinite sync loop) can spam the API. 
* `/api/diagnostics/forensics` directly invokes the Vertex AI LLM (`gemini-1.5-flash`), meaning unbounded requests will result in massive, uncontrolled Google Cloud billing spikes.
* `/api/archive/upload-url` triggers both Firestore writes and GCS token generation, risking database quota exhaustion.

### Finding 2.2: LLM JSON Output Parsing Brittleness
**Severity: HIGH**
In `DiagnosticsRoutes.kt`, the Vertex AI response is extracted and directly fed into `Json.decodeFromString<ForensicsResponse>(jsonResponse)`. Even with strict prompt instructions, LLMs frequently wrap JSON responses inside Markdown code blocks (e.g., ` ```json { ... } ``` `). If this occurs, Ktor's Kotlinx Serialization will throw a `SerializationException`, crashing the endpoint and returning an unhandled 500 error to the client.

### Finding 2.3: Unbounded JSON Payloads and Missing Validation
**Severity: MEDIUM**
Routes utilize `call.receive<T>()` without the Ktor `RequestValidation` plugin and without global payload size constraints. 
* A massive, maliciously crafted `ForensicsRequest` payload could exceed the Vertex AI context window limit (causing a crash) or exhaust JVM memory inside the lightweight Cloud Run container (causing an OOM restart).
* There is no sanitization against prompt injection. A user could submit a `ForensicsRequest` with string properties specifically engineered to override the AI's system prompt.

### Finding 2.4: Information Leakage in Global Error Handler
**Severity: LOW**
In `Application.kt`, the `StatusPages` plugin catches all `Throwable` exceptions and responds with: `call.respondText(text = "500: Internal Server Error: ${cause.message}")`. Passing raw exception messages directly to the client can leak sensitive internal infrastructure details, stack trace hints, or database schema structures.

---

## 3. Prioritized Roadmap to Compliance

**Phase 2.1: Immediate Infrastructure Protection (Within 24 Hours)**
1. **Implement Ktor Rate Limiting:** 
   * Add the `io.ktor.server.plugins.ratelimit.RateLimit` plugin to `Application.kt`.
   * Configure token bucket limits uniquely keyed by `call.principal<FirebasePrincipal>()?.uid`.
   * Apply strict limits (e.g., 5 requests/minute) for the expensive `/api/diagnostics/forensics` route, and moderate limits (e.g., 30 requests/minute) for `/api/archive/sync`.

**Phase 2.2: Payload Robustness (Within 3 Days)**
2. **Sanitize LLM Responses:**
   * Modify `DiagnosticsRoutes.kt` to strip Markdown formatting from the Vertex AI output before decoding. 
   * Example: `val cleanJson = jsonResponse.substringAfter("```json").substringBeforeLast("```").trim()`
   * Wrap the decode step in a `try/catch` block that returns a graceful degradation response rather than a 500 server crash if the model hallucinates a non-JSON response.
3. **Enforce Payload Validation:**
   * Install the Ktor `RequestValidation` plugin.
   * Assert reasonable bounds on incoming DTOs (e.g., limit string lengths on robot configurations, cap the array size of `knownSessionIds` in the `SyncRequest`).
   * Configure Ktor to reject HTTP bodies exceeding a sane threshold (e.g., 2MB) to protect Cloud Run container memory.

**Phase 2.3: Security Polish (Within 1 Week)**
4. **Secure Error Handling:**
   * Update `StatusPages` to log `cause.message` securely to standard out (which flows to Google Cloud Logging) using SLF4J, while returning a static, sanitized string to the HTTP client: `"An internal error occurred."`
