package com.areslib.logging

/** Static local log-manager UI; HTTP routing and authentication remain in the server. */
internal object LogDashboardPage {
    val html by lazy {
        """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>ARES Telemetry | Log Manager</title>
                <style>
                    :root {
                        --bg-dark: #0f1115;
                        --glass-bg: rgba(255, 255, 255, 0.05);
                        --glass-border: rgba(255, 255, 255, 0.1);
                        --text-light: #e2e8f0;
                        --text-muted: #94a3b8;
                        --accent-blue: #3b82f6;
                        --accent-blue-hover: #2563eb;
                        --accent-red: #ef4444;
                        --accent-red-hover: #dc2626;
                        --accent-green: #10b981;
                    }
                    body {
                        margin: 0;
                        padding: 0;
                        font-family: 'Segoe UI', Arial, sans-serif;
                        background-color: var(--bg-dark);
                        color: var(--text-light);
                        background-image: radial-gradient(circle at 50% 0%, rgba(59,130,246,0.15), transparent 50%);
                        min-height: 100vh;
                    }
                    .container {
                        max-width: 900px;
                        margin: 0 auto;
                        padding: 2rem;
                    }
                    header {
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        margin-bottom: 2rem;
                        border-bottom: 1px solid var(--glass-border);
                        padding-bottom: 1rem;
                    }
                    h1 {
                        margin: 0;
                        font-weight: 600;
                        font-size: 1.8rem;
                        background: linear-gradient(to right, #60a5fa, #a78bfa);
                        -webkit-background-clip: text;
                        -webkit-text-fill-color: transparent;
                    }
                    .glass-card {
                        background: var(--glass-bg);
                        backdrop-filter: blur(10px);
                        -webkit-backdrop-filter: blur(10px);
                        border: 1px solid var(--glass-border);
                        border-radius: 12px;
                        padding: 1.5rem;
                        margin-bottom: 1rem;
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        transition: transform 0.2s, background 0.2s;
                    }
                    .glass-card:hover {
                        transform: translateY(-2px);
                        background: rgba(255, 255, 255, 0.08);
                    }
                    .log-info h3 {
                        margin: 0 0 0.5rem 0;
                        font-size: 1.1rem;
                        font-weight: 400;
                    }
                    .log-meta {
                        display: flex;
                        gap: 1rem;
                        font-size: 0.85rem;
                        color: var(--text-muted);
                    }
                    .badge {
                        padding: 0.2rem 0.6rem;
                        border-radius: 9999px;
                        font-size: 0.75rem;
                        font-weight: 600;
                        background: rgba(16, 185, 129, 0.1);
                        color: var(--accent-green);
                        border: 1px solid rgba(16, 185, 129, 0.2);
                    }
                    .badge.unsynced {
                        background: rgba(245, 158, 11, 0.1);
                        color: #f59e0b;
                        border-color: rgba(245, 158, 11, 0.2);
                    }
                    .actions {
                        display: flex;
                        gap: 0.5rem;
                    }
                    button {
                        background: none;
                        border: none;
                        padding: 0.5rem 1rem;
                        border-radius: 6px;
                        font-family: 'Segoe UI', Arial, sans-serif;
                        font-size: 0.9rem;
                        font-weight: 600;
                        cursor: pointer;
                        transition: all 0.2s;
                        color: white;
                    }
                    .btn-upload {
                        background-color: var(--accent-blue);
                    }
                    .btn-upload:hover {
                        background-color: var(--accent-blue-hover);
                    }
                    .btn-delete {
                        background-color: transparent;
                        border: 1px solid var(--accent-red);
                        color: var(--accent-red);
                    }
                    .btn-delete:hover {
                        background-color: var(--accent-red);
                        color: white;
                    }
                    .btn-upload:disabled, .btn-delete:disabled {
                        opacity: 0.5;
                        cursor: not-allowed;
                    }
                    .empty-state {
                        text-align: center;
                        padding: 4rem 2rem;
                        color: var(--text-muted);
                        font-size: 1.1rem;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <header>
                        <h1>ARES Telemetry Manager</h1>
                        <button class="btn-upload" onclick="fetchLogs()" style="background-color: rgba(255,255,255,0.1);">Refresh</button>
                    </header>
                    <div id="logs-container">
                        <div class="empty-state">Loading logs...</div>
                    </div>
                </div>

                <script>
                    function formatBytes(bytes, decimals = 2) {
                        if (!Number.isFinite(bytes) || bytes < 0) return 'Unknown size';
                        if (bytes === 0) return '0 Bytes';
                        const sizes = ['Bytes', 'KiB', 'MiB', 'GiB', 'TiB', 'PiB', 'EiB'];
                        const i = Math.max(0, Math.min(sizes.length - 1, Math.floor(Math.log(bytes) / Math.log(1024))));
                        const digits = Number.isFinite(decimals) ? Math.max(0, Math.min(20, Math.trunc(decimals))) : 2;
                        return parseFloat((bytes / Math.pow(1024, i)).toFixed(digits)) + ' ' + sizes[i];
                    }

                    function element(tag, className, text) {
                        const node = document.createElement(tag);
                        if (className) node.className = className;
                        if (text !== undefined) node.textContent = text;
                        return node;
                    }

                    let refreshGeneration = 0;
                    async function fetchLogs() {
                        const generation = ++refreshGeneration;
                        const container = document.getElementById('logs-container');
                        try {
                            const res = await fetch('/api/logs', { cache: 'no-store' });
                            if (!res.ok) throw new Error('Listing failed');
                            const logs = await res.json();
                            if (!Array.isArray(logs)) throw new Error('Invalid listing');
                            if (generation !== refreshGeneration) return;
                            if (logs.length === 0) {
                                container.replaceChildren(element('div', 'empty-state', 'No logs found on device.'));
                                return;
                            }
                            const rows = document.createDocumentFragment();
                            for (const log of logs) {
                                const card = element('div', 'glass-card');
                                const info = element('div', 'log-info');
                                info.append(element('h3', '', log.name));
                                const meta = element('div', 'log-meta');
                                meta.append(element('span', '', formatBytes(log.sizeBytes)));
                                meta.append(element('span', '', log.lastModifiedFmt));
                                meta.append(element('span', 'badge' + (log.synced ? '' : ' unsynced'), log.synced ? 'Synced' : 'Unsynced'));
                                info.append(meta);
                                const actions = element('div', 'actions');
                                const button = element('button', 'btn-delete', 'Delete');
                                button.addEventListener('click', () => deleteLog(log.name, button));
                                actions.append(button);
                                card.append(info, actions);
                                rows.append(card);
                            }
                            container.replaceChildren(rows);
                        } catch (e) {
                            if (generation !== refreshGeneration) return;
                            const message = element('div', 'empty-state', 'Error loading logs.');
                            message.style.color = 'var(--accent-red)';
                            container.replaceChildren(message);
                        }
                    }

                    async function deleteLog(fileName, button) {
                        if (button.disabled || !confirm('Delete ' + fileName + ' and any synced copy?')) return;
                        button.disabled = true;
                        button.textContent = 'Deleting...';
                        try {
                            let token = sessionStorage.getItem('aresLogDeleteToken');
                            if (!token) {
                                token = prompt('Enter the ARES log-delete token:')?.trim();
                                if (!token) return;
                                sessionStorage.setItem('aresLogDeleteToken', token);
                            }
                            const res = await fetch('/api/delete?file=' + encodeURIComponent(fileName), {
                                method: 'POST',
                                headers: { 'X-ARES-Delete-Token': token }
                            });
                            if (res.status === 401) sessionStorage.removeItem('aresLogDeleteToken');
                            if (!res.ok) throw new Error('Delete failed');
                            await fetchLogs();
                        } catch (e) {
                            alert('Delete failed.');
                        } finally {
                            button.disabled = false;
                            button.textContent = 'Delete';
                        }
                    }
                    fetchLogs();
                </script>
            </body>
            </html>
        """.trimIndent()

    }
}
