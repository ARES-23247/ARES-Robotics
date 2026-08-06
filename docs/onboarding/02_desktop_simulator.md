# Module 2: Driving in the Desktop Physics Simulator

In this module, you will launch our 2.5D Dyn4j physics simulator and drive a virtual robot on your laptop without physical hardware!

---

## 1. Launching the Simulator

Run the following command from the repository root:

```powershell
.\gradlew.bat :simulator:run
```

---

## 2. Controls & Features

- **Gamepad / Keyboard Control:** Plug in a USB controller or use `W`, `A`, `S`, `D` and Arrow Keys to drive.
- **Physics Simulation:** Dyn4j simulates wheel traction, friction coefficients, battery voltage drop, and wall collisions.
- **Live NT4 Broadcast:** The simulator broadcasts NetworkTables 4 telemetry to port `1735`, allowing ARES-Analytics to render the virtual robot live.
