package com.areslib.state

import com.areslib.Store
import com.areslib.action.RobotAction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StoreSubscriberLifecycleTest {
    @Test fun `both dispatch paths notify a stable snapshot during unsubscription`() {
        for (batch in listOf(false, true)) {
            for (removeSelf in listOf(false, true)) {
                val store = Store()
                val observed = mutableListOf<String>()
                lateinit var remove: () -> Unit
                val first = store.subscribe { observed += "first"; remove() }
                val second = store.subscribe { observed += "second" }
                remove = if (removeSelf) first else second
                fun dispatch() {
                    val action = RobotAction.SetAlliance(Alliance.RED)
                    if (batch) store.dispatchAll(action) else store.dispatch(action)
                }
                dispatch()
                assertEquals(listOf("first", "second"), observed)
                observed.clear()
                dispatch()
                assertEquals(listOf(if (removeSelf) "second" else "first"), observed)
            }
        }
    }

    @Test fun `concurrent removal cannot shorten an in-flight notification`() {
        for (batch in listOf(false, true)) {
            val store = Store()
            val entered = CountDownLatch(1)
            val removed = CountDownLatch(1)
            var calls = 0
            store.subscribe { entered.countDown(); assertTrue(removed.await(2, TimeUnit.SECONDS)) }
            val unsubscribe = store.subscribe { calls++ }
            val remover = thread { assertTrue(entered.await(2, TimeUnit.SECONDS)); unsubscribe(); removed.countDown() }
            try {
                val action = RobotAction.SetAlliance(Alliance.RED)
                if (batch) store.dispatchAll(action) else store.dispatch(action)
                assertEquals(1, calls)
            } finally { remover.join(2000) }
        }
    }

    @Test fun `duplicate callback registrations have independent idempotent subscriptions`() {
        val store = Store()
        var calls = 0
        val listener: (RobotState) -> Unit = { calls++ }
        val remove = store.subscribe(listener)
        store.subscribe(listener)
        remove(); remove()
        store.dispatchAll()
        assertEquals(1, calls)
    }
}
