package com.ares.analytics.service.calibration

import com.ares.analytics.service.*
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.shared.models.MAX_SUPPORTED_TIMESTAMP_MS
import com.ares.analytics.service.db.TelemetryExportPreflight
import com.ares.analytics.service.db.TelemetryExportCursor
import kotlinx.coroutines.test.runTest
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.ejml.simple.SimpleMatrix
import java.nio.file.Files
import kotlin.math.*
import kotlin.test.*

class CameraCalibrationMathAuditTest {
    private val solver = CameraCalibrationSolver(mock(DatabaseService::class.java))

    @Test fun `quarter turn camera yaw uses robot axes and optical right down forward translation`() {
        assertPose(Pose3d(0.2, -0.3, 0.4, 0.0, 0.0, PI / 2), solver.solveCameraExtrinsics(
            observations(Pose3d(0.2, -0.3, 0.4, 0.0, 0.0, PI / 2))))
    }

    @Test fun `mixed mounting rotation and gyro wrap recover independently generated geometry`() {
        val expected = Pose3d(-0.14, 0.27, 0.53, 0.31, -0.47, 2.85)
        assertPose(expected, solver.solveCameraExtrinsics(observations(expected)))
    }

    @Test fun `missing repeated and collinear observations cannot report a solved pose`() {
        val sample = observations(Pose3d(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)).first()
        for (data in listOf(emptyList(), listOf(sample), List(8) { sample }, List(6) { i ->
            sample.copy(gyroHeading = 0.0, targetSpaceX = 0.0, targetSpaceY = 0.0,
                targetSpaceZ = i.toDouble(), tagFieldX = i.toDouble(), tagFieldY = 0.0, tagFieldZ = 0.0)
        })) assertFailsWith<IllegalArgumentException> { solver.solveCameraExtrinsics(data) }
    }

    @Test fun `nonfinite geometry is rejected before fitting`() {
        val data = observations(Pose3d(0.0, 0.0, 0.0, 0.0, 0.0, 0.0))
        for (bad in listOf(data[0].copy(gyroHeading = Double.NaN), data[0].copy(targetSpaceX = Double.POSITIVE_INFINITY),
            data[0].copy(tagFieldZ = Double.NaN))) {
            assertFailsWith<IllegalArgumentException> { solver.solveCameraExtrinsics(listOf(bad) + data.drop(1)) }
        }
    }

    @Test fun `empty diagnostics do not claim perfect zero uncertainty`() {
        assertFailsWith<IllegalArgumentException> { solver.solveCameraExtrinsicsWithDiagnostics(emptyList()) }
    }

    @Test fun `symmetric noisy geometry has independently calculable covariance and residual variance`() {
        val scaleError = 0.02
        val points = listOf(doubleArrayOf(1.0,0.0,0.0),doubleArrayOf(-1.0,0.0,0.0),
            doubleArrayOf(0.0,1.0,0.0),doubleArrayOf(0.0,-1.0,0.0),
            doubleArrayOf(0.0,0.0,1.0),doubleArrayOf(0.0,0.0,-1.0))
        val data = points.mapIndexed { index, p -> CalibrationMeasurement(0.0,index,
            p[0]*(1+scaleError)+0.1,p[1]*(1+scaleError)-0.2,p[2]*(1+scaleError)+0.3,
            -p[1],-p[2],p[0],0.0,0.0,0.0) }
        val result = solver.solveCameraExtrinsicsWithDiagnostics(data)
        assertPose(Pose3d(0.1,-0.2,0.3,0.0,0.0,0.0),result.pose)
        val variance = scaleError*scaleError/2 // SSE=6*s^2, residual DOF=18-6.
        assertEquals(variance,result.reducedChiSquared,1e-12)
        for (i in 0..5) for (j in 0..5) {
            val expected = if (i!=j) 0.0 else variance / if(i<3) 6.0 else 4.0
            assertEquals(expected,result.covarianceMatrix[i][j],1e-10)
            if(i==j) assertEquals(sqrt(expected),result.standardErrors[i],1e-10)
        }
    }

