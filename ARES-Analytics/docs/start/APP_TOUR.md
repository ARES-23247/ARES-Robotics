# Find your way around ARES Robotics Studio

This tour explains the parts of the window you will use most often. You can explore every item here with **Local Sim**; a physical robot is not required.

## The window has four landmarks

1. **Workspace selector — top left.** This tells ARES which FTC or FRC project you are editing. Confirm it before changing controls, routines, or subsystem files.
2. **Screen trail & history — across the top.** Clickable contextual breadcrumbs show the section, active screen, and active mechanism, Pit Operations tab, match debrief, or notebook entry (for example, **Robot Studio › Subsystem Builder › Intake**), alongside **Back / Forward** history buttons (**Alt+Left** / **Alt+Right**) and a sibling menu.
3. **Header Search (`Ctrl+K`) & Execution toolbar — top right.** Click the **Search (`Ctrl+K`)** pill button (or press **Ctrl+K**) anywhere to open the Omnibox, then use **Live Robot** or **Local Sim** Play/Stop controls in the toolbar.
4. **Main workspace — center.** This is where the selected screen appears. Many screens include a **Help** button near the toolbar that opens the matching lesson.

The narrow left rail switches between major areas. The icons include labels such as **Dashboard**, **Robot**, **Autonomous**, **Analysis**, and **Data**. The bottom controls open search, Help & Learn, a terminal, connection status, and Settings.

## Start with these screens

| If you want to... | Open | Look for |
| --- | --- | --- |
| See whether data is arriving | **Dashboard** | Field pose, telemetry charts, and connection labels |
| Practice without hardware | Select **Local Sim**, then press Play | The target label remains Local Sim and telemetry begins changing |
| Check what robot signals, battery health, match queue & alert rules exist | **Robot → Pit Operations (Pit Self-Test)** | Status cards marked Observed, Waiting, or Warning plus Battery Fleet & Match Queue tabs |
| Map a gamepad button | **Robot → TeleOp Controls** | Controller diagram, Bindings list, and New binding panel |
| Build a mechanism safely | **Robot → Subsystem Builder** | Template, safety contract, generated-file groups, and preview |
| Find a completed run | **Data → Run History** | Imported sessions; live telemetry is not automatically a saved run |
| Learn a task | **Help & Learn** | Start here, Build skills, and Go deeper tracks |

## Read status before color

ARES uses color to make scanning faster, but color is never the only meaning.

- Read the nearby text such as **CONNECTED**, **OFFLINE**, **OBSERVED**, or **WAITING**.
- A connection means data is available. It does not certify that a robot is safe.
- A warning explains missing or questionable evidence. It is not permission to bypass a check.
- Use **Settings → Larger text** if labels or guidance are uncomfortable to read. This scales the app while preserving the operating-system text scale.

## Forms show essentials first

Novice-facing editors keep the primary task visible and place uncommon controls behind descriptive expanders.

- In **TeleOp Controls**, choose the input, event, and target, then use **Add binding**. Open **Advanced timing & safety** only when you need debounce, hold, repeat, cooldown, maximum-active-time, or chord behavior. Existing non-default safety settings open automatically.
- In **Subsystem Builder**, start from the capability template closest to the mechanism. Read every safety warning before generating files; generated plumbing is collapsed because it is not a customization point.

## Two fast ways to get unstuck

- Click **Search (`Ctrl+K`)** in the top header (or press **Ctrl+K**) to open the Omnibox with recent destinations and live results across screens, recorded runs, Engineering Notebook entries, battery packs (`BAT-01`), scheduled matches (`Match #12`), Pit Operations tabs, subsystems, superstructures, autonomous routines, and glossary terms. Use **↑ / ↓**, **Enter**, and **Esc** to navigate without touching the mouse, and **Alt+Left / Alt+Right** to step through your screen history.
- Select **Help** near the execution toolbar to open the lesson for the current workflow.

When asking a mentor for help, share three facts: the selected workspace, the selected execution target, and the exact status or error text shown on screen.
