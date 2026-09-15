package com.areslib.state

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.math.estimation.ApplyPoseEstimatorRuntimeResult
import com.areslib.reducer.rootReducer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StoreFailureAuditTest {
    private fun motion(time: Long) = RobotAction.DriveHardwareUpdate(
        1.0, 0.0, 0.0, 1.0, 0.0, 0.0, time
    )

    @Test fun `failed public reduction cannot leak hidden odometry into later dispatch`() {
        val failure = IllegalArgumentException("public reducer failed")
        var fail = true
        val store = Store(reducer = { state, action ->
            if (fail && action is RobotAction.DriveHardwareUpdate) throw failure
            rootReducer(state, action)
        })
        val initial = store.state
        assertSame(failure, assertFailsWith<IllegalArgumentException> { store.dispatch(motion(100L)) })
        assertSame(initial, store.state)
        fail = false
        val rejected = assertFailsWith<IllegalStateException> { store.dispatch(motion(120L)) }
        assertSame(failure, rejected.cause)
        assertSame(initial, store.state)
    }

    @Test fun `failed derived reduction rejects both dispatch entry points before action observers`() {
        val failure = AssertionError("derived reducer failed")
        var fail = true
        val store = Store(reducer = { state, action ->
            if (fail && action is ApplyPoseEstimatorRuntimeResult) throw failure
            rootReducer(state, action)
        })
        var observed = 0
        store.actionListener = { observed++ }
        val initial = store.state
        assertSame(failure, assertFailsWith<AssertionError> { store.dispatch(motion(100L)) })
        fail = false
        assertSame(failure, assertFailsWith<IllegalStateException> {
            store.dispatchAll(RobotAction.SetAlliance(Alliance.RED, 120L))
        }.cause)
        assertFailsWith<IllegalStateException> { store.dispatchAll() }
        assertEquals(1, observed)
        assertSame(initial, store.state)
    }

    @Test fun `action observer failure precedes estimator work and does not poison the store`() {
        val store = Store()
        val initial = store.state
        val failure = IllegalStateException("logging observer failed")
        store.actionListener = { throw failure }
        assertSame(failure, assertFailsWith<IllegalStateException> { store.dispatch(motion(100L)) })
        assertSame(initial, store.state)
        store.actionListener = null
        store.dispatch(motion(120L))
        assertEquals(1.0, store.state.drive.poseEstimator.estimatedPoseX, 1e-9)
    }

    @Test fun `subscriber failure preserves the commit and existing fail fast order`() {
        val store = Store()
        val failure = IllegalStateException("subscriber failed")
        val seen = mutableListOf<Long>()
        val remove = store.subscribe { throw failure }
        store.subscribe { seen.add(it.timestampMs) }
        assertSame(failure, assertFailsWith<IllegalStateException> { store.dispatch(motion(100L)) })
        assertEquals(1.0, store.state.drive.poseEstimator.estimatedPoseX, 1e-9)
        assertTrue(seen.isEmpty())
        remove()
        store.dispatch(motion(120L))
        assertEquals(listOf(120L), seen)
        assertEquals(2.0, store.state.drive.poseEstimator.estimatedPoseX, 1e-9)
    }

    @Test fun `failed batch retains its committed prefix but cannot reuse a failed estimator`() {
        val failure = IllegalArgumentException("second action failed")
        var fail = true
        val store = Store(reducer = { state, action ->
            if (fail && action.timestampMs == 120L) throw failure
            rootReducer(state, action)
        })
        val seen = mutableListOf<Long>()
        store.subscribe { seen.add(it.timestampMs) }
        assertSame(failure, assertFailsWith<IllegalArgumentException> {
            store.dispatchAll(motion(100L), motion(120L), motion(140L))
        })
        val prefix = store.state
        assertEquals(100L, prefix.timestampMs)
        assertEquals(1.0, prefix.drive.poseEstimator.estimatedPoseX, 1e-9)
        assertTrue(seen.isEmpty())
        fail = false
        assertSame(failure, assertFailsWith<IllegalStateException> { store.dispatch(motion(160L)) }.cause)
        assertSame(prefix, store.state)
    }

    @Test fun `successful batch observes every action and notifies once including an empty batch`() {
        val store = Store()
        val observed = mutableListOf<Long>()
        val seen = mutableListOf<Long>()
        store.actionListener = { observed.add(it.timestampMs) }
        store.subscribe { seen.add(it.timestampMs) }
        store.dispatchAll(motion(100L), motion(120L))
        assertEquals(listOf(100L, 120L), observed)
        assertEquals(listOf(120L), seen)
        assertEquals(2.0, store.state.drive.poseEstimator.estimatedPoseX, 1e-9)
        store.dispatchAll()
        assertEquals(listOf(100L, 120L), observed)
        assertEquals(listOf(120L, 120L), seen)
    }
}
