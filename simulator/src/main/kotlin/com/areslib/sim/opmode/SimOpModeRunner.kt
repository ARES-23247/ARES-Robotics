package com.areslib.sim.opmode

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode
import com.qualcomm.robotcore.eventloop.opmode.OpMode
import com.qualcomm.robotcore.eventloop.opmode.Autonomous
import com.qualcomm.robotcore.eventloop.opmode.Disabled
import com.qualcomm.robotcore.eventloop.opmode.TeleOp
import java.io.File
import java.util.jar.JarFile

/**
 * Discovers FTC OpModes by scanning classpath directories/JARs and publishes selectable names to
 * both the custom and WPILib NT4 servers.
 *
 * Discovery is an initialization-time, reflective operation and is intentionally not a hot path.
 * Unloadable classes and optional SDK annotations are skipped. If no enabled OpMode is found, the
 * ARES hardware test OpMode is advertised as a fallback.
 */
object SimOpModeRunner {

    /**
     * Scans the current classpath and publishes enabled TeleOp/Autonomous class-name JSON arrays.
     * Failures are logged and do not escape to simulator startup.
     */
    fun scanAndPublishOpModes() {
        try {
            val disabledOpModes = findAnnotatedClasses(Disabled::class.java).mapTo(mutableSetOf(), Class<*>::getName)
            val enabledFilter: (Class<*>) -> Boolean = { opMode ->
                !opMode.name.startsWith("org.firstinspires.ftc.robotcontroller") &&
                    !opMode.isAnnotationPresent(Disabled::class.java) &&
                    opMode.name !in disabledOpModes &&
                    SimOpModeLifecycle.supports(opMode) &&
                    !java.lang.reflect.Modifier.isAbstract(opMode.modifiers)
            }
            var teleops = findAnnotatedClasses(TeleOp::class.java).filter(enabledFilter).map(Class<*>::getName)
            var autos = findAnnotatedClasses(Autonomous::class.java).filter(enabledFilter).map(Class<*>::getName)

            // A TeleOp fallback is useful for a library-only simulator. Never advertise it as an
            // autonomous: lifecycle kind is annotation-derived and mode substitution is unsafe.
            if (teleops.isEmpty()) {
                teleops = listOf("com.areslib.ftc.hardware.AresHardwareTestOpMode")
            }

            val gson = com.google.gson.Gson()
            val teleOpJson = gson.toJson(teleops)
            val autoJson = gson.toJson(autos)

            // Ensure NT4Server is active
            if (com.areslib.networktables.NT4Server.getInstance() == null) {
                com.areslib.networktables.NT4Instance.defaultInstance.startServer("0.0.0.0", 5810)
            }

            // Publish to pure Java NT4Server for ARES-Analytics dashboard
            com.areslib.networktables.NT4Server.publishTopic("ARES/DriverStation/TeleOpList", teleOpJson)
            com.areslib.networktables.NT4Server.publishTopic("ARES/DriverStation/AutonomousList", autoJson)
            com.areslib.networktables.NT4Instance.defaultInstance.defaultServer?.flush()

            // Publish to WPILib NT4 instance for AdvantageScope compatibility if active
            try {
                val ntInst = edu.wpi.first.networktables.NetworkTableInstance.getDefault()
                val teleOpTopic = ntInst.getStringTopic("ARES/DriverStation/TeleOpList")
                teleOpTopic.publish().set(teleOpJson)
                teleOpTopic.setRetained(true)

                val autoTopic = ntInst.getStringTopic("ARES/DriverStation/AutonomousList")
                autoTopic.publish().set(autoJson)
                autoTopic.setRetained(true)
            } catch (_: Throwable) {}

            println("[Simulator] Published ${teleops.size} TeleOps and ${autos.size} Autos to NT4")
        } catch (e: Exception) {
            println("[Simulator] Error scanning OpModes: ${e.message}")
        }
    }

    private fun findAnnotatedClasses(annotationClass: Class<out Annotation>): List<Class<*>> {
        val result = ArrayList<Class<*>>()
        val classPath = System.getProperty("java.class.path", "")
        val entries = classPath.split(File.pathSeparator).filter { it.isNotEmpty() }

        for (entry in entries) {
            val file = File(entry)
            if (!file.exists()) continue
            if (file.isDirectory) {
                scanDir(file, file, annotationClass, result)
            } else if (file.name.endsWith(".jar")) {
                scanJar(file, annotationClass, result)
            }
        }
        return result
    }

