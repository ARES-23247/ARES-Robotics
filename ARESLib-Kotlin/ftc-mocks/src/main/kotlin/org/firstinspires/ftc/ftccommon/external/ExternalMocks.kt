@file:Suppress("UNUSED_PARAMETER")
package org.firstinspires.ftc.ftccommon.external

/**
 * Marker annotation retained for FTC event-loop creation hooks in desktop simulation.
 *
 * Annotates methods on desktop OpModes and control extensions that register hooks
 * with the simulated FTC event loop during robot lifecycle startup.
 *
 * This allows simulation harnesses to detect custom lifecycle callbacks when running
 * in headless desktop mode without requiring Android platform classes.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class OnCreateEventLoop

/**
 * Marker annotation retained so FTC web-handler registration code compiles on desktop.
 *
 * Used by web-handler registration mechanisms in robot controller extensions to attach
 * HTTP/WebSocket routes without requiring live Android web server dependencies.
 *
 * Handlers registered under this annotation are safely no-oped in desktop test environments
 * where no physical HTTP server daemon is listening.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class WebHandlerRegistrar
