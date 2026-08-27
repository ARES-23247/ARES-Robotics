package com.ares.analytics.viewmodel.field

/** Mutable pan/zoom gesture state kept outside the larger field-view state flow. */
class FieldCameraGestureController {
    var zoomLevel: Float = 1.0f
    var panOffsetX: Float = 0.0f
    var panOffsetY: Float = 0.0f

    fun reset() {
        zoomLevel = 1.0f
        panOffsetX = 0.0f
        panOffsetY = 0.0f
    }
}