    @Test fun `diagnostic equality agrees with hashing for signed zero and nan`() {
        fun result(v:Double)=CalibrationDiagnostics(Pose3d(0.0,0.0,0.0,0.0,0.0,0.0),DoubleArray(6),Array(6){DoubleArray(6)},v)
        assertNotEquals(result(0.0),result(-0.0))
        assertEquals(result(Double.NaN),result(Double.NaN))
        assertEquals(result(Double.NaN).hashCode(),result(Double.NaN).hashCode())
    }

    @Test fun `malformed component names are not calibration data`() = runTest {
        withDatabase { service, db ->
            val rows = frames(observations(Pose3d(0.1,0.2,0.3,0.0,0.0,0.0))).map {
                if (it.key.contains("CameraToTag/")) it.copy(key=it.key.replace("CameraToTag/","CameraToTagGarbage")) else it
            }
            db.insertTelemetryFrames(rows)
            assertFailsWith<IllegalArgumentException> { service.runExtrinsicCalibration("calibration",0) }
        }
    }

    @Test fun `fractional camera identifiers cannot select another cameras data`() = runTest {
        withDatabase { service,db ->
            db.insertTelemetryFrames(frames(observations(Pose3d(0.1,0.2,0.3,0.0,0.0,0.0))).map {
                if(it.key.endsWith("CameraIndex")) it.copy(value=0.5) else it
            })
            assertFailsWith<IllegalArgumentException> { service.runExtrinsicCalibration("calibration",0) }
        }
    }

    @Test fun `inactive calibration observations are excluded`() = runTest {
        withDatabase { service,db ->
            db.insertTelemetryFrames(frames(observations(Pose3d(0.1,0.2,0.3,0.0,0.0,0.0))).map {
                if(it.key.endsWith("IsActive")) it.copy(value=0.0) else it
            })
            assertFailsWith<IllegalArgumentException> { service.runExtrinsicCalibration("calibration",0) }
        }
    }

    @Test fun `noncollinear tags at one heading are observable without unused optical rotation fields`() = runTest {
        val expected = Pose3d(0.2,0.3,0.4,0.1,0.2,-0.3)
        withDatabase { service,db ->
            db.insertTelemetryFrames(frames(observations(expected, stationaryHeading=true)).filterNot {
                it.key.matches(Regex("Calibration/CameraToTag/[345]"))
            })
            assertPose(expected,service.runExtrinsicCalibration("calibration",0))
        }
    }

    @Test fun `unrelated oversized component suffix is ignored without aborting a valid run`() = runTest {
        val expected=Pose3d(0.2,0.3,0.4,0.1,0.2,-0.3)
        withDatabase { service,db ->
            db.insertTelemetryFrames(frames(observations(expected))+TelemetryFrame(1000,"calibration",
                "Calibration/CameraToTag/999999999999999999999999999999999",42.0))
            assertPose(expected,service.runExtrinsicCalibration("calibration",0))
        }
    }

    @Test fun `noncollinear planar geometry remains identifiable`() {
        val expected = Pose3d(0.3, -0.1, 0.5, -0.2, 0.4, -0.6)
        val data = List(9) { i ->
            val aligned = doubleArrayOf(1.0+(i%3)*0.2, -0.3+(i/3)*0.3, 0.0)
            val mounted = rotate(rotate(rotate(aligned,0,expected.roll),1,expected.pitch),2,expected.yaw)
            CalibrationMeasurement(0.0,i,mounted[0]+expected.x,mounted[1]+expected.y,mounted[2]+expected.z,
                -aligned[1],-aligned[2],aligned[0],0.0,0.0,0.0)
        }
        assertPose(expected, solver.solveCameraExtrinsics(data))
        assertPose(expected, solver.solveCameraExtrinsicsWithDiagnostics(data).pose)
    }

