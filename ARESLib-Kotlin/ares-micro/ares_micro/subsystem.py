"""Descriptor-driven XRP mechanism runtime generated from `.aressubsystem` files."""

import math


def _number(value):
    if type(value) not in (int, float) or not math.isfinite(value):
        raise ValueError("XRP controller values must be finite numbers")
    return value


def _wrap_delta(value, period):
    return (value + period / 2.0) % period - period / 2.0


class MockXrpDevice:
    """Deterministic simulator/test device with the same read/write boundary as physical adapters."""

    def __init__(self, readings=None):
        self.readings = readings or {}
        self.last_output = None
        self.fail_writes = False
        self.fail_reads = False

    def read(self, source):
        if self.fail_reads:
            raise OSError("simulated XRP read failure")
        return self.readings.get(source, 0.0)

    def write(self, value):
        if self.fail_writes:
            raise OSError("simulated XRP write failure")
        self.last_output = value


class GeneratedXrpSubsystem:
    """Mechanism state, IO, and control for generated XRP subsystem descriptors."""

    def __init__(self, descriptor, hardware_factory):
        self.descriptor = descriptor
        self.document_id = descriptor["documentId"]
        self.state = {}
        self.devices = {}
        self.faulted = False
        self.configured = True
        self._integral = {}
        self._previous_error = {}
        self._derivative = {}
        self._bang_bang = {}
        self._fields = {field["fieldId"]: field for field in descriptor.get("stateFields", [])}
        self._hardware = tuple(descriptor.get("hardware", []))
        self._loops = tuple(descriptor.get("controlLoops", []))
        self._outputs = [0.0] * len(self._loops)
        self._read_groups = []
        available_measurements = set()
        self._reset_control()
        for field in descriptor.get("stateFields", []):
            default_key = {
                "DOUBLE": "defaultNumber", "BOOLEAN": "defaultBoolean",
                "INT": "defaultInt", "STRING": "defaultText",
            }[field["type"]]
            self.state[field["fieldId"]] = field.get(default_key)
        for device in self._hardware:
            try:
                adapter = hardware_factory(device)
                if adapter is None:
                    raise ValueError("XRP hardware factory returned no adapter")
                self.devices[device["hardwareId"]] = adapter
                groups = {}
                for measurement in device.get("measurements", []):
                    groups.setdefault(measurement["source"], []).append(measurement)
                self._read_groups.append((adapter, tuple(groups.items())))
                for measurement in device.get("measurements", []):
                    available_measurements.add(measurement["fieldId"])
            except Exception:
                if device.get("required", True):
                    self.configured = False
                    self.faulted = True
        for loop in self._loops:
            if (loop["actuatorId"] not in self.devices
                    or (loop["strategy"] not in ("DIRECT", "SERVO_POSITION")
                        and loop.get("measurementFieldId") not in available_measurements)):
                self.configured = False
                self.faulted = True
        self.stop()

    def set_target(self, field_id, value):
        field = self._fields.get(field_id)
        if field is None or field.get("role") not in ("TARGET", "CONFIGURATION"):
            raise ValueError("Unknown XRP subsystem target: " + field_id)
        self._validate_value(field, value)
        self.state[field_id] = value

    def _validate_value(self, field, value):
        kind = field["type"]
        if ((kind == "DOUBLE" and type(value) not in (int, float))
                or (kind == "INT" and type(value) is not int)
                or (kind == "BOOLEAN" and type(value) is not bool)
                or (kind == "STRING" and not isinstance(value, str))):
            raise ValueError("XRP target does not match its declared type")
        if kind in ("DOUBLE", "INT"):
            _number(value)
        minimum, maximum = field.get("minimum"), field.get("maximum")
        if minimum is not None:
            _number(minimum)
        if maximum is not None:
            _number(maximum)
        if minimum is not None and value < minimum or maximum is not None and value > maximum:
            raise ValueError("XRP subsystem target is outside its declared limits")

    def periodic(self, dt=0.02):
        if self.faulted or not self.configured:
            self.stop()
            return
        try:
            if _number(dt) <= 0:
                raise ValueError("XRP subsystem period must be positive")
            for adapter, groups in self._read_groups:
                for source, measurements in groups:
                    raw = adapter.read(source)
                    for measurement in measurements:
                        value = _number(raw * measurement.get("scale", 1.0) + measurement.get("offset", 0.0))
                        minimum, maximum = measurement.get("validMinimum"), measurement.get("validMaximum")
                        if minimum is not None and value < minimum or maximum is not None and value > maximum:
                            raise ValueError("XRP feedback outside declared validity bounds")
                        self.state[measurement["fieldId"]] = value
            # Validate every controller result before issuing any nonzero output.
            for index, loop in enumerate(self._loops):
                self._outputs[index] = self._calculate(loop, dt)
            for index, loop in enumerate(self._loops):
                self.devices[loop["actuatorId"]].write(self._outputs[index])
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
        self._reset_control()
        failed = False
        for device in self._hardware:
            neutral = device.get("safeOutput")
            adapter = self.devices.get(device["hardwareId"])
            if neutral is None or adapter is None:
                continue
            try:
                adapter.write(_number(neutral))
            except Exception:
                failed = True
        if failed:
            self.faulted = True
            raise OSError("XRP subsystem neutral write failed")

    def _reset_control(self):
        for loop in self._loops:
            key = loop["loopId"]
            self._integral[key] = 0.0
            self._previous_error[key] = None
            self._derivative[key] = 0.0
            self._bang_bang[key] = 0.0

    def _calculate(self, loop, dt):
        target_value = self.state[loop["targetFieldId"]]
        self._validate_value(self._fields[loop["targetFieldId"]], target_value)
        target = _number(float(target_value))
        minimum_output = _number(loop.get("minimumOutput", -12.0))
        maximum_output = _number(loop.get("maximumOutput", 12.0))
        if minimum_output >= maximum_output:
            raise ValueError("XRP controller minimum output must be below maximum")
        strategy = loop["strategy"]
        if strategy not in ("DIRECT", "SERVO_POSITION", "POSITION_PID", "VELOCITY_PID", "PROFILED_POSITION_PID", "BANG_BANG"):
            raise ValueError("Unknown XRP control strategy: " + str(strategy))
        if strategy in ("DIRECT", "SERVO_POSITION"):
            output = target
        else:
            measurement = _number(self.state[loop["measurementFieldId"]])
            error = _number(target - measurement)
            continuous = loop.get("continuousInput", {})
            period = None
            if continuous.get("enabled"):
                if strategy not in ("POSITION_PID", "PROFILED_POSITION_PID"):
                    raise ValueError("Continuous input requires position control")
                minimum = _number(continuous.get("minimumInput", -math.pi))
                maximum = _number(continuous.get("maximumInput", math.pi))
                period = maximum - minimum
                if period <= 0 or abs(period - 2.0 * math.pi) > 1e-4:
                    raise ValueError("Continuous XRP angle input must span one turn")
                error = _wrap_delta(error, period)
            key = loop["loopId"]
            if strategy == "BANG_BANG":
                tolerance = _number(loop.get("tolerance", 0.0))
                hysteresis = _number(loop.get("hysteresis", 0.0))
                if tolerance < 0 or hysteresis < 0:
                    raise ValueError("Bang-bang tolerance and hysteresis must be nonnegative")
                output = self._bang_bang[key]
                if output > 0 and error <= tolerance or output < 0 and error >= -tolerance:
                    output = 0.0
                elif output == 0:
                    if error > tolerance + hysteresis:
                        output = max(0.0, maximum_output)
                    elif error < -tolerance - hysteresis:
                        output = min(0.0, minimum_output)
                self._bang_bang[key] = output
                # Neutral is zero even when the active output interval excludes it.
                return output
            else:
                kp, ki, kd = _number(loop.get("kP", 0.0)), _number(loop.get("kI", 0.0)), _number(loop.get("kD", 0.0))
                tau = _number(loop.get("derivativeFilterTimeConstantSeconds", 0.02))
                if tau < 0:
                    raise ValueError("Derivative filter time must be nonnegative")
                previous = self._previous_error[key]
                derivative = 0.0
                if kd != 0 and previous is not None:
                    delta = error - previous
                    if period is not None:
                        delta = _wrap_delta(delta, period)
                    raw_derivative = _number(delta / dt)
                    old_derivative = self._derivative[key]
                    derivative = _number(old_derivative + dt / (tau + dt) * (raw_derivative - old_derivative))
                self._previous_error[key] = error
                self._derivative[key] = derivative
                candidate = _number(self._integral[key] + error * dt) if ki != 0 else 0.0
                output = _number(kp * error + ki * candidate + kd * derivative)
                bounded = max(minimum_output, min(maximum_output, output))
                # Integrate only when unsaturated or when the integral change moves
                # output back toward its allowed range, including negative kI.
                if output == bounded or (output - bounded) * (ki * error) <= 0:
                    self._integral[key] = candidate
                return bounded
        return max(minimum_output, min(maximum_output, _number(output)))


def mock_hardware_factory(device):
    return MockXrpDevice()
