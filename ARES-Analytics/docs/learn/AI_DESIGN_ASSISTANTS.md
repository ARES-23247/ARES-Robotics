# AI design assistants

ARES uses Gemini as a **review-only form assistant**. It cannot save project files, generate Kotlin,
edit vendor source, or command a robot. Every result is parsed into the same typed DSL, validated,
shown as a structured change list, and applied only after confirmation.

## Configure Gemini

1. Open **Profile → Gemini assistance**.
2. Choose Google AI Studio and enter an API key, or configure Vertex AI.
3. Keep the stable default model unless a mentor has a reason to change it.
4. Save the profile.

The API key is local configuration and must never be committed to the robot project.

## Where assistance appears

- **Subsystem Builder:** open or create a subsystem, remain on **Configure**, and use **Help me
  design this** above the build steps.
- **Drivebase Builder:** use **Help me design this drivebase** at the top of the screen.
- **Controller Bindings:** select a control scheme, then use **Help me create bindings** above the
  controller canvas.

Good requests describe physical behavior and constraints, for example:

- “A two-motor elevator with the right motor following the left, current-and-velocity stall homing,
  35 cm travel, brake neutral, and an arm feedforward.”
- “A four-motor mecanum drive using a Pinpoint, field-centric control, and safe brake on disable.”
- “Operator right bumper runs intake while held; X stops it; left trigger proportionally controls
  reverse speed.”

Review device identities, inversion, units, homing evidence, safe outputs, timing, action arguments,
and every warning before applying. AI suggestions are not hardware evidence and do not replace
restrained-chassis testing.

