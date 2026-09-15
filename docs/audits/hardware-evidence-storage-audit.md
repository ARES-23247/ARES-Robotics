# Hardware evidence ownership and exclusive publication

Pass 197, 2026-09-12. This pass covers linked evidence paths and append races left
open by pass 196. All changes belong to Studio; the ARES library candidate and
existing JSON record formats are unchanged.

## Confirmed fixes

Both evidence directories and listed record paths now resolve existing links
against the selected project's real root, including when the final path has not
yet been created. A directory pointing outside that root cannot supply an accepted
review or receive a new record. Inspection rejects the path; the view-model error
path and deployment inspection fail closed. Opening the project itself through a
link remains supported: its resolved root defines ownership.

The previous append path checked existence and then used a replacement helper.
A file created between those steps could be overwritten. Evidence publication now
uses a dedicated store that creates a new directory entry for complete, flushed
temporary bytes. An existing entry wins; it is never opened for replacement. The
generic atomic-replacement helper retains its existing contract for other callers.

Java specifies that existing-target behavior under `ATOMIC_MOVE` depends on the
implementation and ignores other options. Dropping `REPLACE_EXISTING` alone is
therefore insufficient. The new store uses hard-link creation where available,
with an exclusive `CREATE_NEW` write fallback. That option atomically arbitrates
creation and rejects an existing file or symbolic link. See the official
[Java 17 Files API](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/file/Files.html).

The fallback supports providers that reject hard links without permitting
replacement. It has weaker publication visibility: readers can briefly encounter
incomplete JSON, which existing evidence decoding rejects. A write failure may
leave an incomplete record for diagnosis; it is not silently deleted or replaced.
Temporary files are cleaned up after normal publication, duplicates and failures
before publication. File data is force-flushed and directory durability remains
best effort where the platform does not support directory flushing.

## Evidence

A publication hook was first added without changing the old storage behavior.
Six baseline tests then ran: five failed. Four exercised configuration/physical
reads and writes through actual Windows directory junctions outside the selected
project. The fifth deterministically inserted another writer's record after
preparation and observed its replacement. The identical-concurrent-append case
already passed on this Windows provider; that result was not used to claim a
portable no-replacement contract.

Final focused coverage includes both real-service storage paths, two concurrent
writers, a destination appearing during publication, linked project roots, failed
publication cleanup, exact UTF-8 bytes, repeated append preservation and forced
hard-link failure/unsupported-provider fallback. Fallback tests also place a
competing destination after preparation. Each link is explicitly removed before
its owned temporary test tree is deleted; executor workers are shut down and joined.

All 48 focused tests passed: eight storage integration cases, five store cases,
five submitted-inventory persistence cases, 18 existing hardware-service cases and
12 lifecycle cases. Full Studio validation passed with 1,891 tests: 1,885 successful
executions, zero failures/errors and six opt-in/environment skips (three generated-
project integrations, native file chooser, performance baseline and physical
dashboard target). Repository policy passed, including links in 358 current
documents and 38 excluded historical records. Evidence is under
`ARESLib-Kotlin/build/audit-pass197-verified-evidence/`.

## Scope and remaining work

The new store and tests were read completely, and the service's list/read/append
boundary was rechecked. Real-directory tests ran on Windows with junctions; the
Unix symbolic-link branch was not executed here. Fallback failures were injected
against owned local test files, not a physical FAT/network/cloud filesystem.

Canonical path checks are point-in-time checks, not directory-handle transactions.
Continuously hostile parent-directory replacement between a check and an OS call
is not proven safe by this pass, and the store remains partial for that boundary
and interrupted fallback-write recovery. Record ordering, timestamps and semantic
record validation, physical source ownership, cross-file inspection consistency,
XRP address namespaces and CAN/bus identity also remain in the service backlog.
The generic atomic-replacement and project-document writer implementations were
read at their relevant boundaries; this pass does not close their full coverage.
No hardware, visible Studio window, release or robot runtime was exercised.