    @Test fun `pitch gimbal lock has a canonical pose but cannot claim finite Euler uncertainty`() {
        for (sign in listOf(-1,1)) {
            val data = observations(Pose3d(0.2,0.3,0.4,0.4,sign*PI/2,1.0))
            assertPose(Pose3d(0.2,0.3,0.4,0.0,sign*PI/2,1.0-sign*0.4),solver.solveCameraExtrinsics(data))
            assertFailsWith<IllegalArgumentException> { solver.solveCameraExtrinsicsWithDiagnostics(data) }
        }
    }

    @Test fun `mixed-angle uncertainty matches an independent central-difference Jacobian`() {
        val data = observations(Pose3d(0.2,0.3,0.4,0.3,-0.4,0.7),true).mapIndexed { i,m ->
            m.copy(tagFieldX=m.tagFieldX+0.003*sin(i*4.0),tagFieldY=m.tagFieldY+0.002*cos(i*3.0),tagFieldZ=m.tagFieldZ+0.001*sin(i*2.0))
        }
        val result = solver.solveCameraExtrinsicsWithDiagnostics(data)
        val p = result.pose.let { doubleArrayOf(it.x,it.y,it.z,it.roll,it.pitch,it.yaw) }
        fun predict(parameters:DoubleArray,m:CalibrationMeasurement):DoubleArray {
            val aligned=doubleArrayOf(m.targetSpaceZ,-m.targetSpaceX,-m.targetSpaceY)
            val mounted=rotate(rotate(rotate(aligned,0,parameters[3]),1,parameters[4]),2,parameters[5])
            return DoubleArray(3) { mounted[it]+parameters[it] }
        }
        val jacobian=SimpleMatrix(data.size*3,6)
        val step=1e-5
        for (j in 0..5) {
            val plus=p.copyOf().also { it[j]+=step };val minus=p.copyOf().also { it[j]-=step }
            data.forEachIndexed { i,m ->
                val a=predict(plus,m);val b=predict(minus,m)
                for(k in 0..2) jacobian.set(3*i+k,j,(a[k]-b[k])/(2*step))
            }
        }
        val expected=jacobian.transpose().mult(jacobian).invert().scale(result.residualVariance)
        assertTrue(result.residualVariance>0.0)
        for(i in 0..5) for(j in 0..5) assertEquals(expected.get(i,j),result.covarianceMatrix[i][j],1e-10)
        assertEquals(solver.solveCameraExtrinsics(data),result.pose)
    }

    @Test fun `both fitting entry points scan the observation list once regardless of sample count`() {
        val expected=Pose3d(0.1,0.2,0.3,0.2,-0.3,0.4)
        val originals=observations(expected)
        var reads=0
        val counted=object:AbstractList<CalibrationMeasurement>() {
            override val size=12_000
            override fun get(index:Int):CalibrationMeasurement {reads++;return originals[index%originals.size]}
        }
        assertPose(expected,solver.solveCameraExtrinsics(counted))
        assertEquals(counted.size,reads)
        reads=0
        assertPose(expected,solver.solveCameraExtrinsicsWithDiagnostics(counted).pose)
        assertEquals(counted.size,reads)
        println("Camera fit input accesses: 12000 per 12000 observations, both entry points")
    }

