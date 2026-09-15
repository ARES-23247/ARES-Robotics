# Hardware registry topology ownership audit

Pass 220 covers the registry's topology registration overloads, replacement rules, metadata
ownership and snapshot construction. It extends the publication and lifecycle work in
[pass 217](registry-publication-polling-audit.md) and the schema/card boundaries in
[pass 218](desktop-drive-topology-contract-audit.md) and
[pass 219](studio-hardware-topology-presentation-audit.md). Power-manager indexed access to
live cached collections and unusual polling-worker termination remain separate open scopes.

## Findings and changes

- **Replacement retained the retired hardware address.** Registering a different motor under
  `arm` without topology kept the previous motor's CAN address. Replacing a generic device
  could also leave its retired physical ID behind and prevent another device from using that
  ID in a valid export. Bare replacement now removes metadata for that logical name. Bare
  registration of the same object preserves its known address; surviving aliases keep theirs.
- **Snapshot metadata belonged to the caller.** Mutating or clearing the map passed to
  `registerDevice` changed later exports and already retained snapshots. Registration now
  copies metadata into a read-only map. Exported node lists are also read-only. Retained
  snapshots survive replacement and close without changing. Callers must keep the input map
  stable while registration copies it; arbitrary concurrent mutation of caller data is not
  supported.
- **Invalid node identities could poison export after changing hardware ownership.** The
  registry accepted blank or duplicate physical IDs even though the canonical JSON codec
  rejects them. Identity validation and metadata acquisition now happen before device and
  cache mutation. Failed acquisition leaves the previous registration intact. Explicit
  physical IDs may still differ from logical telemetry names; uniqueness is checked across
  other registrations, and replacing an owner makes its retired ID available again.
- **Topology writes escaped the registration monitor.** Every metadata overload previously
  registered the device under the monitor and then wrote its topology outside it. Another
  registration could interleave and leave the earlier call's address attached to the later
  device. All overloads now use one synchronized private registration operation. A latch-based
  test pauses metadata acquisition and verifies that readers retain the completed snapshot
  and original hardware until acquisition finishes. The old interleaving is established from
  source ordering; this test does not claim to reproduce that scheduling race before the fix.
- **Every topology read copied and sorted every node.** An owned sorted snapshot is now cached
  until topology changes. Sorting is lazy, so construction of a robot does not sort each
  partially registered topology. The CAN display-name helper also uses the last substring
  directly instead of allocating a split list. The public method signatures are unchanged.

The registry metadata map is monitor-owned instead of a concurrent map with writes outside
the registration transaction. A cached snapshot is published through a volatile reference;
cache misses acquire the monitor and recheck before sorting. A read overlapping registration
may return the previous complete snapshot. Separate hardware and topology getters are not a
multi-get transaction. Close retains its existing quiesced-owner contract and clears the cache.

## Focused evidence

`HardwareRegistryTopologyAuditTest` adds nine methods. Seven failed on the previous code;
two controls already passed. Failures include stale replacement metadata, externally mutated
metadata, invalid IDs being accepted, duplicate IDs surviving retirement and repeated read
allocation. The acquisition-failure and latch tests failed because the old implementation did
not acquire metadata at registration at all. They validate the new ownership guarantee rather
than independently demonstrating a vendor IO or thread-scheduling failure.

The final focused run passed all 49 results: the nine new methods, 35 existing registry
methods and five shared allocation regressions. API checks passed. Cases cover all four
address-generating overloads, explicit IDs, sorted order, absent parents, CAN fields, servo
ports, same-object re-registration, replacement, metadata failure and retained snapshots.
The combined identity test failed at its first blank-ID assertion on the baseline; its final
run also executes the remaining blank and duplicate cases. Threads are released and joined
before registry teardown.

On this desktop JVM, 10,000 reads of a 128-node topology allocated **10,960,000 bytes before**
and **240,000 bytes after**, a 97.8% reduction. The remaining allocation is the small per-call
`HardwareTopology` wrapper. The benchmark retains each result through a volatile reference,
so escape analysis cannot erase it. This is a repeated-read allocation observation, not a
robot loop-time measurement. Current production call sites publish topology during setup;
JSON encoding still allocates. Existing focused registry refresh/safety measured zero bytes
per 10,000 pairs and publication measured 832 bytes per 10,000 passes.

## Candidate and validation

Source commit: `0cc4422127b5df80f44896bd119f72452d6e603c`.
Library tree: `3da6b44138f4309be21a560f9ff9a218326f71f5`.
Local candidate: `17.0.26-rc.3da6b44138f4`.

Logs, original/final XML, allocation diagnostics, candidate hashes and archive comparisons are
kept under `ARESLib-Kotlin/build/audit-pass220-verified-evidence/`. Every consumer resolves the
same candidate from the isolated local validation repository.

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| ARESLib | 2,569 | 0 |
| FTC TeamCode | 143 | 0 |
| FTC simulator | 13 | 0 |
| FRC | 305 | 0 |
| FTC starter TeamCode | 13 | 0 |
| FTC starter simulator | 1 | 0 |
| FRC starter | 34 | 0 |
| Studio shared | 31 | 0 |
| Studio gateway | 18 | 0 |
| Studio app | 2,043 | 6 |
| MicroPython host tests | 130 | 0 |
| Repository tooling tests | 101 | 0 |

The suites account for 5,401 passing results, zero failures/errors and six existing Studio
skips. These cover three opt-in starter integration scenarios, native file chooser, dashboard
performance baseline and physical dashboard validation. FTC/starter generated-project checks
and APK assembly passed; FRC/starter generated-project checks passed. All 410 candidate files
were hashed and reverified after consumer validation. Monorepo policy passed, including
source/version/archive identity, shared guidance and links in 381 current documents, with
38 explicitly historical records skipped. Four rebuilt archives differ only in version properties.
Validated results include Gradle up-to-date/cache outputs; focused runs are not counted twice.

## Coverage and limitations

The ledger accounts for 2,966 tracked files: 1,204 fully reviewed, 168 partially reviewed
and 1,594 pending, with zero stale or orphaned records. This pass adds one fully reviewed
regression file and this report; the registry remains partial for the explicitly deferred scopes.
Coverage describes file review and appropriate validation, not universal executable coverage.

This pass does not change robot output, estimator math, current reconciliation or polling
behavior. The live cached motor/current views still require a separate review of indexed
iteration and retained sampler access during registration. Platform-specific valid CAN/port
ranges, complete parent discovery and physical topology accuracy are not inferred here.
No physical robot, native Studio window, remote CI or public release was exercised. No WPILib
defect was established. Changes and validation artifacts remain local; the audit goal remains
active.
