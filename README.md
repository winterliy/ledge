# Ledge

Ledge is a shared Minecraft map project with two platform adapters:

- `common`: platform-independent map coordinates, tile keys, render styles, resolutions, marker/area models, and PNG tile storage.
- `glap1`: Paper/GLAP1 plugin with a localhost web map, server-side tile renderer, block-change refresh, and a built-in terrain pre-generator.
- `fabric`: Fabric client mod with a configurable Controls key, full-screen tile viewer, map panning/zoom, coordinate readout, player marker toggle, and selected-region screenshots.

## Current progress

This is an **early development preview (`0.1.0-alpha.4`)**, not a complete map product.

### Implemented

- Shared 256×256 PNG tile format with path-safe world/dimension identifiers, resolution levels (`LOW`, `STANDARD`, `HIGH`, `ULTRA`), and zoom levels.
- Shared `SURFACE`, `FLAT`, and `CAVE` renderer choices, plus world configuration and marker/area data models.
- Filesystem tile cache with atomic writes, image-size checks, and versioned renderer paths so rendering improvements rebuild tiles without deleting the previous cache.
- GLAP plugin web viewer on the configured port, bound to `127.0.0.1` by default. External binding requires `web.allow-external: true`.
- Server-side tile sampling is incremental and limited per tick; image composition and PNG writes run on a bounded worker queue. Unloaded chunks are not force-loaded.
- GLAP web map can select loaded worlds, renderer, resolution, 2D/3D view, and player-marker visibility. Player markers are currently simple dots.
- The GLAP cave renderer samples an underground horizontal slice at one quarter of the world's height range and shows solid blocks exposed to non-solid space. Unloaded chunks remain transparent. Surface vegetation and water colors receive a biome-specific tint.
- Surface tiles use height gradients from four neighbors to add directional relief shading shared by the web map and Fabric client. A one-sample border reads adjacent loaded terrain so the shading does not stop at tile edges; unloaded border chunks reuse the nearest sampled height without force-loading.
- Placing, breaking, burning, and explosion changes version the affected chunks instead of walking every cached zoom variant. Active Fabric subscriptions rerender, and cached inactive variants are checked against chunk versions on the next request.
- Built-in terrain pre-generator modelled on Chunky (no separate plugin needed): `/ledge pregen start <world> <radius> [square|circle] [centerX centerZ]`, `/ledge pregen pause|continue|cancel <world>`, `/ledge pregen status`. Chunks are generated from the center outward with bounded async loads; progress is saved to `plugins/Ledge/pregen.yml` and survives restarts (tasks return paused). Each generated chunk refreshes the map.
- Fabric client opens the full-screen map with `Z` by default; the binding is registered in Controls.
- Fabric requests server world settings and nearby tiles over the `ledge:map` play channel. PNG responses are split into bounded fragments, reassembled with transfer limits, and held in a capped, world/dimension-aware client texture cache.
- Fabric can cycle through the server's loaded world/dimension targets from the map header. Each request is limited to tiles within eight tile steps of the player's coordinates, and uses that target's configured renderer and resolution.
- The Fabric map follows the player by default, supports drag panning and wheel zoom (`0`–`12`), and reports hovered block X/Z plus player Y in a closable coordinate panel. Its `2D`/`3D` button switches between a flat map and an isometric diamond-tile projection. Resolution and renderer follow the selected target's server settings.
- Surface `3D` view now uses a server-generated height projection shared by the GLAP web map and Fabric client. Sampled terrain cells are drawn in depth order with height-displaced tops and shaded exposed east/south cliff faces. A one-sample halo includes neighboring tile heights without loading chunks, and projected PNGs are cached alongside the color and height tiles.
- The local player marker can be toggled from the map. Zoom works with the wheel and `−`/`+` buttons. The print control arms a drag selection and saves the selected screen region in the Minecraft `screenshots/` folder.
- Right-click opens point and freeform-area tools. Points accept a name and hex banner color; areas accept a name/color and can be translucent fills or outlines. Hold Shift while drawing to add a straight segment from the last point. These annotations persist locally per server, world, and dimension in `config/ledge/annotations/`.
- The GLAP adapter retains bounded subscriptions to requested tiles and pushes a refreshed tile after server-side rendering completes.

### Latest work session (2026-11-05)

