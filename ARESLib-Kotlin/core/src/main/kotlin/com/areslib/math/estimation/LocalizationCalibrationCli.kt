package com.areslib.math.estimation

import java.io.File

/** Offline CLI: `... LocalizationCalibrationCli <log.csv>... [--output report.json]`. */
object LocalizationCalibrationCli {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isNotEmpty()) { "Provide one or more localization calibration CSV files" }
        val files = ArrayList<File>()
        var output: File? = null
        var i = 0
        while (i < args.size) {
            if (args[i] == "--output") {
                require(i + 1 < args.size) { "--output requires a path" }
                output = File(args[++i])
            } else {
                files += File(args[i])
            }
            i++
        }
        require(files.isNotEmpty()) { "Provide one or more localization calibration CSV files" }
        require(files.all(File::isFile)) { "Every input must be an existing calibration CSV" }
        val destination = output
        require(destination == null || files.none {
            it.canonicalFile == destination.canonicalFile ||
                (destination.exists() && java.nio.file.Files.isSameFile(it.toPath(), destination.toPath()))
        }) { "Calibration output must not overwrite an input log" }
        val report = LocalizationCalibrationFitter.fit(LocalizationCalibrationCsv.read(files))
        val json = report.toJson()
        println(json)
        output?.writeText(json)
    }
}
