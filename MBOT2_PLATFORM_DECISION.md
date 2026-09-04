# mBot2 / CyberPi platform decision

Status: **Accepted architecture; implementation awaits a vendor-protocol and hardware spike**  
Decision date: 2026-09-04

## Decision

ARES will model mBot2 as a distinct `MBOT2` platform. It will not be an XRP board subtype and will
not reuse the XRP hardware adapter or simulator. mBot2 projects will keep the stock CyberPiOS and
Makeblock MicroPython modules (`cyberpi`, `mbot2`, and `mbuild`) so the onboard display, buttons,
speaker, motors, encoders, sensors, expansion modules, and vendor AI Camera remain available.

The shared parts are the canonical `.ares` project/subsystem/routine/control documents, safety and
verification vocabulary, coordinate contracts, generated-test model, Studio editors, telemetry
normalization, run storage, and analytics. The platform host, generated Python, device transport,
hardware adapters, simulator, deploy flow, and commissioning checks remain mBot2-specific.

This boundary follows the vendor workflow: CyberPi/mBot2 programs are uploaded to the device and
can run independently, while Python programming uses Makeblock's device APIs. The AI Camera 2.0 is
a smart vision module connected through CyberPi/mBuild, not a generic webcam. Its classifications,
detections, confidence, bounding boxes, learned models, and freshness are treated as observations
from a coprocessor rather than simulated pixels.

## Product shape

- Add `AresLeague.MBOT2` and a dedicated `ARES-MBot2-Starter` only after the protocol spike passes.
- Generate deterministic MicroPython from `.ares` documents; do not transpile Kotlin or
  reverse-engineer arbitrary Python.
- Support GUI-owned, code-first, and hybrid projects with explicit USER-OWNED extension metadata.
- Provide an mBot2-specific simulator model with differential drive, encoder noise, ultrasonic and
  line/color sensing, CyberPi buttons/display/audio state, battery/fault injection, and camera
  observation fixtures. Do not force mBot2 into the XRP physics/hardware model.
- Use a versioned ARES mBot protocol with project/content identity, monotonic sequences, explicit
  armed state, bounded leases, and neutral-on-disconnect. Whether that bridge uses USB serial,
  Bluetooth, or Wi-Fi is deferred until the official supported transport is verified on hardware.
- Preserve upload mode: once deployed, a generated robot can run without Studio.

## AI Camera contract

The camera adapter will publish a timestamped observation frame containing:

- mode/model ID and vendor firmware identity;
- detection class or recognition result;
- confidence when the vendor API provides it;
- normalized bounding box and image dimensions;
- frame sequence, device timestamp, receive timestamp, and maximum accepted age;
- camera-to-robot mounting transform and calibration evidence;
- health, stale, disconnected, and unsupported-mode states.

Safety decisions fail closed when the observation is stale, malformed, below a configured
confidence threshold, or unavailable. Simulation uses recorded/golden observation frames and
fault injection; it does not claim to validate recognition accuracy or camera calibration.

## Hardware and safety scope

The first supported physical surface should include CyberPi buttons, display, RGB LEDs, speaker,
ambient/light and motion sensing exposed by the installed firmware; mBot2 encoder motors and their
closed-loop commands; ultrasonic and quad-RGB/line sensing; and discoverable mBuild devices.
Platform adapters must cache reads once per loop, bound writes, neutral motors on boot/disable/fault,
enforce stale feedback and output-write failure handling, and report the exact firmware/runtime
identity. Device discovery never authorizes motion.

Evidence remains explicit:

1. **Compiled successfully** — generated Python and tests passed on desktop.
2. **Simulation verified** — deterministic simulator behavior passed.
3. **Configuration reviewed** — device identity and declared ports/modules matched.
4. **Ready for physical validation** — all non-physical gates passed.
5. **Physically validated** — a person recorded direction, polarity, limits, neutral, disconnect,
   current/battery, sensor, and camera checks on the named device.

## Delivery phases and exit gates

### Phase 0 — vendor and hardware spike

- Verify current CyberPiOS/mBot2 firmware identities and readable version APIs.
- Verify an officially supported programmatic upload and console protocol on Windows and macOS.
- Inventory exact Python APIs for every stock device and AI Camera 2.0 mode.
- Review redistribution and trademark/license terms for firmware, Python modules, and camera assets.
- Capture neutral/disable/disconnect behavior on a real mBot2.

Exit: a checked-in protocol transcript, API inventory, license decision, and repeatable no-motion
hello/version program. If no stable supported transport exists, Studio exports code and launches
mBlock for upload rather than depending on an undocumented protocol.

### Phase 1 — schema, generator, and simulator

- Add the distinct platform enum and capability matrix.
- Add project template, generated runtime, generated safety tests, and mBot2 simulator.
- Add stock differential drivetrain, encoders, ultrasonic, line/color sensing, CyberPi UI/audio,
  and offline camera observation fixtures.

Exit: a fresh GUI-authored robot builds, passes generated verification, and completes a visible
Studio simulator journey without any vendor cloud service.

### Phase 2 — physical deploy and telemetry

- Implement the verified transport, transactional/recoverable deployment where the device permits,
  runtime identity checks, telemetry bridge, and commissioning UI.
- Reject stale firmware, mismatched project identity, invalid commands, and unreviewed motion.

Exit: complete lifted-robot direction, encoder, neutral, lease-loss, battery, and sensor tests on
named hardware, recorded separately from canonical configuration.

### Phase 3 — AI Camera 2.0

- Implement the typed camera observation adapter and configuration picker.
- Add recorded fixtures, freshness/confidence failures, camera extrinsics, overlays, telemetry, and
  analytics.
- Validate each supported recognition mode on hardware; clearly label custom-model training as a
  vendor workflow unless Makeblock documents an offline supported interface.

Exit: a generated autonomous routine consumes a fresh typed detection in simulation and on a real
camera, while stale/disconnected/low-confidence input deterministically blocks the dependent action.

## Why this is not part of XRP

XRP is a Pico W running the official Open-STEM MicroPython/XRPLib stack with a small TCP protocol.
mBot2 is a CyberPi-based product with different firmware, APIs, peripherals, deployment tooling,
and vendor AI modules. Treating them as one platform would turn the XRP adapter and simulator into
conditional collections and would make safety claims ambiguous. Shared schemas and Studio UX give
reuse at the stable layer; separate hosts preserve accurate hardware behavior.

## Authoritative references used for this decision

- Makeblock, [uploading programs to CyberPi or mBot2](https://support.makeblock.com/hc/en-us/articles/15891344634391-10-How-to-Upload-Programs-to-CyberPi-or-mBot2)
- Makeblock, [Python programming on mBlock 5](https://support.makeblock.com/hc/en-us/articles/4411195519511-Python-Programming-on-mBlock-5)
- Makeblock, [AI Camera vision module](https://www.makeblock.com/products/ai-camera-vision-module)
- Makeblock, [AI Camera 2.0 recognition features](https://support.makeblock.com/hc/en-us/articles/30900768675223-Use-Recognition-Features-of-AI-Camera-2-0)
- Makeblock, [AI Camera 2.0 with CyberPi](https://support.makeblock.com/hc/en-us/articles/34966114598807-Use-AI-Camera-2-0-with-CyberPi)

The pages above establish product capabilities and supported user workflows. They do not yet
establish a stable third-party deploy protocol or redistribution permission; Phase 0 must resolve
those facts before ARES ships physical mBot2 deployment.