    private fun scanDir(baseDir: File, currentDir: File, annotationClass: Class<out Annotation>, result: MutableList<Class<*>>) {
        val files = currentDir.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory) {
                scanDir(baseDir, f, annotationClass, result)
            } else if (f.name.endsWith(".class") && !f.name.contains('$')) {
                val relativePath = baseDir.toURI().relativize(f.toURI()).path
                val className = relativePath.removeSuffix(".class").replace('/', '.').replace('\\', '.')
                tryLoadClass(className, annotationClass, result)
            }
        }
    }

    private fun scanJar(jarFile: File, annotationClass: Class<out Annotation>, result: MutableList<Class<*>>) {
        try {
            JarFile(jarFile).use { jar ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    if (name.endsWith(".class") && !name.contains('$')) {
                        val className = name.removeSuffix(".class").replace('/', '.')
                        tryLoadClass(className, annotationClass, result)
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun tryLoadClass(className: String, annotationClass: Class<out Annotation>, result: MutableList<Class<*>>) {
        if (className.startsWith("java.") || className.startsWith("javax.") || className.startsWith("jdk.") || className.startsWith("org.lwjgl") || className.startsWith("org.dyn4j")) return
        try {
            val clazz = Class.forName(className, false, Thread.currentThread().contextClassLoader)
            if (clazz.isAnnotationPresent(annotationClass)) {
                result.add(clazz)
            }
        } catch (_: Throwable) {}
    }

    /**
     * Resolves [opModeClassName] as a fully qualified class, a known team package name, a discovered
     * simple/annotation name. Both iterative [OpMode] and [LinearOpMode] classes are supported.
     * An unknown, abstract, or unsupported class fails closed instead of silently running a
     * different OpMode.
     */
    fun createOpModeInstance(
        opModeArg: Any?,
        opModeClassName: String?
    ): SimOpModeLifecycle? {
        if (opModeArg != null) {
            return SimOpModeLifecycle.wrap(opModeArg)
                ?: throw IllegalArgumentException("Unsupported OpMode type: ${opModeArg.javaClass.name}")
        }
        if (opModeClassName.isNullOrBlank()) return null

        val name = opModeClassName.trim()
        val candidates = listOf(
            name,
            "org.firstinspires.ftc.teamcode.opmodes.$name",
            "org.firstinspires.ftc.teamcode.$name",
            "com.areslib.ftc.hardware.$name"
        )
        
        for (candidate in candidates) {
            try {
                val clazz = Class.forName(candidate)
                val instance = instantiateSupported(clazz)
                if (instance != null) {
                    println("[Simulator] Successfully instantiated OpMode class: $candidate")
                    return instance
                }
            } catch (_: Exception) {}
        }

        // Fallback: search discovered OpModes by simple class name or annotation name
        try {
            val teleOpClass = TeleOp::class.java
            val autoClass = Autonomous::class.java
            val allOpModes = findAnnotatedClasses(teleOpClass) + findAnnotatedClasses(autoClass)
            
            for (clazz in allOpModes) {
                if (clazz.simpleName.equals(name, ignoreCase = true) || clazz.name.equals(name, ignoreCase = true)) {
                    val instance = instantiateSupported(clazz)
                    if (instance != null) {
                        println("[Simulator] Successfully matched and instantiated OpMode class by simple name: ${clazz.name}")
                        return instance
                    }
                }
                val teleAnno = clazz.getAnnotation(teleOpClass)
                if (teleAnno != null) {
                    val annoName = teleAnno.name
                    if (annoName.equals(name, ignoreCase = true)) {
                        val instance = instantiateSupported(clazz)
                        if (instance != null) {
                            println("[Simulator] Successfully matched OpMode by TeleOp annotation name '$annoName': ${clazz.name}")
                            return instance
                        }
                    }
                }
                val autoAnno = clazz.getAnnotation(autoClass)
                if (autoAnno != null) {
                    val annoName = autoAnno.name
                    if (annoName.equals(name, ignoreCase = true)) {
                        val instance = instantiateSupported(clazz)
                        if (instance != null) {
                            println("[Simulator] Successfully matched OpMode by Autonomous annotation name '$annoName': ${clazz.name}")
                            return instance
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("[Simulator] Error searching OpModes by annotation: ${e.message}")
        }

        System.err.println("[Simulator] OpMode '$opModeClassName' was not found or is unsupported")
        return null
    }

    private fun instantiateSupported(clazz: Class<*>): SimOpModeLifecycle? {
        if (!SimOpModeLifecycle.supports(clazz) || java.lang.reflect.Modifier.isAbstract(clazz.modifiers)) return null
        return SimOpModeLifecycle.wrap(clazz.getDeclaredConstructor().newInstance())
    }

}
