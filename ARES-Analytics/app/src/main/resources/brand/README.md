# ARES Robotics Studio brand assets

`ares-studio-master.png` is the reviewed 1024 px application mark. It is intentionally simpler
than the official ARES 23247 team artwork in `ares-mark.webp`: desktop launchers must remain
recognizable at 16–32 px, while the detailed team mark remains appropriate for team links and
larger identity surfaces.

The application mark was generated with OpenAI's built-in image-generation tool, reviewed in the
ARES workspace, and then post-processed with `scripts/generate-app-icons.py`. The reviewed prompt
specified a simplified red Spartan helmet, a single cyan circuit trace, an obsidian rounded square,
no text, and no fine flower or helmet detail. The script removes the generated preview matte and
deterministically produces:

- `ares-studio-app.png` — Compose window/taskbar resource
- `ares-studio.png` — Linux package icon
- `ares-studio.ico` — multi-resolution Windows package icon
- `ares-studio.icns` — multi-resolution macOS package icon

Regenerate derived files from the checked-in master with:

```powershell
python scripts/generate-app-icons.py `
  --source app/src/main/resources/brand/ares-studio-master.png `
  --output-dir app/src/main/resources/brand
```

Do not replace the icon merely by exporting the detailed team mark at a smaller size. Preserve the
high-contrast Spartan silhouette, small cyan engineering accent, generous padding, and readable
small-size shape.
