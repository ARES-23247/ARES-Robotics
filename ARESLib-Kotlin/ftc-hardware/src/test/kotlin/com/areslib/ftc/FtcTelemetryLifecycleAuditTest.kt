package com.areslib.ftc

import com.areslib.Store
import com.areslib.action.ActionLogger
import com.areslib.action.RobotAction
import com.areslib.control.safety.BrownoutGuard
import com.areslib.ftc.telemetry.FtcTelemetryManager
import com.areslib.ftc.vision.FtcVisionTracker
import com.areslib.hardware.HardwareRegistry
import com.areslib.state.Alliance
import com.areslib.telemetry.RobotStatusTracker
import com.areslib.util.RobotClock
import org.firstinspires.ftc.robotcore.external.Telemetry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class FtcTelemetryLifecycleAuditTest {
    private val originalMode = RobotStatusTracker.activeOpMode
    @AfterTest fun restoreGlobals() {
        RobotClock.useSystemTime()
        RobotStatusTracker.activeOpMode = originalMode
    }

    @Test fun `action mode transition cannot wait for a blocked writer`() = withManager { store, manager ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val hook: () -> Unit = { entered.countDown(); check(release.await(5,TimeUnit.SECONDS)) }
        ActionLogger::class.java.getDeclaredField("beforeWriteForTest").apply { isAccessible=true }
            .set(manager.actionLogger,hook)
        store.dispatch(RobotAction.SetAlliance(Alliance.BLUE))
        val transition = Thread({
            try {
                RobotStatusTracker.activeOpMode = "TeleOp"
                publish(store,manager,2000)
            } catch (failure: Throwable) { error.set(failure) }
        },"audit-owned-action-transition")
        try {
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            transition.start()
            transition.join(1000)
            assertFalse(transition.isAlive,"Mode transition must not drain action files on the control loop")
        } finally {
            release.countDown()
            transition.join(3000)
            check(!transition.isAlive)
        }
        error.get()?.let { throw it }
    }

    @Test fun `first action after mode change receives the new mode before publish`() = withManager { store, manager ->
        RobotStatusTracker.activeOpMode = "TeleOp"
        store.dispatch(RobotAction.SetAlliance(Alliance.BLUE))
        assertEquals("TeleOp",manager.actionLogger.mode)
    }

    @Test fun `publisher failure restores out of frame network forwarding`() = withManager { store, manager ->
        manager.enableNetworkStreaming=false
        val failure=IllegalStateException("custom publisher")
        assertSame(failure,assertFails { publish(store,manager,1000) { throw failure } })
        assertTrue(manager.dataLoggingTelemetry.ntEnabled)
    }

    @Test fun `close inhibits publishes and detaches only its own action callback`() = withManager { store, manager ->
        manager.close()
        assertNull(store.actionListener)
        assertFailsWith<IllegalStateException> { publish(store,manager,1000) }
        assertFailsWith<IllegalStateException> { manager.publish(store.state,null,null,0.02,12.0) }
    }

    @Test fun `closing a replaced listener preserves its successor`() = withManager { store, manager ->
        val successor: (RobotAction) -> Unit = {}
        store.actionListener=successor
        manager.close()
        assertSame(successor,store.actionListener)
    }

    @Test fun `a slow Driver Station receives the newest pending snapshot`() = withManager { store, manager ->
        val entered=CountDownLatch(1)
        val release=CountDownLatch(1)
        val next=CountDownLatch(1)
        val latest=AtomicReference<String>()
        var calls=0
        var currentText=""
        val console=object : Telemetry {
            override fun addData(caption: String,value: Any?): Telemetry.Item? {
                if (caption=="Sequence") currentText=value.toString()
                return null
            }
            override fun addData(caption: String,format: String,vararg args: Any?): Telemetry.Item? = null
            override fun update(): Boolean {
                calls++
                latest.set(currentText)
                if(calls==1) { entered.countDown(); check(release.await(5,TimeUnit.SECONDS)) }
                else next.countDown()
                return true
            }
        }
        try {
            manager.customDriverStationText["Sequence"]="0"
            publish(store,manager,1000,console)
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            for(i in 1..5) {
                manager.customDriverStationText["Sequence"]=i.toString()
                publish(store,manager,1000+i*250L,console)
            }
            release.countDown()
            assertTrue(next.await(2,TimeUnit.SECONDS))
            assertEquals("5",latest.get())
        } finally { release.countDown() }
    }

    @Test fun `Driver Station uses literal text and handles zero and rewound timestamps`() = withManager { store,manager ->
        val messages=java.util.concurrent.LinkedBlockingQueue<String>()
        var currentText=""
        val console=object: Telemetry {
            override fun addData(caption: String,value: Any?): Telemetry.Item? {
                if(caption=="Sequence") currentText=value.toString()
                return null
            }
            override fun addData(caption: String,format: String,vararg args: Any?): Telemetry.Item? {
                error("Raw telemetry must not invoke the formatting overload")
            }
            override fun update(): Boolean { messages.offer(currentText); return true }
        }
        for((time,label) in listOf(0L to "0%",1000L to "100%",500L to "50%")) {
            manager.customDriverStationText["Sequence"]=label
            publish(store,manager,time,console)
            assertEquals(label,messages.poll(2,TimeUnit.SECONDS))
        }
        val field=FtcTelemetryManager::class.java.getDeclaredField("driverStationThread").apply { isAccessible=true }
        val worker=field.get(manager) as Thread
        manager.close()
        assertFalse(worker.isAlive,"An unblocked owned Driver Station worker must be joined")
    }

    private fun withManager(action: (Store,FtcTelemetryManager)->Unit) {
        RobotStatusTracker.activeOpMode="Init"
        RobotClock.useMockTime(1000)
        val store=Store()
        val manager=FtcTelemetryManager(store,HardwareRegistry())
        try { action(store,manager) } finally { manager.close() }
    }

    private fun publish(store: Store,manager: FtcTelemetryManager,time: Long,console: Telemetry?=null,hook: ()->Unit={}) {
        RobotClock.useMockTime(time)
        manager.publishFull(store.state,null,null,0.02,12.0,BrownoutGuard.ftcDefaults(),
            FtcVisionTracker(store,null,null),time,console,hook)
    }
}
