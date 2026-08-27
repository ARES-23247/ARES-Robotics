# ARES-Analytics Compose Desktop Application Audit Report

## 1. Strengths
* **NT4 Key Normalization:** `Nt4ClientService` correctly enforces the NT4 key normalization requirement by actively stripping leading slashes (`.removePrefix("/")`), preventing duplicate topic registration on the `ntcore` server.
* **ViewModel MVI Structuring:** ViewModels (e.g., `DashboardViewModel.kt`) successfully implement the core MVI foundations, utilizing sealed `Intent` classes and exposing immutable `StateFlow<State>` data classes for UI consumption.
* **Offline-First Desktop Pull Architecture:** `CloudViewModel.kt` accurately fetches logs directly from the robot via the `LogManagerServer` on the local network (port 5002 endpoints: `/api/logs`, `/api/download`, `/api/delete`), properly bypassing cloud gateways for raw log transfers as required by the spec.

## 2. Findings

### 2.1 Imperative UI Mutations Violating MVI
While the ViewModels expose declarative `StateFlow`, the Compose UI layer actively bypasses the Model-View-Intent pattern by locally maintaining imperative, mutable state.
* **Violation:** Extensive use of `var <property> by remember { mutableStateOf(...) }` inside composables instead of lifting state to the ViewModel.
* **Impacted Files:** Found across numerous UI components, including `FieldViewerCard.kt` (`robotX`, `robotY`), `CameraStreamCard.kt` (`streamUrl`, `isConnected`), `DataTablePanel.kt` (`telemetryRows`, `isLoading`), `ConsoleViewer.kt`, and `FtcDriverStationWidget.kt`.
* **Architectural Conflict:** The specification demands that "Compose UI observes StateFlow via collectAsState() — purely declarative, no imperative UI mutations." This local state tracking fragments the single source of truth.

### 2.2 Network Side Effects Bypassing `Dispatchers.IO`
The architecture explicitly dictates that all side effects (DB writes, network calls) must strictly execute in `viewModelScope` on `Dispatchers.IO`. 
* **Violation:** In `CloudViewModel.kt`, Ktor `httpClient` requests to the `LogManagerServer` are fired directly within the default coroutine scope instead of explicitly switching context.
* **Identified Instances:**
  1. `UploadRobotRun` intent: `httpClient.get("http://${getRobotIp()}:5002/api/download?file=${file.name}")`
  2. `DeleteRobotRun` intent: `httpClient.post("http://${getRobotIp()}:5002/api/delete")`
  3. `fetchRobotLogs()` function: `httpClient.get("http://${getRobotIp()}:5002/api/logs")`

### 2.3 NT4 Key Normalization & LogManagerServer Verification
* **NT4 Normalization:** Compliant. The normalization logic exists in `Nt4ClientService.dispatchValue()`.
* **LogManagerServer Integration:** Structurally compliant (utilizing correct endpoints), but implementation fails the concurrency constraints mentioned in 2.2.

## 3. Prioritized Roadmap to Compliance

1. **[P0] Eradicate Imperative UI State:** Refactor all `ui/components/` files to remove `mutableStateOf`. Lift all local UI properties (such as dropdown expansions, search texts, and intermediate data rows) into their respective ViewModel's `ScreenState` data classes. Route all UI mutations through dispatched Intents.
2. **[P1] Context-Switch Network I/O:** Update `CloudViewModel.kt` to explicitly wrap all `LogManagerServer` REST calls inside `withContext(Dispatchers.IO) { ... }` blocks, matching the pattern successfully employed for database operations.
3. **[P2] Automate Architectural Enforcement:** Integrate static analysis rules (such as Konsist or custom detekt rules) into the `build.gradle.kts` pipeline to automatically fail the build if `mutableStateOf` is used inside the UI layer, preventing future regressions.
