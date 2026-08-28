# Telemetry and logging

## Offline-first data flow

Robot code never depends on internet or cloud credentials:

```text
robot/simulator
  |-- live NT4 telemetry on :5810 ----------> ARES Robotics Studio
  `-- local log HTTP API on :5002 ----------> ARES Robotics Studio
                                                   |
                                                   `-- optional laptop-to-cloud sync
```

Robot-side cloud upload and replay classes are intentionally absent. Do not add robot-to-cloud
uploads, retries, authentication, or archival. A robot match and its logging must continue when WAN
access is absent; the desktop application owns import, replay, and remote synchronization.

## NT4 contract

The custom `NT4Server` uses the NetworkTables 4.1 WebSocket protocol on port `5810`. Its important invariants are:

- Topic names are stored without leading `/`; publish and subscribe paths are normalized.
- A topic's declared type is immutable for the lifetime of its announcement.
- Client writes are accepted only for publisher IDs owned by that WebSocket connection.
- Subscriptions support exact and prefix matching plus unsubscribe.
- Binary decode limits bound frames, message counts, strings, arrays, and raw payloads before allocation.
- Dirty values are flushed as batches. Congested clients retain pending topic identities and receive the newest value when their socket clears.

Code that publishes at loop rate should reuse arrays/buffers and call the telemetry implementation's `update()`/server flush once per frame. Do not publish both `/Topic` and `Topic`; they are the same canonical topic.

### Pose topics

`ARESNetworkStatePublisher` currently publishes the following integration keys:

| Topic | Meaning | Units/type |
|---|---|---|
| `Drive/Odom_X` | Raw odometry X | meters, double |
| `Drive/Odom_Y` | Raw odometry Y | meters, double |
| `Drive/Odom_Heading` | Raw odometry heading | radians CCW+, double |
| `Drive/Pose_X` | Fused EKF X | meters, double |
| `Drive/Pose_Y` | Fused EKF Y | meters, double |
| `Drive/Pose_Heading` | Fused EKF heading | radians CCW+, double |
| `ARES/EstimatedPose` | Fused `[x, y, heading]` | double array |
| `ARES/EstimatedPose/0..2` | Scalar fused X/Y/heading | double |
| `Robot/Odometry/Covariance` | EKF covariance diagonal `[Pxx, Pyy, Ptheta]` | double array |

`TelemetryTopicNormalizer` removes transport-only leading slashes; it does not translate aliases. Every publishing and consuming product uses the canonical names above. The scalar `ARES/EstimatedPose/*` and drive pose topics must carry the same frame and heading sign.

The simulator consumes the atomic leased `ARES/Input/driveFrame` v2 `double[8]` for drive,
mode, alliance, and button state. Alliance is flag bit 5; each new session must begin with a
neutral frame before motion is accepted. Field payloads remain separate non-control topics at
`ARES/Input/obstacles` and `ARES/Input/fieldConfig`. Retired scalar input and heartbeat topics are
not accepted.

The field editor publishes `ARES/Input/fieldConfig` as the single authoritative field mutation.
Simulators rebuild walls, obstacles, element metadata, and AprilTag vision from that document; the
legacy obstacle-only input remains a compatibility surface for older clients, not a second half of
an editor transaction.

Both FTC and FRC simulators publish `ARES/GamePiecesFrame` as an atomic v2 `double[]`:
`[version, count, records..., sequence]`. Each nine-value record is
`[instanceKey, typeKey, x, y, rotation, width, height, shapeCode, colorRgb]`. Keys are stable,
positive integers exactly representable as doubles; translations/sizes are meters and rotation is
CCW-positive radians. The final changing sequence is the commit marker, including at zero count.

## Local log API

`LogManagerServer` is a NanoHTTPD service on port `5002`:

| Method and path | Behavior |
|---|---|
| `GET /` | Local log-management page |
| `GET /api/logs` | Lists unsynced and synced log metadata as JSON |
| `GET /api/download?file=<name>` | Streams a named log |
| `POST /api/delete?file=<name>` | Deletes a named local/synced log |

File names are canonicalized and constrained to the configured log directories. The API is rate-limited per remote IP. Treat it as a trusted local-subnet interface; do not expose port `5002` to the public internet.

