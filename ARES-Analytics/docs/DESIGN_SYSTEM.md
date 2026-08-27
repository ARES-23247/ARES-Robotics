# ARES product design system

ARES products should look related without forcing a public website and a technical mission-control
application into the same layout. The website is expressive and recruitment-oriented. The Studio is
dense, operational, and optimized for readable telemetry. Both use the same team identity,
typography families, focus treatment, and accessibility rules.

## Shared contract

The source contract lives in
[`ARES-23247/ARESWEB/design/ares-design-tokens.json`](https://github.com/ARES-23247/ARESWEB/blob/master/design/ares-design-tokens.json).
ARES Robotics Studio packages a versioned snapshot at
`app/src/main/resources/design/ares-design-tokens.json`; `AresBrandContractTest` prevents its Kotlin
theme from drifting from that snapshot.

When changing a shared token:

1. Change the website-owned JSON contract.
2. Update the website CSS mapping and its contrast test.
3. From Analytics, run `scripts\sync-ares-design-tokens.ps1` to copy the complete contract—never
   copy only one color. Pass `-WebsiteRoot` if ARESWEB is not the default sibling.
4. Update `AresBrandTokens` and run the Analytics theme tests.
5. Review representative website and Studio screens with keyboard focus, high contrast, larger
   text, and colorblind-friendly mode.

Neither product downloads design tokens at runtime. Both remain usable offline.

## Brand versus behavior

| Purpose | Token | Usage |
|---|---|---|
| Team identity | ARES red `#C00000` | Logo and intentional brand moments |
| Team heritage | Bronze `#CD7F32` and gold `#FFB81C` | Decorative details and selected emphasis |
| Technical interaction | Cyan `#00E5FF` | Studio primary actions, data, selection, and focus |
| Dark identity foundation | Obsidian `#1A1A1A` | Website dark sections and app branding |
| Light identity foundation | Marble `#F9F9F9` | Website light sections and logo contrast |
| Errors, warnings, success | Semantic palette | Operational state—not team identity |

ARES red is not the Studio selected-state color and is not interchangeable with an error.
Every error, warning, success, connection, or robot state includes a word, icon, border, or pattern;
color is supplemental.

## Typography and shape

- **League Spartan** is the preferred display face for public headings and occasional branded app
  moments. It should not replace compact editor or telemetry typography.
- **Inter** is the preferred body face. Analytics currently uses the platform sans-serif fallback
  until a reviewed font asset is packaged with every native distribution.
- Monospace remains reserved for code, telemetry values, topics, and terminal output.
- Website feature cards may use the `24px 4px` cut-corner treatment. Analytics uses restrained
  4–12 px control and panel radii so dense forms remain predictable.

## Links and logo

The app packages two related assets. The detailed official team mark remains on the navigation rail
and opens [aresfirst.org](https://aresfirst.org/). The simplified ARES Robotics Studio Spartan/circuit
mark is used for the window, taskbar, and native installers because it remains legible at 16–32 px.
**Help & Learn** also provides explicit links
to the [team website](https://aresfirst.org/) and
[ARES GitHub organization](https://github.com/ARES-23247). Link failures never block offline work.

## Readability rules

- Normal text targets at least 4.5:1 contrast; large text and non-text UI target at least 3:1.
- Bright cyan, gold, green, orange, and red fills use `AresOnAccent` near-black text—not white.
- Brand red text is never used directly on the dark app background; use a readable semantic color
  or a filled brand treatment.
- Focus indicators remain visible and do not rely on a color change alone.
- Contrast modes may alter semantic success and error colors; the official logo is not recolored to
  imply system state.

Automated tests are regression evidence, not a complete accessibility claim. Review zoom, keyboard
focus, pit-light glare, screen-reader naming, and color-vision modes on real displays.
