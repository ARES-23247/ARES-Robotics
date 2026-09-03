# ARES XRP Starter

The official starter project for XRP robots running with ARES Robotics.

## Project Structure

```
ARES-XRP-Starter/
├── .ares/
│   └── project.json       # Canonical XRP project identity (100" x 56" Orbit Odyssey)
├── deploy/
│   ├── paths/
│   │   └── field.json     # Bundled field definition
│   └── deploy_to_pico.py  # One-step flash tool for Pico W
└── main.py                # User entry point (50 Hz control loop & teleop tether)
```

## Getting Started

1. Open this project in **ARES Robotics Studio**.
2. Author your drivetrain (Differential or Mecanum), configure the SparkFun OTOS sensor, or design an autonomous routine in the Path Editor.
3. Plug in your Raspberry Pi Pico W via USB.
4. Run the deploy helper:
   ```bash
   python deploy/deploy_to_pico.py
   ```
   This automatically installs the latest `ares_micro` library to `/lib/ares_micro` on your Pico W and uploads `main.py`.
5. Connect your laptop to the robot's Wi-Fi Access Point (`ARES-XRP-23247`) and control the robot with your gamepad using the ARES Studio Driver Station!