- Replaced the flat diamond arrangement in surface `3D` view with a shared elevation-projected terrain raster. Neighbor heights form darker side faces and prevent discontinuities at tile edges.
- Added a bounded signed 16-bit height sidecar and a versioned projected PNG cache; both web and Fabric consume the exact same projected raster generated by the server.
- Kept world sampling on the server thread and limited to loaded chunks; projection and PNG/cache work run on the bounded renderer worker.
- Started an isolated Minecraft 26.3 server with the plugin, forced a small test area to load, and confirmed `/api/worlds`, color tiles, and projected PNG tiles respond successfully. Inspected the generated terrain image. Fixed projected-cache decoding discovered during this runtime check.
- Moved tile files to a versioned renderer path (`v2`). The previous cache remains on disk, while the updated renderer regenerates tiles as they are requested.
- Verified JavaScript syntax and compiled all three modules with `./gradlew --no-daemon --offline --no-configuration-cache :common:compileJava :glap1:compileJava :fabric:compileJava`.

### Runtime verification (2026-11-06)

- Started Paper 26.3 build 218 with the GLAP1 plugin and joined it using a Fabric 26.3 client. Confirmed the client received and decoded both regular and projected terrain PNG tiles while the Ledge map screen was open.
- The session exposed invalid Minecraft texture identifiers for uppercase world IDs. Texture paths now encode world/dimension identifiers as lowercase hexadecimal and tile coordinates as safe path segments.
- Confirmed Paper's web endpoints return the world list and color/projected PNG tiles. The generated projected terrain image was inspected separately.
- The game session used an offline test player on an isolated local Paper 26.3 build 218 server. The client opened the map and decoded regular/projected tiles. Automated in-game interaction checks passed for coordinate panel visibility, player marker toggle, 2D/3D switching, zoom buttons and wheel, drag panning, right-click annotation menu, point and area persistence, and selected-region screenshot creation.
- Fixed the screenshot crop to copy the selected rectangle from the captured frame into the new image. The screenshot output was checked after the in-game selection test.
- Kept the target selector usable at narrow screen widths by clamping its hitbox and truncating its label to the available space.
- Rebuilt alpha.4 with `check` and all module jars, then started the exact Paper jar on Paper 26.3 build 218. Verified the web page, worlds and players APIs, regular 256×256 PNG tile, and projected PNG tile return successfully. Loaded Fabric 26.3 alpha.4 and joined the same local Paper server.

### Known limitations

- Surface `3D` is an elevation-projected raster rather than a rotatable perspective renderer. It draws exposed east/south cliff faces; caves and other renderers retain the existing flat/diamond display.
- The screenshot tool writes the selected region directly without a preview or confirmation.
- The plugin-message channel requires both the GLAP plugin and Fabric mod on the same server connection. The web map remains plugin-only.
- GLAP web view is a first pass. Its 3D mode uses an isometric tile projection, not a true 3D terrain renderer.
- Cave mode shows one fixed-height slice, not a complete 3D cave survey. Biome tints improve the basic material palette but do not reproduce Minecraft's exact texture and map-color rules.
- Web player markers are dots without skin-face icons. Chat, game time/date, and share links are intentionally omitted.
- Client annotations are local and are not yet sent to the server or shown in the web map.
- The renderer currently samples the top block of already-loaded columns. Already generated chunks that are not loaded are read from disk for map tiles; ungenerated terrain needs `/ledge pregen`.
- Dynmap code has not been copied; its behavior and tile concepts are being reimplemented for the current target. The Xaero implementation has not been copied or integrated.
- A human visual review on a normal interactive game session remains outstanding; the recorded control checks were automated in a local client/server session.

## Next implementation steps

Start here next:

1. Manually inspect steep terrain, tile boundaries, water, and player-marker alignment in both clients; test controls, panning, zoom, and annotations.
2. Add a screenshot preview/confirmation before saving selected map regions.
3. Continue optimizing projected tile transfer and cache behavior if live updates show duplicate or stale images, then verify renderer/resolution changes and Chunky pre-generation.

The shared height-data and projection path is in place; next work should begin with human visual review of the resulting terrain in a normal interactive game session.

## Requirements

- JDK 25
- Gradle Wrapper (included)
- Minecraft 26.3, Fabric Loader/API, and a compatible Paper/GLAP1 server

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

Ledge's built-in pre-generator follows Chunky's task model (GPL-3.0-only); Chunky itself is not required or bundled. Xaero code is not redistributed. Check each upstream license before incorporating or distributing upstream implementation code.
