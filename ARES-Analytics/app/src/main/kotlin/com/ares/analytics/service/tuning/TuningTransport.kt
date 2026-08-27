package com.ares.analytics.service.tuning

import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningTopics

/** Topic construction for schema-v3's declaration-UID transport. Keys are metadata, not topics. */
object TuningTransport {
    fun parameterRoot(declaration: TuningParameterDeclaration): String =
        "${TuningTopics.ROOT}/Parameters/${declaration.uid}"

    fun current(declaration: TuningParameterDeclaration): String = "${parameterRoot(declaration)}/Current"
    fun consumerSupported(declaration: TuningParameterDeclaration): String = "${parameterRoot(declaration)}/ConsumerSupported"
    fun requested(declaration: TuningParameterDeclaration): String = "${parameterRoot(declaration)}/Requested"
    fun requestNonce(declaration: TuningParameterDeclaration): String = "${parameterRoot(declaration)}/RequestNonce"
    fun processedNonce(declaration: TuningParameterDeclaration): String = "${parameterRoot(declaration)}/ProcessedNonce"
    fun lastResult(declaration: TuningParameterDeclaration): String = "${parameterRoot(declaration)}/LastResult"
}
