package com.ares.analytics.service

import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharedFlow

/** Target identity is captured when a frame is indexed, before any collector can queue it. */
internal data class TelemetryPublication(val frame: TelemetryFrame, val targetEpoch: Long)

/** A view of the same buffered bus: no second queue or forwarding coroutine. */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
internal class TelemetryFrameFlow(private val publications: SharedFlow<TelemetryPublication>) : SharedFlow<TelemetryFrame> {
    override val replayCache: List<TelemetryFrame> get() = publications.replayCache.map { it.frame }
    override suspend fun collect(collector: FlowCollector<TelemetryFrame>): Nothing =
        publications.collect(object : FlowCollector<TelemetryPublication> {
            override suspend fun emit(value: TelemetryPublication) { collector.emit(value.frame) }
        })
}