Default log storage is `/sdcard/FIRST/telemetry_logs/` on Android and `./logs/` on desktop. The desktop location is relative to the process working directory. Only completed `.csv` and `.csv.gz` telemetry logs are listed or downloadable. Active writer reservations and quarantined abandoned files are deliberately hidden.

## Logging profiles and storage governance

`ARESDataLogger` selects one bounded policy for the process:

| Profile | Default runtime | Sample interval | Rotation | Completed-log budget |
| --- | --- | ---: | --- | ---: |
| `COMPETITION` | FTC Control Hub or FRC roboRIO | 20 ms | 64 MiB or 30 min | 1 GiB |
| `SIMULATION` | Desktop simulator/development | 50 ms | 64 MiB or 15 min | 2 GiB |
| `FORENSIC` | Explicit incident capture | every submitted frame | 128 MiB or 15 min | 4 GiB |

All built-in profiles stream gzip at low CPU cost. Set `ares.logging.profile` or
`ARES_LOGGING_PROFILE` to select a profile explicitly. Misspelled values fail fast instead of
silently falling back. Retention deletes only oldest completed `ares_log_*.csv[.gz]` files owned by
this logger; it never deletes action logs, imported artifacts, active reservations, or abandoned
quarantine files. It also preserves each profile's minimum recent-file count.

At startup, old `.active` files are tested with an operating-system lock. Unlocked reservations
older than 12 hours move to a uniquely named `.abandoned` quarantine. A locked file is considered a
live writer and is never moved based only on age.

Maintenance and validation runs that must preserve an existing directory byte-for-byte may set
`ares.logging.retention.enabled=false` or `ARES_LOG_RETENTION_ENABLED=false`. This is not a logging
off switch: new logs still rotate and finalize, but no automatic pruning occurs. Normal production
defaults to retention enabled.

## Asynchronous CSV logger

`ARESDataLogger` accepts frames into a bounded queue and writes them on one background worker. Operational details matter:

- `logFrame` is non-blocking; a stopped/full logger increments `droppedFrameCount`.
- `stop()` stops acceptance, drains all accepted frames, flushes, and closes the writer.
- Active files end in `.csv.gz.active` by default and become importable `.csv.gz` files only after a successful close.
- Files rotate at the selected profile's size or duration boundary, whichever comes first.
- The first frame establishes stable CSV columns.
- Values and headers use CSV quoting rules.
- Keys first seen after the header are preserved as JSON in `_ExtraFieldsJson` rather than changing row width.
- Pooled mutable maps returned by logger helpers must not be retained by callers after submission.

Monitor `droppedFrameCount` in stress tests. A zero count is expected during ordinary operation, but the bounded queue deliberately favors robot-loop progress over blocking when storage cannot keep up.

The logger publishes operational evidence under `Diagnostics/Logging/*`: `Profile`,
`AcceptedFrames`, `WrittenFrames`, `DroppedFrames`, `QueueDepth`, `CurrentFileBytes`,
`CompletedBytes`, `Rotations`, and `PrunedFiles`. Dashboard health should alarm on dropped frames or
a persistently growing queue, not on the mere existence of a large completed archive.

## Troubleshooting

### Dashboard cannot connect to live telemetry

1. Confirm robot and laptop are on the same local network.
2. Confirm the server bound to port `5810` and another process is not using it.
3. Use topic names without a leading slash.
4. Verify publisher and subscriber types match exactly.
5. Check that telemetry calls `update()`/flush after publishing the frame.

### Pose appears twice or flickers

The dashboard may consume both `Drive/*` and `ARES/EstimatedPose/*`. Confirm both sources have the same units, sign, and frame. A last-arriving value should not be used to hide disagreement.

### Logs are listed but cannot be downloaded

Check the process working directory, file permissions, and whether the file moved to the synced directory. Send the base file name as the `file` parameter; path traversal is rejected.

### CSV has data only in `_ExtraFieldsJson`

The first frame defines stable columns. Emit expected keys in the first frame if they need first-class columns; late keys remain recoverable from `_ExtraFieldsJson` and are expanded by ARES Robotics Studio import.
