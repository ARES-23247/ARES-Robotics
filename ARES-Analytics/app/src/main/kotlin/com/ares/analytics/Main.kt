package com.ares.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.ares.analytics.desktop.AwtDesktopWindowPort
import com.ares.analytics.desktop.DesktopCrashHandler
import com.ares.analytics.desktop.DesktopInstanceLock
import com.ares.analytics.desktop.DesktopShutdownCoordinator
import com.ares.analytics.desktop.DesktopStartupMachine
import com.ares.analytics.desktop.DesktopWindowPresentationController
import com.ares.analytics.desktop.DesktopWindowCreationWatchdog
import com.ares.analytics.di.ServiceRegistry
import com.ares.analytics.ui.screens.MainScreen
import com.ares.analytics.ui.theme.AresTheme
import com.ares.analytics.ui.theme.rememberAresAppIconPainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

/**
 * Composition root for the single-instance Compose desktop application.
 *
 * Lifecycle concerns live in `com.ares.analytics.desktop`: the instance lock, crash policy,
 * shutdown coordination (bounded disposal + hard-exit watchdog), the explicit startup state
 * machine, and the window presentation controller (native verification observation-only;
 * Compose owns visibility and always-on-top state). This file only wires them together.
 */
fun main(args: Array<String>) {
    runPackagedProjectValidationCommand(args)?.let { exitCode ->
        if (exitCode != 0) exitProcess(exitCode)
        return
    }

    launchDesktopApplication()
}

private fun launchDesktopApplication() {
    // Disable Java Assistive Technology check to prevent crash on Windows systems with screen readers active
    System.setProperty("javax.accessibility.assistive_technologies", "")

    val instanceLock = DesktopInstanceLock.tryAcquire()
    if (instanceLock == null) {
        System.err.println("[ARES-Analytics] App is already running (failed to acquire app.lock). Exiting.")
        java.lang.System.exit(0)
        return
    }
    Runtime.getRuntime().addShutdownHook(Thread { instanceLock.close() })

    DesktopCrashHandler.install {
        // A crash on the AWT event thread leaves no usable window; a windowless JVM must not
        // keep the single-instance lock alive for the next launch.
        exitProcess(1)
    }

    application {
        val windowState = rememberWindowState(
            placement = WindowPlacement.Floating,
            position = WindowPosition.Aligned(Alignment.Center),
            size = DpSize(1440.dp, 900.dp),
        )
        val services = remember { ServiceRegistry() }
        val shutdownScope = rememberCoroutineScope()
        val startupMachine = remember { DesktopStartupMachine() }
        val shutdownCoordinator = remember { DesktopShutdownCoordinator(startupMachine) }
        val creationWatchdog = remember {
            DesktopWindowCreationWatchdog(
                machine = startupMachine,
                onUnrecoverableWindow = DesktopShutdownCoordinator::terminateForUnusableWindow,
            )
        }
        var startupAlwaysOnTop by remember { mutableStateOf(true) }
        var bootstrapAttempt by remember { mutableStateOf(0) }
        var bootstrapState by remember { mutableStateOf<DesktopServiceBootstrapState>(DesktopServiceBootstrapState.Loading) }

        LaunchedEffect(services, bootstrapAttempt) {
            bootstrapState = DesktopServiceBootstrapState.Loading
            bootstrapState = runCatching {
                runDesktopServiceBootstrap(services::prepareForMainScreen)
            }.fold(
                onSuccess = { DesktopServiceBootstrapState.Ready },
                onFailure = { failure ->
                    DesktopServiceBootstrapState.Failed(
                        failure.message?.takeIf(String::isNotBlank)
                            ?: failure::class.simpleName
                            ?: "Unknown database startup error"
                    )
                },
            )
        }

        // This effect belongs to the application composition rather than Window content. It
        // therefore starts even if Compose never creates the native peer/content composition.
        DisposableEffect(creationWatchdog) {
            creationWatchdog.start()
            onDispose { creationWatchdog.stop() }
        }

        Window(
            onCloseRequest = {
                shutdownCoordinator.requestShutdown(
                    scope = shutdownScope,
                    services = services,
                    exitApplication = ::exitApplication,
                )
            },
            title = "${BuildConfig.PRODUCT_NAME} — Mission Control",
            icon = rememberAresAppIconPainter(),
            state = windowState,
            // Windows may deny a foreground request from a Gradle-launched child process. Let
            // Compose create the window topmost so it is visible even when activation is denied,
            // then release topmost status shortly after the native peer has opened.
            alwaysOnTop = startupAlwaysOnTop,
            visible = true,
        ) {
            DisposableEffect(window) {
                window.minimumSize = java.awt.Dimension(1100, 700)
                val presentationController = DesktopWindowPresentationController(
                    windowPort = AwtDesktopWindowPort(window),
                    machine = startupMachine,
                    isShutdownStarted = shutdownCoordinator::isShutdownStarted,
                    onStartupAlwaysOnTopChange = { startupAlwaysOnTop = it },
                    onFocusLost = services.keyboardDriveState::releaseAll,
                    onUnrecoverableWindowLoss = DesktopShutdownCoordinator::terminateForUnusableWindow,
                )
                presentationController.attach()

                onDispose {
                    presentationController.detach(expectedShutdown = shutdownCoordinator.isShutdownStarted)
                }
            }
            AresTheme {
                when (val state = bootstrapState) {
                    DesktopServiceBootstrapState.Loading -> DesktopServiceLoadingScreen()
                    DesktopServiceBootstrapState.Ready -> MainScreen(services = services)
                    is DesktopServiceBootstrapState.Failed -> DesktopServiceFailureScreen(
                        message = state.message,
                        onRetry = { bootstrapAttempt += 1 },
                    )
                }
            }
        }
    }
}

internal suspend fun runDesktopServiceBootstrap(initializer: () -> Unit) {
    withContext(Dispatchers.IO) {
        initializer()
    }
}

private sealed interface DesktopServiceBootstrapState {
    data object Loading : DesktopServiceBootstrapState
    data object Ready : DesktopServiceBootstrapState
    data class Failed(val message: String) : DesktopServiceBootstrapState
}

@Composable
private fun DesktopServiceLoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = "Opening your analytics database…",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "Your telemetry stays on this computer. Large databases can take a little longer after an upgrade.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun DesktopServiceFailureScreen(
    message: String,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "ARES could not open the analytics database",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Your telemetry files were not deleted or replaced.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onRetry) {
                Text("Try again")
            }
        }
    }
}
