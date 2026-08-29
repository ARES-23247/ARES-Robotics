# Dashboard widget extensions

ARES Robotics Studio dashboard widgets are registered once in
`DashboardWidgetRegistry`. The registry is the source of truth for the picker,
layout validation, default sizing, configuration properties, runtime rendering,
and extension-contract tests. Do not add a second widget-type `when`, picker
list, or size table.

## Add a widget

1. Add a `DashboardWidgetDefinition` to `DashboardWidgetRegistry` with a stable
   lower-snake-case type ID. Persisted layouts use this ID, so renaming it is a
   data migration rather than a label change.
2. Declare its student-facing name, description, category, icon, recommended
   status, default and minimum grid spans, capabilities, and property schema.
3. Implement its renderer as a focused component. Consume only the grouped
   `DashboardWidgetServices` capabilities passed in the render context; never
   reach back into `ServiceRegistry` from a widget. Renderer access to live,
   analysis, or replay services is rejected unless the definition declares a
   matching capability, so keep the capability set accurate.
4. Keep configuration in `WidgetConfig.properties`. Every persisted key must be
   declared by the definition. Typed property defaults are validated at startup.
5. Add the widget to a built-in profile only when it is useful by default. Built-in
   profiles live in `BuiltInDashboardLayoutProfiles` and are checked for unknown
   types, duplicate IDs, invalid properties, undersized cards, grid overflow, and
   overlap.

The picker and grid host discover the definition automatically. There is no
second registration step.

## Data ownership

- Live widgets read the immutable UI snapshot published by `Nt4ClientService`.
- Replay widgets read the immutable `ReplayFrame` supplied by the dashboard host.
- A widget that supports both must choose replay whenever a replay frame is
  present. It must never combine a replay value with a live timestamp or vice
  versa.
- Unit strings are presentation labels. A widget must not silently convert a
  value unless its definition declares and tests a canonical conversion boundary.
- Expensive services remain lazy through `ServiceRegistryDashboardWidgetServices`.
  Declaring a capability must not eagerly start cloud, database, camera, or robot
  control work.

## Required verification

Every widget change must pass:

1. `DashboardWidgetCatalogTest` for registry identity, metadata, property defaults,
   and built-in profile validity.
2. A component-level behavior test for its live/replay/configuration semantics.
3. The layout persistence contract: add, configure, resize, save, recreate the
   layout service, and verify the exact `WidgetConfig` is restored.
4. `:shared:test :gateway:test :app:test`.
5. Two real Compose Desktop journeys using an exact-window capture: configure and
   save on the first launch, verify restoration and live/replay behavior on the
   second launch, then close gracefully and prove no `MainKt` process remains.

Advanced details may expose topics and persisted property keys, but the default UI
should explain the signal, source (`LIVE` or `REPLAY`), units, and missing-data state
without requiring students to understand Kotlin or the registry.
