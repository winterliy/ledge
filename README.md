# Ledge

Ledge is a shared Minecraft map project with two platform adapters:

- `common`: platform-independent map coordinates, tile keys, render styles, resolutions, marker/area models, and PNG tile storage.
- `glap1`: Paper/GLAP1 plugin with a localhost web map, server-side tile renderer, block-change refresh, and optional Chunky API integration.
- `fabric`: Fabric client mod. It is still the original loader skeleton; the full-screen map and client tools have not been implemented yet.

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

### Known limitations

- The Fabric module does not yet include the requested full-screen map, configurable `Z` key, live tile protocol, local player-marker toggle, hovered XYZ panel, right-click tools, map layers, or screenshot selection/export.
- GLAP web view is a first pass. Its 3D mode is a visual tilt of the 2D canvas, not a true 3D terrain renderer.
- The cave renderer currently has no server-side cave sampling, so it produces transparent tiles. The block palette is a basic material-name approximation, not Minecraft's full biome/texture map color system.
- Web player markers are dots without skin-face icons. Chat, game time/date, and share links are intentionally omitted.
- Marker and area models exist in `common`, but persistence and editing UI are not connected yet.
- The renderer currently samples the top block of already-loaded columns. Chunky pre-generation is optional and is not automatically started by opening the map.
- Dynmap code has not been copied; its behavior and tile concepts are being reimplemented for the current target. The Xaero implementation has not been copied or integrated.
- No full gameplay/integration testing has been done. The latest compile check was `./gradlew :common:jar :glap1:jar`; Fabric has not changed from the initial skeleton in this implementation pass.

## Next implementation steps

1. Finish GLAP world rendering: biome/material map colors, cave sampling, 3D renderer, tile revision handling, and accurate per-world/dimension configuration.
2. Add the bounded, versioned plugin-message tile protocol so Fabric clients can request and receive live tiles without depending on the web port.
3. Build the Fabric full-screen map: Controls key (default `Z`), pan/zoom, dimension and 2D/3D selection, renderer/resolution controls, local player-marker toggle, and top-left hover XYZ panel with close button.
4. Add Fabric right-click tools for named hex-color point markers, translucent filled areas, and outlines; persist them by server, world, and dimension.
5. Add drag-to-select PNG export under the Minecraft `screenshots/` folder, with preview/confirmation and strict image-size limits.
6. Add runtime verification on a GLAP server and Fabric client, check live updates and Chunky operation, then package and document install/config steps.

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
