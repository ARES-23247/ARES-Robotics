package com.ares.analytics.viewmodel

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class DashboardLayoutTransactionQueueTest {
    @Test
    fun `concurrent layout operations observe the prior committed state`() = runBlocking {
        val transactions = DashboardLayoutTransactionQueue()
        val commits = mutableListOf<Int>()

        val first = async {
            transactions.transact {
                val next = (commits.lastOrNull() ?: 0) + 1
                delay(40)
                commits += next
            }
        }
        delay(5)
        val second = async {
            transactions.transact {
                commits += (commits.lastOrNull() ?: 0) + 1
            }
        }

        first.await()
        second.await()

        assertEquals(listOf(1, 2), commits)
    }
}
