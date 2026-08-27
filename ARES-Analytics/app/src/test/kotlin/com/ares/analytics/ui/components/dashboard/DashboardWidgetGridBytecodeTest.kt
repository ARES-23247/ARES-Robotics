package com.ares.analytics.ui.components.dashboard

import kotlin.test.Test

class DashboardWidgetGridBytecodeTest {
    @Test
    fun generatedDashboardGridLambdaIsValidJvmBytecode() {
        // A labeled return from the Compose key lambda previously produced an invalid
        // $$$$$NON_LOCAL_RETURN$$$$$.<anonymous> method reference. Compilation passed,
        // but the JVM threw ClassFormatError when the dashboard first rendered. The
        // regression guard is "every synthetic class the compiler generates for this
        // file links cleanly" — the exact synthetic naming differs between compiler
        // versions (K1 emitted DashboardWidgetGridKt$DashboardWidgetGrid$1; K2 does
        // not), so never pin one generated name.
        val loader = javaClass.classLoader!!
        val resources = loader.getResources("com/ares/analytics/ui/components/dashboard")
        var checked = 0
        for (url in java.util.Collections.list(resources)) {
            val dir = java.io.File(url.toURI())
            val files = dir.listFiles { f -> f.name.startsWith("DashboardWidgetGrid") }
            if (files != null) {
                for (file in files) {
                    val binaryName = "com.ares.analytics.ui.components.dashboard." + file.name.removeSuffix(".class")
                    Class.forName(binaryName, true, loader)
                    checked++
                }
            }
        }
        kotlin.test.assertTrue(checked > 0, "expected generated DashboardWidgetGrid classes on the test classpath")
    }
}
