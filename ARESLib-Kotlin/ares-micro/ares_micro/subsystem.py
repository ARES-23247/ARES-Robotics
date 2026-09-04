"""Descriptor-driven XRP mechanism runtime generated from `.aressubsystem` files."""

import math


class MockXrpDevice:
    """Deterministic simulator/test device with the same read/write boundary as physical adapters."""

    def __init__(self, readings=None):
        self.readings = readings or {}
        self.last_output = None
        self.fail_writes = False

    def read(self, source):
        return self.readings.get(source, 0.0)

    def write(self, value):
        if self.fail_writes:
            raise OSError("simulated XRP write failure")
        self.last_output = value


class GeneratedXrpSubsystem:
    """Small fail-closed controller whose complete behavior comes from one descriptor."""

    def __init__(self, descriptor, hardware_factory):
        self.descriptor = descriptor
        self.document_id = descriptor["documentId"]
        self.state = {}
        self.devices = {}
        self.faulted = False
        self.configured = True
        self._integral = {}
        self._previous_error = {}
        for field in descriptor.get("stateFields", []):
            default_key = {
                "DOUBLE": "defaultNumber", "BOOLEAN": "defaultBoolean",
                "INT": "defaultInt", "STRING": "defaultText",
            }[field["type"]]
            self.state[field["fieldId"]] = field.get(default_key)
        for device in descriptor.get("hardware", []):
            try:
                self.devices[device["hardwareId"]] = hardware_factory(device)
            except Exception:
                if device.get("required", True):
                    self.configured = False
                    self.faulted = True
        self.stop()

    def set_target(self, field_id, value):
        field = next((item for item in self.descriptor.get("stateFields", []) if item["fieldId"] == field_id), None)
        if field is None or field.get("role") not in ("TARGET", "CONFIGURATION"):
            raise ValueError("Unknown XRP subsystem target: " + field_id)
        if isinstance(value, float) and not math.isfinite(value):
            raise ValueError("XRP subsystem targets must be finite")
        minimum, maximum = field.get("minimum"), field.get("maximum")
        if minimum is not None and value < minimum or maximum is not None and value > maximum:
            raise ValueError("XRP subsystem target is outside its declared limits")
        self.state[field_id] = value

    def periodic(self, dt=0.02):
        if self.faulted or not self.configured:
            self.stop()
            return
        try:
            for device in self.descriptor.get("hardware", []):
                adapter = self.devices.get(device["hardwareId"])
                if adapter is None:
                    continue
                for measurement in device.get("measurements", []):
                    raw = adapter.read(measurement["source"])
                    value = raw * measurement.get("scale", 1.0) + measurement.get("offset", 0.0)
                    if isinstance(value, float) and not math.isfinite(value):
                        raise ValueError("non-finite XRP feedback")
                    minimum, maximum = measurement.get("validMinimum"), measurement.get("validMaximum")
                    if minimum is not None and value < minimum or maximum is not None and value > maximum:
                        raise ValueError("XRP feedback outside declared validity bounds")
                    self.state[measurement["fieldId"]] = value
            for loop in self.descriptor.get("controlLoops", []):
                output = self._calculate(loop, dt)
                self.devices[loop["actuatorId"]].write(output)
        except Exception:
            self.faulted = True
            try:
                self.stop()
            except Exception:
                pass

    def recover_neutral(self):
        try:
            self.stop()
            self.faulted = not self.configured
        except Exception:
            self.faulted = True
        return not self.faulted

    def stop(self):
        failed = False
        for device in self.descriptor.get("hardware", []):
            neutral = device.get("safeOutput")
            adapter = self.devices.get(device["hardwareId"])
            if neutral is None or adapter is None:
                continue
            try:
                adapter.write(neutral)
            except Exception:
                failed = True
        if failed:
            self.faulted = True
            raise OSError("XRP subsystem neutral write failed")

    def _calculate(self, loop, dt):
        target = float(self.state[loop["targetFieldId"]])
        strategy = loop["strategy"]
        if strategy in ("DIRECT", "SERVO_POSITION"):
            output = target
        else:
            measurement = float(self.state[loop["measurementFieldId"]])
            error = target - measurement
            continuous = loop.get("continuousInput", {})
            if continuous.get("enabled"):
                minimum, maximum = continuous["minimumInput"], continuous["maximumInput"]
                period = maximum - minimum
                error = (error + period / 2.0) % period - period / 2.0
            key = loop["loopId"]
            self._integral[key] = self._integral.get(key, 0.0) + error * dt
            derivative = (error - self._previous_error.get(key, error)) / dt if dt > 0 else 0.0
            self._previous_error[key] = error
            if strategy == "BANG_BANG":
                output = loop.get("maximumOutput", 1.0) if error > loop.get("tolerance", 0.0) else 0.0
            else:
                output = loop.get("kP", 0.0) * error + loop.get("kI", 0.0) * self._integral[key] + loop.get("kD", 0.0) * derivative
        return max(loop.get("minimumOutput", -1.0), min(loop.get("maximumOutput", 1.0), output))


def mock_hardware_factory(device):
    return MockXrpDevice()