    @Test fun `sample skew boundaries and supported timestamp extremes cannot admit stale components`() {
        val input=frames(observations(Pose3d(0.0,0.0,0.0,0.0,0.0,0.0)).take(1))
        for(delta in listOf(-100L,100L)) assertEquals(1,calibrationMeasurements(input.map {
            if(it.key=="Calibration/CameraToTag/0") it.copy(timestampMs=1000+delta,timestampUs=(1000+delta)*1000) else it
        },0).size)
        for(delta in listOf(-101L,101L)) assertTrue(calibrationMeasurements(input.map {
            if(it.key=="Calibration/CameraToTag/0") it.copy(timestampMs=1000+delta,timestampUs=(1000+delta)*1000) else it
        },0).isEmpty())
        assertTrue(calibrationMeasurements(input.map {
            if(it.key=="Calibration/CameraToTag/0") it.copy(timestampMs=0L,timestampUs=0L)
            else it.copy(timestampMs=MAX_SUPPORTED_TIMESTAMP_MS,timestampUs=MAX_SUPPORTED_TIMESTAMP_MS*1000)
        },0).isEmpty())
    }

    @Test fun `scoped camera vectors override generic data without mixing incomplete namespaces`() {
        val input=frames(observations(Pose3d(0.0,0.0,0.0,0.0,0.0,0.0)).take(1))
        val scoped=input.filter { it.key.startsWith("Calibration/CameraToTag/") }.map {
            it.copy(key=it.key.replace("CameraToTag/","CameraToTag/0/"),value=it.value+3.0)
        }
        val value=calibrationMeasurements(input+scoped,0).single()
        assertEquals(scoped.first().value,value.targetSpaceX)
        assertTrue(calibrationMeasurements(input+scoped.filterNot { it.key.endsWith("/1") },0).isEmpty())
        assertEquals(calibrationMeasurements(input,0),calibrationMeasurements(input+scoped.map {
            it.copy(key=it.key.replace("CameraToTag/0/","CameraToTag/1/"))
        },0))
    }

    @Test fun `loader rejects invalid identities and normalizes leading slashes`() {
        val input=frames(observations(Pose3d(0.0,0.0,0.0,0.0,0.0,0.0)).take(1))
        assertEquals(calibrationMeasurements(input,0),calibrationMeasurements(input.map { it.copy(key="//"+it.key) },0))
        for(key in listOf("Calibration/CameraIndex","Calibration/TagIndex")) for(value in listOf(-1.0,0.5,Double.NaN,Double.POSITIVE_INFINITY,2147483648.0)) {
            assertTrue(calibrationMeasurements(input.map { if(it.key==key) it.copy(value=value) else it },0).isEmpty())
        }
        assertTrue(calibrationMeasurements(input.filterNot { it.key=="Calibration/CameraIndex" },0).isEmpty())
        assertEquals(1,calibrationMeasurements(input.filterNot { it.key=="Calibration/IsActive" },0).size)
        assertFailsWith<IllegalArgumentException> { calibrationMeasurements(input,-1) }
    }

    @Test fun `equal-distance sample selection is deterministic and nonfinite selected components invalidate observations`() {
        val input=frames(observations(Pose3d(0.0,0.0,0.0,0.0,0.0,0.0)).take(1))
        val key="Calibration/CameraToTag/0"
        val point=input.first { it.key==key }
        val rows=input.filterNot { it.key==key }+point.copy(timestampMs=950,timestampUs=950000,value=1.0)+point.copy(timestampMs=1050,timestampUs=1050000,value=2.0)
        assertEquals(1.0,calibrationMeasurements(rows.reversed(),0).single().targetSpaceX)
        assertTrue(calibrationMeasurements(input.map { if(it.key==key) it.copy(value=Double.NaN) else it },0).isEmpty())
    }

    @Test fun `finite geometry with overflowing products cannot become a successful pose`() {
        val data=observations(Pose3d(0.0,0.0,0.0,0.0,0.0,0.0)).mapIndexed { i,m ->
            m.copy(targetSpaceX=if(i%2==0) Double.MAX_VALUE else -Double.MAX_VALUE)
        }
        assertFailsWith<IllegalArgumentException> { solver.solveCameraExtrinsics(data) }
    }

