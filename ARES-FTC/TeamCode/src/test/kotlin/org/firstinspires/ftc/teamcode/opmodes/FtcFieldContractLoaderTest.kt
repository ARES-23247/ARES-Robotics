package org.firstinspires.ftc.teamcode.opmodes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Failure taxonomy of the extracted season field-contract loader. */
class FtcFieldContractLoaderTest {
    private fun fieldJson(fieldType: String = "ftc", tags: String = """{"id":1,"name":"Test tag","family":"36h11","sizeMeters":0.1651,"x":1.0,"y":1.0,"z":1.0,"roll":10.0,"pitch":20.0,"yaw":90.0}""") =
        """{"schemaVersion":2,"id":"test-field","name":"Test","fieldType":"$fieldType","widthMeters":3.6576,"heightMeters":3.6576,"apriltags":[$tags]}"""

    @Test
    fun `valid document yields config and id-indexed tags`() {
        val contract = loadFtcFieldContract(fieldJson().toByteArray())
        assertNotNull(contract)
        assertNull(FtcFieldContractLoader.error)
        assertEquals(1, contract!!.tags.size)
        assertEquals(Math.toRadians(10.0), contract.tags.getValue(1).rotation.x, 1e-9)
        assertEquals(Math.toRadians(20.0), contract.tags.getValue(1).rotation.y, 1e-9)
        assertEquals(Math.toRadians(90.0), contract.tags.getValue(1).rotation.z, 1e-9)
    }

    @Test
    fun `non-FTC geometry is rejected with a taxonomy error`() {
        assertNull(loadFtcFieldContract(fieldJson(fieldType = "frc").toByteArray()))
        assertEquals("Canonical season field must declare FTC geometry", FtcFieldContractLoader.error)
    }

    @Test
    fun `duplicate and invalid AprilTags are rejected`() {
        val tag = """{"id":1,"name":"Tag","family":"36h11","sizeMeters":0.1651,"x":1.0,"y":1.0,"z":1.0,"yaw":0.0}"""
        assertNull(loadFtcFieldContract(fieldJson(tags = "$tag, $tag").toByteArray()))
        assertEquals("FTC field contains duplicate AprilTag IDs", FtcFieldContractLoader.error)

        assertNull(loadFtcFieldContract(fieldJson(tags = """{"id":1,"name":"Tag","family":"36h11","sizeMeters":0.1651,"x":NaN,"y":1.0,"z":1.0,"yaw":0.0}""").toByteArray()))
        assertEquals("FTC field contains an invalid AprilTag", FtcFieldContractLoader.error)
    }

    @Test
    fun `undecodable bytes are rejected without throwing`() {
        assertNull(loadFtcFieldContract("not-json".toByteArray()))
        assertTrue(FtcFieldContractLoader.error != null)
    }
}
