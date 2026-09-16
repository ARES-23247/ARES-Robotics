// ARES OWNERSHIP: USER-OWNED
package org.firstinspires.ftc.teamcode.vision

import com.areslib.hardware.vision.AprilTagCluster
import com.areslib.hardware.vision.AprilTagClusterMember

/** FIRST SDK 12.0.0 AprilTagGameDatabase.getBioBuzzTagLibrary geometry, converted inches -> meters.
 * IDs identify RED SCORING (30..33), RED AUDIENCE (34..37), BLUE AUDIENCE (38..41), BLUE SCORING (42..45).
 * SDK member positions describe tag centers relative to the cell opening in the raw tag object frame.
 * Negating those positions gives the tag-to-opening offsets consumed by Limelight optical poses.
 * No absolute field coordinates are assigned to these moving targets.
 */
object BiobuzzTagClusters {
    const val TAG_SIZE_MILLIMETERS = 82.55
    val clusters: List<AprilTagCluster> = listOf(
        cluster("red-scoring", 30), cluster("red-audience", 34),
        cluster("blue-audience", 38), cluster("blue-scoring", 42)
    )
    private fun cluster(id: String, first: Int): AprilTagCluster = AprilTagCluster(id,
        listOf(-6.5, -2.75, 2.75, 6.5).mapIndexed { i, x ->
            AprilTagClusterMember(first + i, -x * 0.0254, -7.1874 * 0.0254, 5.622 * 0.0254)
        })
}
