package com.ares.analytics.service

import com.ares.analytics.shared.models.League
import com.areslib.codegen.GeneratedSubsystemFile
import com.areslib.codegen.SubsystemKotlinCodegenTarget
import com.areslib.codegen.SubsystemKotlinGenerator
import com.areslib.codegen.SubsystemStarterPlan
import com.areslib.codegen.SubsystemStarterReconciler
import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.isAresGenerated
import java.io.File

/** Builds deterministic starter proposals from canonical subsystem descriptors. */
internal class SubsystemStarterPlanner {
    fun plan(root: File, league: League): SubsystemStarterPlan {
        if (league == League.XRP) return SubsystemStarterPlan(emptyList(), null)
        val inputs = inputs(root, league)
        return SubsystemStarterReconciler.plan(inputs.root.toPath(), inputs.files)
    }

    private fun inputs(root: File, league: League): Inputs {
        val platform = when (league) {
            League.FTC -> SubsystemPlatform.FTC
            League.FRC -> SubsystemPlatform.FRC
            League.XRP -> error("XRP uses its standalone Python generator")
        }
        val basePackage = when (league) {
            League.FTC -> "org.firstinspires.ftc.teamcode.subsystems"
            League.FRC -> "com.areslib.frc.subsystems"
            League.XRP -> error("XRP uses its standalone Python generator")
        }
        val starterRoot = when (league) {
            League.FTC -> File(root, "TeamCode/src/main/java/${basePackage.replace('.', '/')}")
            League.FRC -> File(root, "src/main/kotlin/${basePackage.replace('.', '/')}")
            League.XRP -> error("XRP uses its standalone Python generator")
        }.canonicalFile
        require(starterRoot.toPath().startsWith(root.toPath())) { "Subsystem starter root escaped the project" }

        val documents = File(root, ".ares/subsystems").canonicalFile
            .listFiles { file -> file.isFile && file.extension.equals("aressubsystem", true) }
            .orEmpty()
            .sortedBy { it.name.lowercase() }
            .map { SubsystemDocumentCodec.decode(it.readText()) }
            .filter { it.platform == platform && it.implementation.kind.isAresGenerated() }
        val target = SubsystemKotlinCodegenTarget(platform, basePackage)
        return Inputs(starterRoot, documents.flatMap { SubsystemKotlinGenerator.generate(it, target) })
    }

    private data class Inputs(
        val root: File,
        val files: List<GeneratedSubsystemFile>,
    )
}
