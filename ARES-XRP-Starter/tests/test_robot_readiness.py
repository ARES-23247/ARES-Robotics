"""Host-only readiness evidence for the actual generated starter and simulated motors."""
import gc
import importlib.util
import json
import math
import pathlib
import platform
import time
import tracemalloc
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


def stats(values):
    ordered = sorted(values)
    def percentile(fraction):
        return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)] / 1e6
    return {"p50Ms": percentile(.5), "p95Ms": percentile(.95),
            "p99Ms": percentile(.99), "maxMs": ordered[-1] / 1e6}


class RobotReadinessTest(unittest.TestCase):
    def test_generated_starter_lifecycle_and_host_cost(self):
        spec = importlib.util.spec_from_file_location("xrp_readiness_simulator", ROOT / "simulator/xrp_simulator.py")
        simulator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(simulator)
        robot, motors = simulator.create_simulated_robot()
        self.addCleanup(robot.shutdown)
        control = {"command": None, "frame": None}
        robot.telemetry.poll = lambda: None
        robot.telemetry.get_command = lambda: control.pop("command", None)
        robot.telemetry.get_drive_frame = lambda: control["frame"]
        # No sockets are opened. The boundary excludes control-link transport and serialization.
        robot.step(.02)
        self.assertTrue(all(m.effort == 0 for m in motors))
        control.update(command="START_TELEOP", frame=(.2, 0, .8))
        robot.step(.02)
        self.assertTrue(all(m.effort > 0 for m in motors))
        control["frame"] = None
        robot.step(.02)
        self.assertEqual(robot.STATE_DISABLED, robot.mode)
        self.assertTrue(all(m.effort == 0 for m in motors))
        control["frame"] = (.2, 0, .8)
        robot.step(.02)
        self.assertTrue(all(m.effort == 0 for m in motors), "Restored values cannot implicitly re-arm")
        control["command"] = "START_TELEOP"
        robot.step(.02)
        self.assertFalse(robot.faulted)

        # Small circle inside the existing tabletop field; no simulator-truth estimator override.
        robot.drivetrain.reset_pose(.7, .4, 0)
        sensor_start = [0]
        output_end = [0]
        get_position = motors[0].get_position
        set_effort = motors[-1].set_effort
        def sample():
            sensor_start[0] = time.perf_counter_ns()
            return get_position()
        def output(value):
            set_effort(value)
            output_end[0] = time.perf_counter_ns()
        motors[0].get_position = sample
        motors[-1].set_effort = output

        def cycle():
            for motor in motors:
                motor.advance(.02)
            robot.step(.02)

        for _ in range(1000):
            cycle()
        durations, latency = [], []
        for _ in range(5000):
            started = time.perf_counter_ns()
            cycle()
            durations.append(time.perf_counter_ns() - started)
            latency.append(output_end[0] - sensor_start[0])
        self.assertTrue(all(value >= 0 for value in latency))
        self.assertFalse(robot.faulted)
        self.assertEqual(robot.STATE_TELEOP, robot.mode)
        self.assertTrue(all(m.effort > 0 for m in motors))

        # Separate tracing run: peak/retained Python memory, NOT total allocated bytes or Pico heap.
        gc.collect()
        tracemalloc.start()
        try:
            for _ in range(1000):
                cycle()
            retained, peak = tracemalloc.get_traced_memory()
        finally:
            tracemalloc.stop()
        periods, lateness = [], []
        scheduled = time.perf_counter_ns()
        previous = None
        for _ in range(100):
            while (remaining := scheduled - time.perf_counter_ns()) > 0:
                time.sleep(remaining / 1e9)
            started = time.perf_counter_ns()
            lateness.append(max(0, started - scheduled))
            if previous is not None:
                periods.append(started - previous)
            previous = started
            cycle()
            scheduled += 20_000_000
        control["command"] = "STOP"
        robot.step(.02)
        self.assertTrue(all(m.effort == 0 for m in motors))
        robot.shutdown()
        control["command"] = "START_TELEOP"
        robot.step(.02)
        self.assertTrue(all(m.effort == 0 for m in motors), "Closed runtime must remain inert")
        report = {
            "platform": "XRP", "environment": "desktop CPython simulated IO",
            "os": platform.system(), "pythonVersion": platform.python_version(),
            "clock": "time.perf_counter_ns", "nominalBudgetMs": 20,
            "boundary": "SimMotor advance, XrpRobot.step, generated starter subsystems, odometry, field constraint, motor outputs",
            "excludes": "Pico/MicroPython, physical IO, network transport and telemetry serialization",
            "warmupCycles": 1000, "samples": 5000, "loop": stats(durations),
            "sensorToOutput": stats(latency), "executionOver20ms": sum(v > 20_000_000 for v in durations),
            "tracedSeparateCycles": 1000, "tracedRetainedBytes": retained, "tracedPeakBytes": peak,
            "totalAllocatedBytes": None, "pacedSamples": 100, "pacedPeriod": stats(periods),
            "schedulerLateness": stats(lateness), "pacedIntervalsOver20ms": sum(v > 20_000_000 for v in periods),
            "scheduledStartsMissedByWholePeriod": sum(v >= 20_000_000 for v in lateness),
            "scenarioChecks": "passed",
        }
        path = ROOT / "build/reports/robot-readiness/xrp.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
