# Hardware Review

Simulation working is evidence about logic, not proof that a physical robot is safe.

Before deployment, a named reviewer must confirm in Robot Studio:

- controller vendor/model and every CAN ID;
- leader/follower relationships and inversion;
- wheel diameter, track width, wheelbase, and gear ratio;
- encoder polarity and canonical CCW-positive gyro behavior;
- controller-enforced current limits and valid cached current readings;
- neutral/brake behavior while Disabled and after communication loss;
- fresh feedback, configuration health, fault latching, and explicit neutral recovery;
- restrained-chassis direction and safe-stop tests.

Until a generated or user-owned physical adapter satisfies that contract, the starter reports Hardware
Review required and does not issue physical drive commands.