    @Test fun `reflected symmetric point sets do not produce an arbitrary confident proper rotation`() {
        val points=listOf(doubleArrayOf(1.0,0.0,0.0),doubleArrayOf(-1.0,0.0,0.0),
            doubleArrayOf(0.0,1.0,0.0),doubleArrayOf(0.0,-1.0,0.0),
            doubleArrayOf(0.0,0.0,1.0),doubleArrayOf(0.0,0.0,-1.0))
        val data=points.mapIndexed { i,p -> CalibrationMeasurement(0.0,i,-p[0],p[1],p[2],-p[1],-p[2],p[0],0.0,0.0,0.0) }
        assertFailsWith<IllegalArgumentException> { solver.solveCameraExtrinsics(data) }
    }

    @Test fun `submillisecond source timestamps choose the actually nearest observation`() {
        val input=frames(observations(Pose3d(0.0,0.0,0.0,0.0,0.0,0.0)).take(1)).map { it.copy(timestampUs=1000100) }
        val point=input.first { it.key=="Calibration/CameraToTag/0" }
        val rows=input.filterNot { it.key==point.key }+point.copy(timestampUs=1000050,value=1.0)+point.copy(timestampUs=1000900,value=2.0)
        assertEquals(1.0,calibrationMeasurements(rows,0).single().targetSpaceX)
    }

    @Test fun `text samples cannot authorize from numeric placeholders or become numeric geometry`() {
        val input=frames(observations(Pose3d(0.0,0.0,0.0,0.0,0.0,0.0)).take(1))
        for(key in listOf("Calibration/IsActive","Calibration/CameraIndex","Calibration/TagIndex","Calibration/CameraToTag/0")) {
            assertTrue(calibrationMeasurements(input.map { if(it.key==key) it.copy(stringValue="false") else it },0).isEmpty())
        }
        assertEquals(1,calibrationMeasurements(input.map {
            if(it.key=="Calibration/IsActive") it.copy(stringValue="true",value=0.0) else it
        },0).size)
    }

    @Test fun `database loading selects only relevant topics and reads successive pages without downsampling`() = runTest {
        val expected=Pose3d(0.2,0.3,0.4,0.3,-0.4,0.7)
        val keys=calibrationTopicKeys(0)
        assertEquals(16,keys.size)
        val rows=frames(observations(expected)).filter { it.key in keys }
            .sortedWith(compareBy<TelemetryFrame> { it.timestampUs }.thenBy { it.sampleOrder }.thenBy { it.key })
        val first=rows.take(41);val second=rows.drop(41)
        fun cursor(page:List<TelemetryFrame>)=page.last().let { TelemetryExportCursor(it.timestampUs,it.sampleOrder,it.key) }
        val db=mock(DatabaseService::class.java)
        `when`(db.getTelemetryExportPreflight("calibration",keys,100_000)).thenReturn(TelemetryExportPreflight(rows.size.toLong(),1000,4000))
        `when`(db.getTelemetryExportPage("calibration",keys,null,25_000)).thenReturn(first)
        `when`(db.getTelemetryExportPage("calibration",keys,cursor(first),25_000)).thenReturn(second)
        `when`(db.getTelemetryExportPage("calibration",keys,cursor(second),25_000)).thenReturn(emptyList())
        assertPose(expected,CameraCalibrationSolver(db).runExtrinsicCalibration("calibration",0))
        verify(db).getTelemetryExportPreflight("calibration",keys,100_000)
        verify(db).getTelemetryExportPage("calibration",keys,null,25_000)
        verify(db).getTelemetryExportPage("calibration",keys,cursor(first),25_000)
        verify(db).getTelemetryExportPage("calibration",keys,cursor(second),25_000)
        verifyNoMoreInteractions(db)
    }

