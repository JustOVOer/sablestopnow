# Changelog

> **中文版：[CHANGELOG.md](CHANGELOG.md)**

Release history for **Aeronautics: Tweaks & Toolkit** (mod id stays `sablestopnow`).
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the version numbers follow [Semantic Versioning](https://semver.org/).

## [1.1.0] — 2026-09

The physics staff enhancements grew from "it works" into a complete tool chain: multiplayer coexistence, ownership, a reworked region select, snapshots, scaling, ghosting — plus a full in-game settings screen.

### Added

- **Renamed the mod**: the display name is now **Aeronautics: Tweaks & Toolkit** (the mod id and package stay `sablestopnow`, so saves are compatible).
- **Per-player selections**: every player owns an independent queue, and a body can only be claimed by one player at a time. Bodies already claimed or owned by somebody else are refused with a chat hint naming them. Outline colours come from a 12-colour per-player palette, and other players' selections render only within 64 blocks in the same dimension.
- **Ownership (O)**: claim / release the whole queue, written to the save (`SavedData`) so it survives restarts. The HUD shows the owner of whatever you look at, and OPs get `/sablesn owner list | clear <player> | clear all`.
- **Penetration picking is always available**: Left Alt + scroll changes the penetration depth without having to enter multi-select first.
- **Hover outline, always on**: the body you might select is outlined at any time, and if it is part of the current queue the **whole group's outline is thickened**.
- **Region select (Z) reworked**: press Z to enter; **scroll** sets the distance of the cursor point from you and **your view** sets its direction; before the first corner a single-block preview box is shown; **RMB** confirms a corner and two corners span a cuboid; while picking the second corner the bodies that *would* be selected are outlined live in yellow-green; press Z again to cancel. The scroll wheel no longer switches the hotbar.
- **View lock (C)**: holding the staff outside multi-select and group control, holding C re-picks the body with the smallest angle to your view every tick and locks your view onto it; release to unlock. Inside group control C still means "centre".
- **Snapshots (K / R)**: K creates / cancels a snapshot of the whole queue (a light-blue camera icon marks the members) and R rolls it back — blocks, position, orientation, velocity and name all return to the moment the snapshot was taken. Snapshots are per player.
- **Scaling (X + scroll)**: scales the queue about its **common centroid**, so relative positions and orientations scale together and shapes never fall apart; range and sensitivity are configurable; the HUD shows the current factor plus the factor of the body under the crosshair; the first X press normalises a mixed-scale selection to 1.0 before scaling starts.
- **Ghosting (no player collision)**: new `dragged_no_player_collision` (a body being dragged no longer collides with **the player dragging it**) and `scaled_no_player_collision` (a body with scale ≠ 1 no longer collides with **any** player), both on by default.
- **In-game settings screen**: opened with `Ctrl+O` (or the mod list's *Config* button), implementing the category / detail pages designed in `newConfigGUI/` (enter and leave animations, icon scaling and rotation, options placed along a polyline). Every string is "中文 | English" and changes are written straight back to `config/sablestopnow-common.toml`.
- **All keys migrated to Minecraft `KeyMapping`** — rebind them any time in *Options → Controls → Aeronautics: Tweaks & Toolkit*, no restart needed.

### Changed

- **Box select and the "contour only" outline style** switched from GL lines to thin elongated cuboid boxes in the spirit of Create blueprints / super glue, with the thickness controlled by `staff_outline_thickness` / `outline_thickness` and a smooth animation added; the other outline modes look unchanged.
- The speed auto-lock exemption is wider: bodies that are being dragged, being rescaled, or have already been rescaled are never falsely locked.
- **Artifact renamed**: the jar file prefix changed from `sablestopnow` to `aeronautics-tweaks-toolkit` (i.e. `aeronautics-tweaks-toolkit-1.1.0.jar`). The **mod id and package are still `sablestopnow`** — only the file name follows the display name.
- All staff-related UI text is bilingual, and the HUD now only shows what is currently relevant.

### Fixed

- **"Contour only" drew an extra inward ring**: the old test required both neighbours of an edge to exist and be exposed before skipping it, so the internal seam of the outermost blocks of a flat surface was drawn. It now checks only the single neighbour across that edge, and deduplicates the 12 edges of a block with a bitmask.
- **Render crash `IllegalStateException: Not building!`**: in 1.21.1's `MultiBufferSource.BufferSource`, custom `RenderType`s that are not in `fixedBuffers` all share one `sharedBuffer`, so holding two custom `VertexConsumer`s at once ends the first batch early. Drawing is now always two-phase: finish every box outline and `endBatch`, then draw line icons and axes.
- **Invisible region-select preview box**: the world-space vertices were missing `poseStack.translate(-cameraPos)`.
- **Region select's scroll wheel switched the hotbar.**
- **Toggle keys fired repeatedly from GLFW key repeat**: vanilla also calls `KeyMapping.click()` on REPEAT, so all toggles now use a hand-rolled rising-edge test.
- **The multi-select key (Ctrl) clashed with vanilla sprint**: sprint is now explicitly held down-off every tick while multi-select is active.
- **Settings screen issues**: `rowAt()` returned a nullable `Integer` that was unboxed into an `int` (render NPE); the screen opened with `Ctrl+O` could not be closed with ESC; returning from a category page flickered and never re-ran its animations.
- **Network protocol error / disconnect**: `StreamCodec.unit(...)` only encodes the instance captured at creation, so the clear-selection, restore-snapshot and end-scale packets failed with "Can't encode" and dropped the connection back to the multiplayer menu. They now use hand-written `StreamCodec.of(...)`.
- **Scaling had no effect / was wiped by the server**: Sable's `SableBufferUtils` does **not** serialise `scale` on a `Pose3d`, `SubLevelPhysicsSystem.readPose` only reads position and orientation, and both chunk render paths only translate and rotate. The mod now syncs scale itself and rewrites the render matrix via Mixin as `T((1−s)·(pos−cam)) · R · S`.
- **Scaled bodies snapped back**: the mod's own speed auto-lock was locking members teleported by the motors. Scaling session members are now exempt from the speed scan, and lock state is taken over correctly at the start, end and during a session.

### Known limitations

- Scaling is a **visual + player-collision** feature: Rapier collider size and mass do not follow the scale, so the physics of a rescaled body is still computed at its original size. Sable's serialiser drops `scale`, so the mod persists it itself.
- Ghosting only removes collision — Sable's tracking still carries players standing on a ghosted body.
- The no-collision marker cannot affect terrain; Sable has no per-body "vs world" filter.
- Snapshots are not written to disk (they die with a server restart or leaving the world), and restoring is a "remove + rebuild", so there is a brief respawn flicker; other mods' links to the structure are not guaranteed to come back.
- Group control drives each member with physics motors; very large or fast structures may wobble slightly (the stiffness/damping values are tuned empirically).

## [1.0.4] — 2026-08

### Added
- `/sablesn tick <steps>`: steps the given number of physics ticks while physics is paused, then restores the pause automatically.
- Speed auto-lock: bodies faster than `speed_limit_threshold` are locked automatically and announced server-wide with a **clickable teleport suggestion**.

### Fixed
- Speed-lock broadcast spam: added a 60-tick cooldown per body, and it only announces after the lock is confirmed.
- The reflection that reads Aeronautics' drag sessions could pick up the `locks` map instead, which made ordinary dragged bodies look "not dragged" and locked them by mistake.

## [1.0.3] — 2026-07

### Added
- The no-collision marker now applies to the **whole selection queue**.
- A persistent HUD showing multi-select state, penetration depth, selection count and more.
- Experimental `ghost_real`: truly removes collisions with other Sable bodies (terrain and players excluded).

## [1.0.2] — 2026-07

### Added
- **Physics Staff Enhancements**, first release: multi-select (Ctrl), box select (Z), penetration picking (Alt + scroll), smart lock toggle, and group control (move / scroll for distance / hold C to centre / TAB to rotate).
- A uniform cyan "contour only" selection and hover outline with a ring icon.
- Bilingual action hints.

## [1.0.1] — 2026-06

### Changed
- Surface outlines / axis rendering and other display options were tidied into config settings, and some redundant logging was turned off.

## [1.0.0] — 2026-06

### Added
- First release: force threshold filtering (`threshold` + `excluded_groups`), auto-pause with a server-wide broadcast when a force is filtered, paginated force history `/sablesn forces [filtered] [page]` (clicking a body id fills in a `/tp` command), `/sablesn confirm` / `/sablesn deny` confirmation before a heat-map split, auto-locking of newly assembled sub-levels, disabling the block-placement collision check, and more.
