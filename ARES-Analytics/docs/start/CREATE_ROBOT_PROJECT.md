# Create a robot project without writing code

ARES can create a complete, buildable FTC or FRC simulation project during first-run setup. This is
the recommended starting point for a student who does not already have an ARES repository.

> **Simulation-first safety boundary:** the reviewed starters are hardware-neutral and contain no
> Team 23247 season mechanisms or calibration. Builds and simulation are available immediately,
> but ARES blocks physical deployment until Hardware Setup and commissioning evidence are complete.

## What the installer includes

The setup screen names the exact **ARES FTC** or **ARES FRC** starter and its ARES version. The app
does not follow a mutable `master` branch. Official installers bundle one reviewed archive, verify
its SHA-256, and only then unpack it. A developer/source build may retrieve the same pinned archive
as a recovery fallback if its packaged resource is absent.

The verified archive is cached under the local ARES Robotics Studio application data. A normal installed
app can create either starter without internet access.

## Create the project

1. Open workspace setup and choose **Create a new robot**.
2. Choose **FTC** or **FRC**.
3. Read the displayed starter name and version.
4. Choose an existing parent folder such as `Documents\Robots`.
5. Enter a new folder name. ARES never merges into or replaces an existing file or folder.
6. Continue and enter the team, season, stable robot ID, and friendly name.
7. Review the setup and select **Create workspace**.

ARES unpacks into a private sibling staging folder, writes the robot identity, validates the source
layout, and publishes the finished directory in one move. If download, verification, extraction, or
personalization fails, the incomplete staging folder is removed and the requested destination is
left absent.

## What is personalized

- `.ares/project.json`: the single canonical team, season, robot, display-name, league, coordinate,
  and stable-project identity source, while retaining the reviewed league coordinate convention
  and starter geometry until the student measures and reviews the real robot dimensions.
- `.ares/drivetrains/*.aresdrivetrain` and `.ares/tuning/*.arestuning`: robot-, drivebase-, and
  profile-level UIDs are rebound to this team, league, season, and robot. Parameter/component IDs
  stay stable because the reviewed runtime consumes them.
- `.ares/template-provenance.json`: starter ID, exact revision, SHA-256, and ARES version.
- FTC `local.properties`: when an installed Android SDK is found, ARES records its machine-local
  path so the new project can build without copying settings from another repository.

No Kotlin source file is rewritten during personalization. Retired `.ares-robot.json` identity is
never created for a new project. Canonical documents are decoded,
rewritten through their typed codecs, and validated as one identity graph. Generated mechanisms
still use Robot Studio's normal preview, ownership headers, confirmation tokens, tests, and
generated-source boundaries.

## Next steps

1. Open **Robot Studio → Project identity** and replace the starter footprint with measured values.
2. Choose the supported drivebase for the league.
3. Add mechanisms with Subsystem Builder.
4. Use the reviewed season driving controls as a baseline, or add a controller profile and control
   scheme together when you want GUI bindings for named mechanism actions.
5. Run **Verify & build**. It regenerates the project bridge, verifies ownership, runs tests, and
   packages the project without deploying.
6. Choose **Local Sim**, then start the now-verified simulator.

Creation does not deploy or enable a physical robot. Generic starters remain blocked from the
deploy service until every physical actuator and sensor is represented by reviewed canonical
documents and the commissioning workflow records the required evidence.

Robot Studio's [Hardware Setup](HARDWARE_SETUP.md) screen can still aggregate the canonical
drivebase and subsystem addresses, detect cross-document conflicts, and record a hash-bound review.
That review is useful for finding descriptor conflicts and teaching hardware mapping, but it does
not by itself remove the simulation-first block. It is not powered hardware validation,
calibration, inspection approval, or permission to test without adult supervision.
