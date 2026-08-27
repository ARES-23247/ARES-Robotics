# Lightbot: GUI-owned lighting example

Lightbot is the official small FTC example. Its robot-specific mechanisms are authored in Robot
Builder: two independently controlled indicator lights and one goBILDA Prism underbody light. It
does not include the intake or flywheel implementations used by other robots; those remain reusable
ARES templates rather than Lightbot hardware.

## Canonical ownership

| Builder document | Hardware-map names | Simulator placement | Safe output |
| --- | --- | --- | --- |
| `.ares/subsystems/indicator-lights.aressubsystem` | `indicator`, `indicator2` | left and right side squares | both off |
| `.ares/subsystems/prism.aressubsystem` | `prism` | underbody glow | `SOLID_OFF` |

Change names, color targets, safe outputs, placements, controls, and autonomous actions in the GUI,
then review the structured diff and save the canonical descriptor. Generated definition,
registration, and test files are mechanical Gradle products. Editable adapter starters remain
protected and cannot be silently overwritten during regeneration.

Each side light displays one color at a time. ARESLib derives independent **Set color**, **Cycle
forward**, and **Cycle backward** actions for the left and right targets. Forward walks red through
white and wraps to red; backward walks the same visible choices in reverse. **Off** remains an
explicit named set-color choice rather than an extra stop in the cycle.

## Runtime flow

`TeleOp or autonomous choice → generated action → Redux reducer → immutable subsystem state → generated controller → shared IO contract → FTC or mock adapter`

The simulator publishes each applied output. Studio renders the two indicator lights at their
descriptor locations and maps the Prism command to an underbody color. This visual evidence checks
the GUI-authored command path; it does not prove physical color accuracy, wiring, or brightness.

## Verification

**Verify & build** regenerates tests for every selected lighting target and safety rule. The
Verification page reports descriptor validation, safe startup/stop, failed writes, controller
limits, every generated action, simulator integration, independent FTC lifecycle coverage, and the
package build. Test files stay hidden unless **Advanced details** is opened.

The highest automatic claim is **Ready for physical validation**. A team member must still confirm
the three hardware-map names, both side locations, safe-off behavior, and visible Prism output on a
disabled and restrained robot before recording physical validation.
