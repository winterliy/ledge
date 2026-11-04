# Ledge

Ledge is a shared Minecraft map project with two platform adapters:

- `common`: platform-independent map coordinates, tile keys, render styles, resolutions, marker/area models, and PNG tile storage.
- `glap1`: Paper/GLAP1 plugin with a localhost web map, server-side tile renderer, block-change refresh, and optional Chunky API integration.
- `fabric`: Fabric client mod with a configurable Controls key, full-screen tile viewer, map panning/zoom, coordinate readout, player marker toggle, and selected-region screenshots.

## Current progress

This is an **early development preview (`0.1.0-alpha.1`)**, not a complete map product.

### Implemented

- Shared 256×256 PNG tile format with path-safe world/dimension identifiers, resolution levels (`LOW`, `STANDARD`, `HIGH`, `ULTRA`), and zoom levels.
- Shared `SURFACE`, `FLAT`, and `CAVE` renderer choices, plus world configuration and marker/area data models.
- Filesystem tile cache with atomic writes and image-size checks.
- GLAP plugin web viewer on the configured port, bound to `127.0.0.1` by default. External binding requires `web.allow-external: true`.
- Server-side tile sampling is incremental and limited per tick; image composition and PNG writes run on a bounded worker queue. Unloaded chunks are not force-loaded.
- GLAP web map can select loaded worlds, renderer, resolution, 2D/3D view, and player-marker visibility. Player markers are currently simple dots.
- Placing, breaking, burning, and explosion changes invalidate affected surface tiles for live refresh.
- Optional Chunky 1.5.4 public API integration when Chunky is separately installed. Commands: `/ledge pregen start <world> <radius>`, `/ledge pregen pause <world>`, `/ledge pregen continue <world>`, and `/ledge pregen cancel <world>`.
- Fabric client opens the full-screen map with `Z` by default; the binding is registered in Controls.
- Fabric requests server world settings and nearby tiles over the `ledge:map` play channel. PNG responses are split into bounded fragments, reassembled with transfer limits, and held in a capped, dimension-aware client texture cache.
- The Fabric map follows the player by default, supports drag panning and wheel zoom (`0`–`12`), and reports hovered block X/Z plus player Y in a closable coordinate panel. Resolution and renderer follow the server's current-world settings.
- The local player marker can be toggled from the map. Zoom works with the wheel and `−`/`+` buttons. The print control arms a drag selection and saves the selected screen region in the Minecraft `screenshots/` folder.
- Right-click opens point and freeform-area tools. Points accept a name and hex banner color; areas accept a name/color and can be translucent fills or outlines. Hold Shift while drawing to add a straight segment from the last point. These annotations persist locally per server and dimension in `config/ledge/annotations/`.
- The GLAP adapter retains bounded subscriptions to requested tiles and pushes a refreshed tile after server-side rendering completes.

### Known limitations

- Fabric's controls for dimension and 2D/3D view are still to be built; renderer and resolution currently follow the server's current-world settings.
- Web/Fabric annotation synchronization is not implemented.
- The screenshot tool writes the selected region directly without a preview or confirmation.
- The plugin-message channel requires both the GLAP plugin and Fabric mod on the same server connection. The web map remains plugin-only.
- GLAP web view is a first pass. Its 3D mode is a visual tilt of the 2D canvas, not a true 3D terrain renderer.
- The cave renderer currently has no server-side cave sampling, so it produces transparent tiles. The block palette is a basic material-name approximation, not Minecraft's full biome/texture map color system.
- Web player markers are dots without skin-face icons. Chat, game time/date, and share links are intentionally omitted.
- Client annotations are local and are not yet sent to the server or shown in the web map.
- The renderer currently samples the top block of already-loaded columns. Chunky pre-generation is optional and is not automatically started by opening the map.
- Dynmap code has not been copied; its behavior and tile concepts are being reimplemented for the current target. The Xaero implementation has not been copied or integrated.
- No full gameplay/integration testing has been done. The latest build check was `./gradlew :common:jar :glap1:jar :fabric:jar`.

## Next implementation steps

1. Finish GLAP world rendering: biome/material map colors, cave sampling, 3D renderer, tile revision handling, and accurate per-world/dimension configuration.
2. Add client controls for dimension and 2D/3D view.
3. Decide whether annotations should sync to the server and web map, then add that persistence path.
4. Add screenshot preview/confirmation and runtime verification on a GLAP server and Fabric client; check live updates and Chunky operation.

## Requirements

- JDK 25
- Gradle Wrapper (included)
- Minecraft 26.3, Fabric Loader/API, and a compatible Paper/GLAP1 server
- Optional: Chunky plugin 1.5.4 or a compatible release

## Configuration

The GLAP plugin creates `plugins/Ledge/config.yml` on first start:

```yaml
web:
  enabled: true
  host: 127.0.0.1
  port: 8123
  allow-external: false
render:
  max-samples-per-tick: 2048
defaults:
  renderer: SURFACE
  resolution: STANDARD
  live-updates: true
worlds: {}
```

Open `http://localhost:8123/` on the server computer. To bind the web map to another address, set `web.host` and explicitly enable `web.allow-external`.

Per-world settings can be added under `worlds.<world-name>.<dimension-id>` (dimension IDs use an underscore in place of the namespace colon):

```yaml
worlds:
  world:
    minecraft_overworld:
      renderer: SURFACE
      resolution: STANDARD
      live-updates: true
```

## Build

```sh
./gradlew :common:jar :glap1:jar :fabric:jar
```

Artifacts are written under `common/build/libs/`, `glap1/build/libs/`, and `fabric/build/libs/`.

## Upstream references and licensing

- Dynmap behavior reference: https://github.com/webbukkit/dynmap (Apache-2.0)
- Chunky integration/API reference: https://github.com/pop4959/Chunky (GPL-3.0-only)
- Xaero World Map interaction reference: https://modrinth.com/mod/xaeros-world-map (ARR)

Chunky is used as an optional separate plugin through its public API; it is not bundled in Ledge. Xaero code is not redistributed. Check each upstream license before incorporating or distributing upstream implementation code.
