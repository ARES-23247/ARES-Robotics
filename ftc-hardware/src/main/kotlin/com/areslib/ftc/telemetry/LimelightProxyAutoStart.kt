package com.areslib.ftc.telemetry

import com.areslib.telemetry.RobotStatusTracker

/**
 * Project-policy-owned lifecycle for [LimelightProxy] HTTP stream tunneling.
 *
 * Spawns port forwarding tunnels allowing desktop browsers and ARES-Analytics to view Limelight camera streams via the Control Hub IP (`192.168.43.1:5800`).
 *
 * @see LimelightProxy
 */
object LimelightProxyAutoStart {
    private var proxy: LimelightProxy? = null

    /** Whether the bounded proxy currently owns its listener sockets. */
    val isActive: Boolean
        @Synchronized get() = proxy != null

    /**
     * Starts active Limelight HTTP proxy stream tunnels if not already running.
     */
    @Synchronized
    fun start() {
        if (proxy == null) {
            val p = LimelightProxy()
            try {
                p.start()
                proxy = p
                RobotStatusTracker.ftcLimelightProxyActive = true
                System.out.println("LimelightProxyAutoStart: Started Limelight Proxy tunnels by project policy.")
            } catch (failure: Throwable) {
                runCatching { p.stop() }
                RobotStatusTracker.ftcLimelightProxyActive = false
                throw failure
            }
        }
    }

    /**
     * Terminates active Limelight HTTP proxy stream tunnels.
     */
    @Synchronized
    fun stop() {
        proxy?.stop()
        proxy = null
        RobotStatusTracker.ftcLimelightProxyActive = false
        System.out.println("LimelightProxyAutoStart: Stopped Limelight Proxy tunnels.")
    }
}
