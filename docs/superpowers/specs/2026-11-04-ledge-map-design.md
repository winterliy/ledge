# Ledge map design

## Intent

Build one Minecraft mapping product with a GLAP1/Paper plugin and a Fabric client mod. Ledge uses Dynmap's server-rendered live tile map and per-world map settings, Chunky's rate-controlled chunk pre-generation, and Xaero's full-screen client map interaction model. Implementations may use compatible upstream code where its license permits; the Xaero reference repository is behavioral guidance unless its redistribution rights are verified.

## Goals

- Share world/dimension, tile, renderer, resolution, marker, overlay, and update concepts between the plugin and mod.
- Keep tiles incremental and bounded in memory; render only dirty or requested regions and throttle background work.
- Provide a localhost web map from the GLAP plugin only.
- Provide a full-screen Fabric map opened by a configurable Controls key (default Z), with zoom controls, dimension and 2D/3D selection, and live updates.
- Let each client choose whether player markers are visible; player markers may use skin-face icons. Chat is excluded.
- Let Fabric users inspect the hovered map coordinate in a small top-left XYZ panel with a close control.
- Support right-click map tools: named hex-colored point marker, translucent named filled area, and named outline area.
- Support dragging a rectangular map selection and exporting it as a PNG under `screenshots/`, with size bounds and a preview/confirmation.
- Configure map templates, renderers, and resolutions per world. Provide a sensible default renderer and resolution.
- Exclude in-game time/date and share-link features.

## Platform boundaries

- `common`: platform-independent map identifiers, configuration, tile coordinates, layers, rendering contracts, and persistence formats.
- `glap1`: server world access, Chunky integration, tile generation/update scheduling, configuration, and the embedded localhost web server/UI.
- `fabric`: client key binding, full-screen map UI, local player-marker visibility preference, coordinate panel, map tools, and PNG export.
- Web map is plugin-only. Full-screen map, mouse tools, and screenshots are Fabric-only.
- Player-marker visibility is a client preference. The plugin publishes the marker data needed by connected clients; the Fabric client controls whether to draw those markers.

## Rendering and performance

- Use bounded tiles and a disk-backed tile cache; do not construct a whole-world bitmap in memory.
- Keep server world reads on the server thread and move image composition and file I/O to bounded workers using immutable block snapshots.
- Coalesce repeated dirty-chunk updates, prioritize visible/requested tiles, and cap render concurrency and queue size.
- Chunk pre-generation is opt-in, rate-limited, resumable/cancellable, and must not block the game thread. Detect Chunky at runtime; map viewing remains available without it.
- Initial release ships a default renderer and resolution, with additional renderer/resolution choices configured per world. 2D/3D is a view mode, distinct from the tile renderer.
- Web server binds to loopback by default and reports port conflicts clearly. External binding requires explicit configuration.

## Approaches considered

1. Depend directly on Dynmap for rendering and UI — rejected because current Ledge targets Minecraft 26.3 while Dynmap's published compatibility is older, and it would prevent a shared Fabric screen experience.
2. Reimplement map behavior in `common`, with thin platform adapters — chosen because it preserves a shared data model and lets each platform use its supported world/UI APIs.
3. Fork and modify all upstream implementations — deferred because it creates a large maintenance and license burden; only compatible, clearly licensed components should be incorporated.

## Builder decisions

- Start with filesystem-backed tiles and a conservative default resolution; keep storage and renderer interfaces replaceable.
- Store user-created markers/areas with world and dimension identifiers; use validated 6-digit hex colors and bounded names.
- Client screenshots use the currently selected world/dimension and the user-dragged rectangle; reject exports above a configured pixel/byte cap.
- Client marker visibility is local and persisted per server; server does not offer a player-visibility policy toggle.
- For world tiles to reach Fabric clients, use a versioned, bounded plugin-message protocol; the web UI reads the same tile store locally.

## Risks and open implementation constraints

- Dynmap does not publish compatibility for Minecraft 26.3; use its documented concepts and only incorporate code after checking license and API compatibility.
- Xaero's World Map is marked ARR; do not redistribute its implementation without a license grant. Recreate the requested interaction from the user's description.
- Chunky is GPL-3.0-only; prefer an optional runtime integration through its public API rather than copying code into the proprietary/independently licensed Ledge artifacts.
- Paper/GLAP1 plugin APIs, Fabric mappings, and Minecraft 26.3 networking may still change; isolate version-specific calls in adapters.
