const fs = require("fs");
const path = require("path");
const http = require("http");
const https = require("https");
const { execSync, spawn } = require("child_process");
const readline = require("readline");

// Prepend JDK 17 bin to PATH and set JAVA_HOME on Windows if available
if (process.platform === "win32") {
  const jdkPath = "C:\\Program Files\\Java\\jdk-17";
  if (fs.existsSync(jdkPath)) {
    process.env.JAVA_HOME = jdkPath;
    process.env.PATH = path.join(jdkPath, "bin") + path.delimiter + process.env.PATH;
  }
}

// 1. Auto-installation of dependencies if missing
const daemonDir = __dirname;
const nodeModulesDir = path.join(daemonDir, "node_modules");

if (!fs.existsSync(nodeModulesDir)) {
  console.log("[Daemon] node_modules not found. Executing automatic dependencies installation...");
  try {
    execSync("npm install", { cwd: daemonDir, stdio: "inherit" });
    console.log("[Daemon] Dependencies installed successfully.");
  } catch (err) {
    console.error("[Daemon] Failed to install dependencies. Make sure npm is installed and on your PATH:", err);
    process.exit(1);
  }
}

// Now we can safely import ws
const WebSocket = require("ws");

// Constants & Configurations
const PORT = process.env.PORT || 8080;
const PORT_SECURE = process.env.PORT_SECURE || 8443;
const REPO_ROOT = path.resolve(daemonDir, "../..");
const ALLOWED_ORIGINS = [
  "https://aresfirst-portal.web.app",
  "https://aresfirst.org",
  "https://www.aresfirst.org",
  "http://localhost:3000",
  "http://localhost:5173",
  "http://localhost:8080"
];

// Active state
let activeProcess = null;

