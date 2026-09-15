# NT4 typed values and control parsing audit

Pass 228 reviews the NetworkTables entry/value model, JSON control parsing and standalone
binary codec, plus the server's shared value writer. Source commit
`bc586334dd441f45cbaf081759134781504903ab` was validated as local candidate
`17.0.35-rc.2295478af864`, library tree
`2295478af864c7a4426c6138bedc044a701548cd`.

The [official NT4 specification](https://github.com/wpilibsuite/allwpilib/blob/main/ntcore/doc/networktables4.adoc#supported-data-types)
defines distinct integer, float32, float64 and homogeneous-array wire types. Control fields
belong to their message's params object, with subscription prefix inside options.
The fixes below concern ARES's implementation of those contracts.

## Findings and fixes

- **Integer precision and float width were lost.** The standalone encoder wrote every Number
  as float64, including Long values above the exact-double integer range and Float values.
  It now writes the declared type: signed integers remain exact through Long.MIN/MAX, and
  floats use float32. Signed zero, tiny float values, infinity and NaN remain valid transport
  values. Physical-control validity still belongs to the consuming robot controller.
- **Array updates became nil.** Supported primitive/string arrays fell into the encoder's
  fallback branch. A shared writer now covers every exposed scalar and array family, plus
  explicit binary payloads. Invalid type/value combinations and null are rejected, rather
  than producing an unrelated scalar, truncated integer or nil.
- **Declared incoming types were ignored.** Integer topics could contain fractional doubles;
  homogeneous arrays could contain strings or nested arrays. Decoding now checks each carrier
  while reading it. Arrays stay List values for the existing client API; numeric carriers on
  float-typed topics remain accepted for interoperability. Malformed/unsupported frames return
  no prefix updates, and existing byte/string/array/message caps apply before allocation.
- **Nested JSON fields impersonated control fields.** Text search could treat metadata as the
  outer method/params, or take name/type/UID/prefix from unrelated properties. The parser now
  traverses object structure once, reads only the relevant direct members and validates required
  control fields. Unknown messages are ignored. The existing single-object frame extension is
  preserved alongside standard arrays of messages.
- **IDs and topic names were corrupted while parsing.** Fractional numeric IDs were truncated,
  exponent forms were read as their leading integer, and a closing bracket inside a topic
  string ended the topic list early. UID conversion is exact and bounded to Int, with a fast
  ordinary-integer path; exact decimal/exponent forms remain accepted. The tokenizer handles
  escaped strings and brackets. Public extraction helpers inspect direct members in an
  inclusive object range and check range bounds.
- **Incomplete JSON frames yielded valid prefixes.** Duplicate members, trailing data, missing
  separators and incomplete containers now fail before any messages are returned. Work is
  bounded by 1,048,576 characters, 1,024 messages, 256 requested topics, depth 32 and 65,536
  visited values, including skipped metadata. The streaming reader uses FTC-compatible Gson
  legacy strict mode and does not construct an object tree.
- **Object conversion silently changed data.** Extended Number values could overflow or lose a
  fractional component through toLong. They now require an exact signed Long conversion.
  Already typed NT4Value instances retain their type and snapshot. Java-origin string arrays
  containing null fail at construction rather than later during publication.

The standalone helper and server use one binary value writer, and the helper no longer stages
bytes through an extra ByteArrayOutputStream. Typed decoding avoids constructing nested lists
and rescanning accepted arrays. IntArray conversion uses a primitive loop instead of an
intermediate boxed list. NT4Entry's documentation now matches its synchronized update and
exception-isolation behavior. These are source-backed reductions in redundant work; this pass
does not report a measured throughput, allocation total or physical loop-time improvement.

## Validation

The original implementation failed 18 of 26 baseline cases. The baseline XML/logs were preserved
before production edits. The final focused run passes 58 cases, including 29 new methods:
NT4WireValueAuditTest9, NT4JsonStructureAuditTest11 and NT4EntryValueAuditTest9.

Independent MessagePack reads check integer precision and float width; the existing server
typed decoder checks every exposed value family through the shared writer. Other cases cover
array ownership/equality/hash behavior, timestamps, listeners, authoritative replacement,
JSON object boundaries, escapes, exact IDs, malformed frames and input budgets.

Two existing fixtures were corrected without changing test counts. A subscription fixture now
supplies its required subuid. Array/blob/count limit fixtures now use the matching declared
type and valid boolean payloads; the message-count test proves acceptance at 1,024 messages
and rejection of the next message, so type rejection cannot mask the count boundary.

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| ARESLib | 2,760 | 0 |
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

The suites account for 5,592 passing results, zero failures/errors and six unchanged Studio
opt-in skips. These cover three starter integration scenarios, native file chooser, dashboard
performance baseline and physical dashboard validation. Gradle results may be executed,
up-to-date or restored from cache; focused results are not counted twice.
FTC/starter generated-project checks and APK assembly passed; FRC/starter generated-project
checks passed. All 410 candidate file hashes were reverified after consumers finished.
Monorepo policy passed, including source/version/archive identity, shared guidance and links
in 390 current documents, with 38 explicitly historical records skipped. Four normalized
starter archive comparisons differ only in release version properties.

## Coverage and limits

The ledger accounts for 3,004 tracked files: 1,297 fully reviewed, 175 partially reviewed
and 1,532 pending, with zero stale or orphaned records. This is file review and appropriate
validation accounting, not universal executable coverage.

Full reviews cover NT4Entry, NT4Value, NT4Json, NT4WireProtocol, both new internal codec helpers,
all three new test files, NT4ValueOwnershipTest and NT4ServerTest. NT4Server remains partial:
this pass changes its value-writer delegation and verifies the associated encoding paths.
NT4NetworkingHardeningTest is partial: its limit fixture and directly relevant interoperability
cases were inspected and all 20 methods executed; unrelated cases are not claimed reviewed.
NT4Instance is partial: facade delegation was traced, but concurrent replacement/close,
asynchronous bind-failure ownership and the surrounding singleton lifecycle need their own pass.

This is not full NT4 implementation conformance. Existing server publisher support, properties,
other subscription options, lifecycle/concurrency and clock normalization remain outside this
fresh slice. Binary IDs 7/8 are retained as legacy ARES aliases; standard binary is ID 5.
The public value wrappers retain their existing supported-type set and fallback for other
objects. This pass does not introduce a raw-value wrapper or arbitrary object serialization.
It also does not claim strict UTF-8 validation of malformed binary string bytes.

No rendered Studio window, physical actuator response, packet timing on robot hardware or HIL
was observed. No remote push, merge, release or deployment occurred.
