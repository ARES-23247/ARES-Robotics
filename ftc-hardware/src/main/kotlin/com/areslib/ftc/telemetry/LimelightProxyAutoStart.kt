package com.areslib.ftc.telemetry

import android.content.Context
import org.firstinspires.ftc.ftccommon.external.WebHandlerRegistrar
import com.qualcomm.robotcore.util.WebHandlerManager

/**
 * Automatic initializer for [LimelightProxy] HTTP stream tunneling.
 *
 * Annotates [registerWebHandlers] with `@WebHandlerRegistrar` to hook into the FTC Control Hub web server startup lifecycle.
 * Spawns port forwarding tunnels allowing desktop browsers and ARES-Analytics to view Limelight camera streams via the Control Hub IP (`192.168.43.1:5800`).
 *
 * @see LimelightProxy
 */
object LimelightProxyAutoStart {
    private var proxy: LimelightProxy? = null

    @WebHandlerRegistrar
    @JvmStatic
    /**
     * Called automatically by FTC SDK web server initialization to register proxy tunnels.
     *
     * @param context Application context instance.
     * @param manager Web handler manager interface.
     */
    fun registerWebHandlers(@Suppress("UNUSED_PARAMETER") context: Context, @Suppress("UNUSED_PARAMETER") manager: WebHandlerManager) {
        start()
        System.out.println("LimelightProxyAutoStart: Automatically registered web handlers.")
    }

    /**
     * Starts active Limelight HTTP proxy stream tunnels if not already running.
     */
    @Synchronized
    fun start() {
        if (proxy == null) {
            val p = LimelightProxy()
            proxy = p
            p.start()
            System.out.println("LimelightProxyAutoStart: Started Limelight Proxy tunnels.")
        }
    }

    /**
     * Terminates active Limelight HTTP proxy stream tunnels.
     */
    @Synchronized
    fun stop() {
        proxy?.stop()
        proxy = null
        System.out.println("LimelightProxyAutoStart: Stopped Limelight Proxy tunnels.")
    }
}
