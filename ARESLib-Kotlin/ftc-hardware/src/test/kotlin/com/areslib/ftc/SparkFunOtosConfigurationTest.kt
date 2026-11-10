package com.areslib.ftc

import com.areslib.ftc.drivetrain.SparkFunOtosIO
import com.qualcomm.hardware.sparkfun.SparkFunOTOS
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SparkFunOtosConfigurationTest {
    @Test fun `rejected SDK configuration cannot construct usable localization`() {
        for (failedStep in 0..2) {
            var calls = 0
            val driver = object : SparkFunOTOS() {
                override fun setLinearScalar(scalar: Double): Boolean { calls++; return failedStep != 0 }
                override fun setAngularScalar(scalar: Double): Boolean { calls++; return failedStep != 1 }
                override fun calibrateImu(): Boolean { calls++; return failedStep != 2 }
            }
            assertFailsWith<IllegalStateException> { SparkFunOtosIO(driver) }
            assertEquals(failedStep+1,calls,"Configuration must stop at the failed SDK operation")
        }
    }
}