    @Test fun `oversized or changing recordings are not silently truncated into calibration results`() = runTest {
        val keys=calibrationTopicKeys(0)
        val db=mock(DatabaseService::class.java)
        `when`(db.getTelemetryExportPreflight("calibration",keys,100_000)).thenReturn(TelemetryExportPreflight(100_001,1000,4000))
        assertFailsWith<IllegalArgumentException> { CameraCalibrationSolver(db).runExtrinsicCalibration("calibration",0) }
        verify(db).getTelemetryExportPreflight("calibration",keys,100_000)
        verifyNoMoreInteractions(db)
        val changed=mock(DatabaseService::class.java)
        `when`(changed.getTelemetryExportPreflight("calibration",keys,100_000)).thenReturn(TelemetryExportPreflight(1,1000,1000))
        `when`(changed.getTelemetryExportPage("calibration",keys,null,25_000)).thenReturn(emptyList())
        assertFailsWith<IllegalArgumentException> { CameraCalibrationSolver(changed).runExtrinsicCalibration("calibration",0) }
    }

    companion object {
        // Fixture applies individual physical axis rotations, never the solver's matrix construction.
        internal fun observations(pose:Pose3d,stationaryHeading:Boolean=false):List<CalibrationMeasurement> =
            List(12) { i ->
                val optical=doubleArrayOf(sin(i*0.8)*0.7,cos(i*0.6)*0.4,1.5+i*0.09)
                val aligned=doubleArrayOf(optical[2],-optical[0],-optical[1])
                val mounted=rotate(rotate(rotate(aligned,0,pose.roll),1,pose.pitch),2,pose.yaw)
                val heading=if(stationaryHeading) 0.0 else 2.9+i*0.11
                val field=rotate(doubleArrayOf(mounted[0]+pose.x,mounted[1]+pose.y,mounted[2]+pose.z),2,heading)
                CalibrationMeasurement(heading,i,field[0],field[1],field[2],optical[0],optical[1],optical[2],0.0,0.0,0.0)
            }
        private fun rotate(v:DoubleArray,axis:Int,angle:Double):DoubleArray {
            val result=v.copyOf();val a=(axis+1)%3;val b=(axis+2)%3
            result[a]=cos(angle)*v[a]-sin(angle)*v[b]
            result[b]=sin(angle)*v[a]+cos(angle)*v[b]
            return result
        }
        internal fun assertPose(expected:Pose3d,actual:Pose3d,tolerance:Double=1e-7) {
            assertEquals(expected.x,actual.x,tolerance,"x");assertEquals(expected.y,actual.y,tolerance,"y")
            assertEquals(expected.z,actual.z,tolerance,"z");assertEquals(expected.roll,actual.roll,tolerance,"roll")
            assertEquals(expected.pitch,actual.pitch,tolerance,"pitch");assertEquals(expected.yaw,actual.yaw,tolerance,"yaw")
        }
        internal fun frames(data:List<CalibrationMeasurement>):List<TelemetryFrame> = buildList {
            data.forEachIndexed { i,m ->
                val t=1000L+i*250
                fun add(key:String,value:Double){add(TelemetryFrame(t,"calibration","Calibration/$key",value))}
                add("GyroHeading",m.gyroHeading);add("TagIndex",m.tagId.toDouble());add("CameraIndex",0.0);add("IsActive",1.0)
                listOf(m.targetSpaceX,m.targetSpaceY,m.targetSpaceZ,0.0,0.0,0.0).forEachIndexed { j,v -> add("CameraToTag/$j",v) }
                listOf(m.tagFieldX,m.tagFieldY,m.tagFieldZ).forEachIndexed { j,v -> add("TagField/$j",v) }
            }
        }
        private suspend fun withDatabase(block:suspend (CalibrationService,DatabaseService)->Unit) {
            val dir=Files.createTempDirectory("ares-camera-math-audit-").toFile()
            val db=DatabaseService(dir.resolve("test.db").absolutePath)
            try { block(CalibrationService(db),db) } finally {
                db.close();check(dir.name.startsWith("ares-camera-math-audit-"));check(dir.deleteRecursively())
            }
        }
    }
}
