package com.ares.analytics.mcp.bringup

import com.areslib.drivetrain.DrivetrainComponentRole
import com.areslib.drivetrain.LocalizationSourceKind
import com.areslib.project.model.RobotProjectSnapshot
import com.areslib.subsystem.SubsystemHardwareKind
import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * Generates and validates FTC RobotController REV Hub `.xml` hardware configurations from canonical
 * `.ares` drivetrain & subsystem descriptors while enforcing physical port budgets.
 */
internal object FtcRevHubXmlGenerator {
    const val MAX_MOTORS_PER_HUB = 4
    const val MAX_SERVOS_PER_HUB = 6
    const val MAX_DIGITAL_PER_HUB = 8
    const val MAX_ANALOG_PER_HUB = 4
    const val MAX_I2C_BUSES_PER_HUB = 4

    fun generateFromSnapshot(snapshot: RobotProjectSnapshot): String {
        val motors = linkedSetOf<String>()
        val servos = linkedSetOf<String>()
        val digitalInputs = linkedSetOf<String>()
        val analogInputs = linkedSetOf<String>()
        val i2cSensors = linkedMapOf<String, String>() // name -> xmlTag

        snapshot.drivetrains.firstOrNull()?.let { drive ->
            for (comp in drive.components) {
                when (comp.role) {
                    DrivetrainComponentRole.DRIVE_MOTOR, DrivetrainComponentRole.STEER_MOTOR ->
                        motors += comp.hardwareId
                    DrivetrainComponentRole.ODOMETRY_SENSOR -> {
                        val tag = when (drive.localization.primaryOdometry.source) {
                            LocalizationSourceKind.PINPOINT -> "goBILDA_Pinpoint"
                            LocalizationSourceKind.SPARKFUN_OTOS -> "SparkFunOTOS"
                            else -> "I2cDeviceSynchSimple"
                        }
                        i2cSensors.putIfAbsent(comp.hardwareId, tag)
                    }
                    else -> Unit
                }
            }
        }

        for (subsystem in snapshot.subsystems) {
            for (hw in subsystem.hardware) {
                when (hw.kind) {
                    SubsystemHardwareKind.MOTOR -> motors += hw.hardwareId
                    SubsystemHardwareKind.POSITIONAL_SERVO, SubsystemHardwareKind.CONTINUOUS_SERVO ->
                        servos += hw.hardwareId
                    SubsystemHardwareKind.DIGITAL_INPUT, SubsystemHardwareKind.DIGITAL_OUTPUT ->
                        digitalInputs += hw.hardwareId
                    SubsystemHardwareKind.ANALOG_INPUT -> analogInputs += hw.hardwareId
                    SubsystemHardwareKind.DISTANCE_SENSOR -> i2cSensors.putIfAbsent(hw.hardwareId, "Rev2mDistanceSensor")
                    SubsystemHardwareKind.COLOR_SENSOR -> i2cSensors.putIfAbsent(hw.hardwareId, "LynxColorSensor")
                    SubsystemHardwareKind.IMU -> i2cSensors.putIfAbsent(hw.hardwareId, "ControlHubImuBHI260AP")
                    else -> Unit
                }
            }
        }

        val controlHubI2cCapacity = MAX_I2C_BUSES_PER_HUB - 1 // bus 0 reserved for Control Hub internal IMU
        val totalI2cCapacity = controlHubI2cCapacity + MAX_I2C_BUSES_PER_HUB
        require(motors.size <= MAX_MOTORS_PER_HUB * 2) {
            "FTC hardware declares ${motors.size} motors, exceeding 2 REV Hubs (${MAX_MOTORS_PER_HUB * 2} max)."
        }
        require(servos.size <= MAX_SERVOS_PER_HUB * 2) {
            "FTC hardware declares ${servos.size} servos, exceeding 2 REV Hubs (${MAX_SERVOS_PER_HUB * 2} max)."
        }
        require(digitalInputs.size <= MAX_DIGITAL_PER_HUB * 2) {
            "FTC hardware declares ${digitalInputs.size} digital devices, exceeding 2 REV Hubs (${MAX_DIGITAL_PER_HUB * 2} max)."
        }
        require(analogInputs.size <= MAX_ANALOG_PER_HUB * 2) {
            "FTC hardware declares ${analogInputs.size} analog inputs, exceeding 2 REV Hubs (${MAX_ANALOG_PER_HUB * 2} max)."
        }
        val externalI2c = i2cSensors.entries.filter { it.value != "ControlHubImuBHI260AP" }
        require(externalI2c.size <= totalI2cCapacity) {
            "FTC hardware declares ${externalI2c.size} external I2C devices, exceeding 2 REV Hubs ($totalI2cCapacity max external buses)."
        }

        val needsExpansionHub = motors.size > MAX_MOTORS_PER_HUB ||
            servos.size > MAX_SERVOS_PER_HUB ||
            digitalInputs.size > MAX_DIGITAL_PER_HUB ||
            analogInputs.size > MAX_ANALOG_PER_HUB ||
            externalI2c.size > controlHubI2cCapacity

        val motorList = motors.toList()
        val servoList = servos.toList()
        val digitalList = digitalInputs.toList()
        val analogList = analogInputs.toList()

        return buildString {
            appendLine("""<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>""")
            appendLine("""<Robot type="FirstInspires-FTC">""")
            appendLine("""    <LynxUsbDevice name="Control Hub Portal" serialNumber="(embedded)" parentModuleAddress="173">""")
            appendHubXml(
                indent = "        ",
                hubName = "Control Hub",
                hubPort = 173,
                motors = motorList.take(MAX_MOTORS_PER_HUB),
                servos = servoList.take(MAX_SERVOS_PER_HUB),
                digitals = digitalList.take(MAX_DIGITAL_PER_HUB),
                analogs = analogList.take(MAX_ANALOG_PER_HUB),
                i2cEntries = externalI2c.take(controlHubI2cCapacity),
                includeInternalImu = true,
            )
            if (needsExpansionHub) {
                appendHubXml(
                    indent = "        ",
                    hubName = "Expansion Hub 2",
                    hubPort = 2,
                    motors = motorList.drop(MAX_MOTORS_PER_HUB).take(MAX_MOTORS_PER_HUB),
                    servos = servoList.drop(MAX_SERVOS_PER_HUB).take(MAX_SERVOS_PER_HUB),
                    digitals = digitalList.drop(MAX_DIGITAL_PER_HUB).take(MAX_DIGITAL_PER_HUB),
                    analogs = analogList.drop(MAX_ANALOG_PER_HUB).take(MAX_ANALOG_PER_HUB),
                    i2cEntries = externalI2c.drop(controlHubI2cCapacity).take(MAX_I2C_BUSES_PER_HUB),
                    includeInternalImu = false,
                )
            }
            appendLine("""    </LynxUsbDevice>""")
            appendLine("""</Robot>""")
        }
    }