// 2. Perform Environment Diagnostics
function checkEnvironment() {
  const diagnostics = {
    jdkValid: false,
    jdkVersion: "Unknown",
    gradlewExists: false,
  };

  // Check JDK
  try {
    let javaVersionOutput = "";
    try {
      javaVersionOutput = execSync("java -version", { stdio: "pipe" }).toString();
    } catch (err) {
      javaVersionOutput = (err.stdout ? err.stdout.toString() : "") + (err.stderr ? err.stderr.toString() : "");
    }
    
    if (!javaVersionOutput.trim()) {
      try {
        javaVersionOutput = execSync("java -version 2>&1", { stdio: "pipe" }).toString();
      } catch (e) {
        javaVersionOutput = e.toString();
      }
    }

    const versionMatch = javaVersionOutput.match(/(?:version\s*\"|openjdk\s*version\s*\"|openjdk\s*)([0-9\._a-zA-Z\-]+)/i);
    if (versionMatch) {
      diagnostics.jdkVersion = versionMatch[1];
      const cleanVer = versionMatch[1].replace(/[^0-9\.]/g, "");
      const major = parseInt(cleanVer.split(".")[0]);
      const realMajor = major === 1 ? parseInt(cleanVer.split(".")[1]) : major;
      diagnostics.jdkValid = realMajor >= 17;
    } else {
      const fallbackMatch = javaVersionOutput.match(/(\d+\.\d+\.\d+|\d+)/);
      if (fallbackMatch) {
        diagnostics.jdkVersion = fallbackMatch[1];
        const major = parseInt(fallbackMatch[1].split(".")[0]);
        const realMajor = major === 1 ? parseInt(fallbackMatch[1].split(".")[1]) : major;
        diagnostics.jdkValid = realMajor >= 17;
      }
    }
  } catch (err) {
    diagnostics.jdkVersion = "Not Installed / Not on PATH";
  }

  // Check Gradle Wrapper
  const gradlewName = process.platform === "win32" ? "gradlew.bat" : "gradlew";
  diagnostics.gradlewExists = fs.existsSync(path.join(REPO_ROOT, gradlewName));

  return diagnostics;
}

const env = checkEnvironment();
console.log("\n=== ARES Sim Launcher Daemon Diagnostics ===");
console.log(`Repository Root: ${REPO_ROOT}`);
console.log(`JDK Version:     ${env.jdkVersion} ${env.jdkValid ? "✅ (>= 17)" : "❌ (Requires JDK 17+)"}`);
console.log(`Gradle Wrapper:  ${env.gradlewExists ? "✅ Found" : "❌ Missing"}`);
console.log("============================================\n");

// 3. Initialize HTTP & HTTPS Servers for WS and WSS support
const sslDir = path.join(daemonDir, "ssl");
const certPath = path.join(sslDir, "cert.pem");
const keyPath = path.join(sslDir, "key.pem");
const hasSSL = fs.existsSync(certPath) && fs.existsSync(keyPath);

// Setup verifyClient for CORS / origin check
const wsVerifyClient = (info) => {
  const origin = info.origin;
  const isAllowed = ALLOWED_ORIGINS.some((allowed) => {
    if (allowed.includes("*")) {
      return origin.startsWith(allowed.replace("*", ""));
    }
    return origin === allowed;
  });

  if (!isAllowed) {
    console.warn(`[Daemon] Rejected connection from unauthorized origin: ${origin}`);
  }
  return isAllowed;
};

// Create unsecure HTTP / WS server (always running on port 8080)
const httpServer = http.createServer((req, res) => {
  res.writeHead(200);
  res.end("ARES Sim Launcher Daemon running (WS).\n");
});

const wssUnsecure = new WebSocket.Server({
  server: httpServer,
  verifyClient: wsVerifyClient
});

// Create secure HTTPS / WSS server (optional, runs on port 8443 if SSL certs exist)
let httpsServer = null;
let wssSecure = null;
let httpsProxyServer = null;
let wssProxy = null;

if (hasSSL) {
  console.log(`[Daemon] SSL Certificates found. Starting secure server (WSS) on port ${PORT_SECURE}...`);
  const options = {
    cert: fs.readFileSync(certPath),
    key: fs.readFileSync(keyPath)
  };
  httpsServer = https.createServer(options, (req, res) => {
    res.writeHead(200);
    res.end("ARES Sim Launcher Daemon running securely (WSS).\n");
  });
  wssSecure = new WebSocket.Server({
    server: httpsServer,
    verifyClient: wsVerifyClient
  });

  // Start WSS to WS proxy on port 5811 for secure NT4 support
  console.log(`[Daemon] Starting secure NT4 WSS proxy on port 5811...`);
  httpsProxyServer = https.createServer(options, (req, res) => {
    res.writeHead(200);
    res.end("ARES NT4 WSS Proxy running.\n");
  });
  wssProxy = new WebSocket.Server({
    server: httpsProxyServer,
    verifyClient: wsVerifyClient
  });

  wssProxy.on("connection", (clientWs, req) => {
    let targetHost = "127.0.0.1";
    try {
      const url = new URL(req.url || "", "http://localhost");
      const hostParam = url.searchParams.get("host");
      if (hostParam) {
        targetHost = hostParam;
      }
    } catch (e) {}

    console.log(`[Proxy] Browser client connected. Proxying to ws://${targetHost}:5810`);
    const targetUrl = `ws://${targetHost}:5810/nt/v4/websocket`;
    const simWs = new WebSocket(targetUrl);
    const messageQueue = [];

    simWs.on("open", () => {
      console.log("[Proxy] Simulator connection opened. Flushing message queue...");
      while (messageQueue.length > 0) {
        const msg = messageQueue.shift();
        simWs.send(msg.data, { binary: msg.isBinary });
      }
    });

    simWs.on("message", (data, isBinary) => {
      if (clientWs.readyState === WebSocket.OPEN) {
        clientWs.send(data, { binary: isBinary });
      }
    });

    clientWs.on("message", (data, isBinary) => {
      if (simWs.readyState === WebSocket.OPEN) {
        simWs.send(data, { binary: isBinary });
      } else {
        messageQueue.push({ data, isBinary });
      }
    });

    simWs.on("close", () => {
      console.log("[Proxy] Simulator connection closed.");
      clientWs.close();
    });

    clientWs.on("close", () => {
      console.log("[Proxy] Browser client connection closed.");
      simWs.close();
    });

    simWs.on("error", (err) => {
      console.error("[Proxy] Simulator connection error:", err);
      clientWs.close();
    });

    clientWs.on("error", (err) => {
      console.error("[Proxy] Browser client connection error:", err);
      simWs.close();
    });
  });
} else {
  console.log(`[Daemon] SSL Certificates not found at ${certPath}. Secure server (WSS) disabled.`);
  console.log(`[Daemon] Run 'node generate-certs.js' to generate self-signed certificates and enable WSS.`);
}

// Handler for incoming connections
const onConnection = (ws) => {
  console.log("[Daemon] Client connected.");

  // Send initial status check info
  ws.send(JSON.stringify({
    type: "status",
    status: activeProcess ? "running" : "idle",
    diagnostics: env
  }));

  ws.on("message", (messageStr) => {
    try {
      const msg = JSON.parse(messageStr);

      if (msg.type === "status") {
        ws.send(JSON.stringify({
          type: "status",
          status: activeProcess ? "running" : "idle",
          diagnostics: checkEnvironment()
        }));
      }

      else if (msg.type === "start") {
        if (activeProcess) {
          ws.send(JSON.stringify({ type: "log", line: "[Daemon Error] Simulator is already running." }));
          return;
        }

        const buildEnv = checkEnvironment();
        if (!buildEnv.jdkValid) {
          ws.send(JSON.stringify({ type: "log", line: `[Daemon Error] Invalid JDK version (${buildEnv.jdkVersion}). JDK 17+ is required.` }));
          ws.send(JSON.stringify({ type: "exit", code: 1, success: false }));
          return;
        }
        if (!buildEnv.gradlewExists) {
          ws.send(JSON.stringify({ type: "log", line: "[Daemon Error] Gradle wrapper not found in repository root." }));
          ws.send(JSON.stringify({ type: "exit", code: 1, success: false }));
          return;
        }

        // Handle EKF overrides config if provided
        const configOverridePath = path.join(REPO_ROOT, "config_override.json");
        const configOverrideSimPath = path.join(REPO_ROOT, "simulator", "config_override.json");
        if (msg.params) {
          try {
            const configStr = JSON.stringify(msg.params, null, 2);
            fs.writeFileSync(configOverridePath, configStr);
            try {
              fs.writeFileSync(configOverrideSimPath, configStr);
            } catch (simErr) {
              console.error("[Daemon Warning] Failed to write simulator config_override.json:", simErr.message);
            }
            const logParams = { ...msg.params };
            if (logParams.obstacles) {
              logParams.obstacles = `[${logParams.obstacles.length} obstacles]`;
            }
            if (logParams.elements) {
              logParams.elements = `[${logParams.elements.length} elements]`;
            }
            if (logParams.elementTypes) {
              logParams.elementTypes = `[${logParams.elementTypes.length} element types]`;
            }
            ws.send(JSON.stringify({ type: "log", line: `[Daemon] Wrote config overrides: ${JSON.stringify(logParams)}` }));
          } catch (e) {
            ws.send(JSON.stringify({ type: "log", line: `[Daemon Warning] Failed to write config_override.json: ${e.message}` }));
          }
        } else {
          // Clean up any stale override files
          if (fs.existsSync(configOverridePath)) {
            try { fs.unlinkSync(configOverridePath); } catch (e) {}
          }
          if (fs.existsSync(configOverrideSimPath)) {
            try { fs.unlinkSync(configOverrideSimPath); } catch (e) {}
          }
        }

        ws.send(JSON.stringify({ type: "log", line: "[Daemon] Spawning Gradle simulator task..." }));

        const gradlewCmd = process.platform === "win32" ? "gradlew.bat" : "./gradlew";
        const args = [":simulator:run"];
        if (msg.params && msg.params.configId) {
          args.push(`--args=--field-config ${msg.params.configId}`);
        }

        try {
          activeProcess = spawn(gradlewCmd, args, {
            cwd: REPO_ROOT,
            shell: true,
            env: { ...process.env, JAVA_OPTS: "-XX:+UseG1GC" }
          });

          let isStarted = false;
          // Line-by-line stdout parsing
          const stdoutRl = readline.createInterface({ input: activeProcess.stdout });
          stdoutRl.on("line", (line) => {
            console.log(`[SIM-OUT] ${line}`);
            if (!isStarted && (
              line.includes("Simulation Running") ||
              line.includes("NT: Listening") ||
              line.includes("FLOODGATE") ||
              line.includes("SUPERSTRUCTURE")
            )) {
              isStarted = true;
              console.log("[Daemon] Simulator is running. Notifying client...");
              if (ws.readyState === WebSocket.OPEN) {
                ws.send(JSON.stringify({ type: "status", status: "running" }));
              }
            }

            if (ws.readyState === WebSocket.OPEN) {
              ws.send(JSON.stringify({ type: "log", line }));
            }
          });

          // Line-by-line stderr parsing
          const stderrRl = readline.createInterface({ input: activeProcess.stderr });
          stderrRl.on("line", (line) => {
            console.error(`[SIM-ERR] ${line}`);
            if (ws.readyState === WebSocket.OPEN) {
              ws.send(JSON.stringify({ type: "log", line: `[ERROR] ${line}` }));
            }
          });

          activeProcess.on("exit", (code) => {
            console.log(`[Daemon] Simulator exited with code ${code}`);
            activeProcess = null;
            // Clean up config_override.json files
            const configOverridePath = path.join(REPO_ROOT, "config_override.json");
            const configOverrideSimPath = path.join(REPO_ROOT, "simulator", "config_override.json");
            if (fs.existsSync(configOverridePath)) {
              try { fs.unlinkSync(configOverridePath); } catch (e) {}
            }
            if (fs.existsSync(configOverrideSimPath)) {
              try { fs.unlinkSync(configOverrideSimPath); } catch (e) {}
            }
            if (ws.readyState === WebSocket.OPEN) {
              ws.send(JSON.stringify({
                type: "exit",
                code: code ?? 0,
                success: code === 0
              }));
            }
          });

          activeProcess.on("error", (err) => {
            console.error("[Daemon] Failed to start subprocess:", err);
            activeProcess = null;
            if (ws.readyState === WebSocket.OPEN) {
              ws.send(JSON.stringify({ type: "log", line: `[Daemon Subprocess Error] ${err.message}` }));
              ws.send(JSON.stringify({ type: "exit", code: 1, success: false }));
            }
          });

        } catch (spawnErr) {
          console.error("[Daemon] Spawning error:", spawnErr);
          activeProcess = null;
          ws.send(JSON.stringify({ type: "log", line: `[Daemon Spawn Error] ${spawnErr.message}` }));
          ws.send(JSON.stringify({ type: "exit", code: 1, success: false }));
        }
      }

      else if (msg.type === "stop") {
        if (!activeProcess) {
          ws.send(JSON.stringify({ type: "log", line: "[Daemon] Simulator is not running." }));
          return;
        }

        console.log("[Daemon] Force-stopping simulator process tree...");
        ws.send(JSON.stringify({ type: "log", line: "[Daemon] Sending termination signal to process tree..." }));

        // Gracefully kill process tree
        if (process.platform === "win32") {
          // Use taskkill to kill the spawned process and any of its children recursively (/T)
          try {
            execSync(`taskkill /pid ${activeProcess.pid} /f /t`);
          } catch (e) {
            // If taskkill fails, fallback to standard kill
            activeProcess.kill();
          }
        } else {
          // Send SIGTERM to process group
          activeProcess.kill("SIGTERM");
        }
      }
    } catch (parseErr) {
      console.error("[Daemon] Failed to process incoming WS frame:", parseErr);
    }
  });

  ws.on("close", () => {
    console.log("[Daemon] Client disconnected.");
    // Clean up config_override.json
    const configOverridePath = path.join(REPO_ROOT, "config_override.json");
    if (fs.existsSync(configOverridePath)) {
      try { fs.unlinkSync(configOverridePath); } catch (e) {}
    }
    // Safety check: Kill simulator if connection drops to prevent headless memory leaks
    if (activeProcess) {
      console.log("[Daemon] WebSocket closed. Terminating active simulator process...");
      if (process.platform === "win32") {
        try { execSync(`taskkill /pid ${activeProcess.pid} /f /t`); } catch (e) { activeProcess.kill(); }
      } else {
        activeProcess.kill("SIGTERM");
      }
      activeProcess = null;
    }
  });
};

wssUnsecure.on("connection", onConnection);
if (wssSecure) {
  wssSecure.on("connection", onConnection);
}

httpServer.listen(PORT, () => {
  console.log(`[Daemon] Unsecure server listening on port ${PORT} (ws://localhost:${PORT})`);
});

if (httpsServer) {
  httpsServer.listen(PORT_SECURE, () => {
    console.log(`[Daemon] Secure server listening on port ${PORT_SECURE} (wss://localhost:${PORT_SECURE})`);
  });
}

if (httpsProxyServer) {
  const PORT_PROXY = 5811;
  httpsProxyServer.listen(PORT_PROXY, () => {
    console.log(`[Daemon] Secure NT4 proxy listening on port ${PORT_PROXY} (wss://localhost:${PORT_PROXY})`);
  });
}
