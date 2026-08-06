# Module 3: Pathing & ARES-Analytics Dashboard

In this module, you will learn how to create autonomous trajectories using ARES-Analytics and hot-reload them over Wi-Fi.

---

## 1. Opening ARES-Analytics

Launch the ARES-Analytics desktop application:
```powershell
.\gradlew.bat :app:run
```

---

## 2. Drawing an Autonomous Trajectory

1. Switch to the **Path Editor** tab.
2. Select **Add Waypoint** and click on the field canvas to place start, mid, and end waypoints.
3. Adjust target headings and rotation markers.
4. Click **Deploy Path** to push the trajectory over Wi-Fi to `/sdcard/FIRST/paths/` (FTC) or `/home/lvuser/deploy/pathplanner/paths/` (FRC).