    private fun StringBuilder.appendHubXml(
        indent: String,
        hubName: String,
        hubPort: Int,
        motors: List<String>,
        servos: List<String>,
        digitals: List<String>,
        analogs: List<String>,
        i2cEntries: List<Map.Entry<String, String>>,
        includeInternalImu: Boolean,
    ) {
        appendLine("""$indent<LynxModule name="$hubName" port="$hubPort">""")
        motors.forEachIndexed { idx, name ->
            appendLine("""$indent    <Motor name="${escapeXml(name)}" port="$idx" />""")
        }
        servos.forEachIndexed { idx, name ->
            appendLine("""$indent    <Servo name="${escapeXml(name)}" port="$idx" />""")
        }
        digitals.forEachIndexed { idx, name ->
            appendLine("""$indent    <DigitalDevice name="${escapeXml(name)}" port="$idx" />""")
        }
        analogs.forEachIndexed { idx, name ->
            appendLine("""$indent    <AnalogInput name="${escapeXml(name)}" port="$idx" />""")
        }
        if (includeInternalImu) {
            appendLine("""$indent    <ControlHubImuBHI260AP name="imu" port="0" bus="0" />""")
        }
        val firstExternalBus = if (includeInternalImu) 1 else 0
        i2cEntries.forEachIndexed { idx, (name, tag) ->
            val bus = firstExternalBus + idx
            appendLine("""$indent    <$tag name="${escapeXml(name)}" port="0" bus="$bus" />""")
        }
        appendLine("""$indent</LynxModule>""")
    }

    fun validateXml(xmlText: String): List<HardwareAuditIssue> = buildList {
        val doc = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            factory.newDocumentBuilder().parse(InputSource(StringReader(xmlText)))
        }.getOrElse { error ->
            add(HardwareAuditIssue("ERROR", "FTC_XML_PARSE", "robot_config.xml", "Malformed FTC XML: ${error.message}"))
            return@buildList
        }

