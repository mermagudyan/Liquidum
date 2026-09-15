# Liquidum

A client-side Fabric mod that turns the Minecraft interface into liquid glass. Buttons, slots and bars become refractive glass floating over a live blurred backdrop — while items, models and text stay tack-sharp above it.

## What it does

Open the inventory: the panel texture is gone, replaced by one continuous frosted surface with concave slot wells etched into it. Open a chest or a furnace on snow and in the Nether: the same surface adapts — dimmer on bright backdrops, lifted on dark ones. Press Esc: the hotbar collapses into an iPhone-style Home strip so it never fights the menu buttons; back in the world it grows back with items and counts. Move the mouse over glass: a flare follows the cursor, the world behind bends at the rims, item icons drift slightly toward it.

Concretely covered: buttons, sliders and tabs; the hotbar (glass bar, gliding selection ring, offhand tile); player inventory, crafting, chests, furnaces and other containers; the recipe book; creative tabs; search fields and scrollbars; furnace flame and progress. Health, hunger, armor, air and XP can get experimental glass halos from the Lab.

## How it works

No custom framebuffers and no stencil tricks — everything goes through the engine's own post chain. Between the backdrop phase and the widget phase the mod runs a glass pass over the finished background: box-blurred copies feed frost and refraction, signed-distance fields cut panel, pill and well shapes with fused metaball bridges, then widgets, items and text draw crisply on top. Slot grids are procedural (one descriptor per grid, cost independent of slot count), progress icons and carried items are replayed sharp in the foreground.

## Material Lab (F7)

A live glass bench built into the mod: a demo scene on grass/snow/nether/end/grid/night backdrops, effect toggles, material sliders with A/B snapshots, Main/Custom profiles (the status flips to Custom the moment values drift off stock), per-screen coverage switches, automatic Russian/English interface. F8 opens the same Lab; Shift+F7 keeps the legacy engineering screen.

## Configuration

Defaults (the Main profile):

- Refraction `9.0`, fresnel `0.65`, sharpness `0.18`, corner `0.18`
- Frost on, radius `10.0`; fusion on, radius `12.0`
- Rim, aberration, hover flare and parallax on; open animation off
- Dome and sun glare `0.0` (reserved); halo toggles and smooth scroll off, dock adaptation on

Everything lives in JSON next to the profiles and the Lab scene, editable live without restarts.

### Logging

Normal play logs almost nothing. When reporting a visual bug, attach the block from `run/logs/debug.log` with your Lab state (DEBUG tab → dump diagnostics), the screen name, and whether it happens on a bright backdrop, a dark one, or both. The engineering view (DEBUG tab, debug argument below) isolates every pipeline stage.

### Debug argument

The engineering DEBUG tab and verbose renderer logging are gated behind a JVM argument, off for regular players:

```
-Dliquidum.debug=true
```

In the vanilla launcher: Installations → your profile → More Options → JVM Arguments, append it with a space. Dev runs via Gradle already carry it. It unlocks the Lab DEBUG tab (solo pipeline stages, capture/mask views, crash-on-error, slot geometry overlay) plus per-frame glass logging — attach that output when reporting a bug.

## Limitations

- The debug overlay (F3), book/sign/command editors and structure blocks stay vanilla on purpose — readability and complex inputs first
- Creative tab transitions are parked (disabled) until the scroll spring stops fighting them
- Settings sheets intentionally keep no fullscreen frost — the backdrop stays the sharp dimmed world like the pause menu
- Glass is decorative: critical info (health, hunger, chat, tooltips) is always drawn above it

## Building

### Prerequisites

- **JDK 25.** Any distribution works. Set `JAVA_HOME` to it before building:

  ```powershell
  $env:JAVA_HOME = "C:\path\to\jdk-25"
  ```

- **No manual Gradle install needed.** The wrapper (`gradlew.bat`) fetches Gradle itself.
- **Offline-friendly after the first build.** Loom caches Minecraft, mappings, Loader and API under `~/.gradle` / `.gradle`.

### Commands

```powershell
# Fast gate before every change (also runs the stacked-comment lint)
.\gradlew.bat compileJava compileClientJava --offline --console=plain > .build-check.log 2>&1

# Full remapped build before boot/commit (delete the log after reading)
.\gradlew.bat build --offline --console=plain > .build-check.log 2>&1
```

Piped output with redirect-to-file is required: streaming Gradle output hangs on daemon handles. If even file mode hangs, the daemon is wedged — `gradlew --stop`, retry.

### Output

```
build\libs\liquidum-<version>.jar
```

Drop that jar into `mods/` to install. `*-sources.jar` next to it is for developers.

### Troubleshooting

- **`invalid source release: 25`** — `JAVA_HOME` points at an older JDK
- **First build downloads a lot** — Loom fetching the game and mappings once
- **Stale outputs** — `./gradlew clean` and rebuild

## Installation

1. Install Fabric Loader and Fabric API for your Minecraft version.
2. Drop the mod jar into `mods/`.

## License

GPL-3.0-only — see [LICENSE](LICENSE). Source and issues: https://github.com/mermagudyan/Liquidum