        val root = doc.documentElement
        if (root.tagName != "Robot") {
            add(HardwareAuditIssue("ERROR", "FTC_XML_ROOT", "robot_config.xml", "Root XML tag must be <Robot>, found <${root.tagName}>."))
        }

        val allDeviceNames = hashMapOf<String, String>()
        val modules = doc.getElementsByTagName("LynxModule")
        for (i in 0 until modules.length) {
            val moduleEl = modules.item(i) as? Element ?: continue
            val hubName = moduleEl.getAttribute("name").ifBlank { "LynxModule[$i]" }
            validateSingleHub(hubName, moduleEl, allDeviceNames, this)
        }
    }

    private fun validateSingleHub(
        hubName: String,
        moduleEl: Element,
        globalDeviceNames: MutableMap<String, String>,
        issues: MutableList<HardwareAuditIssue>,
    ) {
        val motorPorts = hashSetOf<Int>()
        val servoPorts = hashSetOf<Int>()
        val digitalPorts = hashSetOf<Int>()
        val analogPorts = hashSetOf<Int>()
        val i2cBuses = hashSetOf<Int>()

        val children = moduleEl.childNodes
        for (j in 0 until children.length) {
            val child = children.item(j) as? Element ?: continue
            val name = child.getAttribute("name").trim()
            if (name.isBlank()) {
                issues += HardwareAuditIssue("ERROR", "FTC_XML_UNNAMED_DEVICE", hubName, "<${child.tagName}> on $hubName is missing a non-blank 'name' attribute.")
            } else {
                val prevHub = globalDeviceNames.putIfAbsent(name, hubName)
                if (prevHub != null) {
                    issues += HardwareAuditIssue("ERROR", "FTC_XML_DUPLICATE_NAME", hubName, "Duplicate FTC hardwareMap device name '$name' (already defined on $prevHub).")
                }
            }

            val port = child.getAttribute("port").trim().toIntOrNull()
            val tag = child.tagName
            when {
                tag == "Servo" || tag == "ContinuousRotationServo" || tag.endsWith("Servo") -> {
                    checkPort(hubName, tag, name, port, 0 until MAX_SERVOS_PER_HUB, servoPorts, issues)
                }
                tag == "Motor" || tag == "GoBILDA5202Series" || tag.endsWith("Motor") || tag.endsWith("Gearmotor") -> {
                    checkPort(hubName, tag, name, port, 0 until MAX_MOTORS_PER_HUB, motorPorts, issues)
                }
                tag == "DigitalDevice" || tag == "RevTouchSensor" -> {
                    checkPort(hubName, tag, name, port, 0 until MAX_DIGITAL_PER_HUB, digitalPorts, issues)
                }
                tag == "AnalogInput" -> {
                    checkPort(hubName, tag, name, port, 0 until MAX_ANALOG_PER_HUB, analogPorts, issues)
                }
                else -> {
                    val busAttr = child.getAttribute("bus").trim()
                    if (busAttr.isNotEmpty()) {
                        val bus = busAttr.toIntOrNull()
                        if (bus == null || bus !in 0 until MAX_I2C_BUSES_PER_HUB) {
                            issues += HardwareAuditIssue("ERROR", "FTC_HUB_I2C_BUDGET", hubName, "I2C device '$name' on $hubName uses invalid bus '$busAttr' (valid 0..3).")
                        } else if (!i2cBuses.add(bus)) {
                            issues += HardwareAuditIssue("ERROR", "FTC_HUB_I2C_COLLISION", hubName, "I2C device '$name' on $hubName collides on bus $bus.")
                        }
                    }
                }
            }
        }
    }

    private fun checkPort(
        hubName: String,
        tag: String,
        deviceName: String,
        port: Int?,
        validRange: IntRange,
        usedPorts: MutableSet<Int>,
        issues: MutableList<HardwareAuditIssue>,
    ) {
        if (port == null || port !in validRange) {
            issues += HardwareAuditIssue(
                severity = "ERROR",
                code = "FTC_HUB_PORT_OUT_OF_RANGE",
                path = hubName,
                message = "$tag '$deviceName' on $hubName has invalid port '${port ?: "missing"}' (valid ${validRange.first}..${validRange.last}).",
            )
            return
        }
        if (!usedPorts.add(port)) {
            issues += HardwareAuditIssue(
                severity = "ERROR",
                code = "FTC_HUB_PORT_COLLISION",
                path = hubName,
                message = "$tag '$deviceName' on $hubName collides on physical port $port.",
            )
        }
    }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
