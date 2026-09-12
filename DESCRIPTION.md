# Aeronautics: Tweaks & Toolkit

A practical NeoForge addon for **Create: Aeronautics / Simulated** and the **Sable** physics engine (Minecraft 1.21.1). Mod id stays `sablestopnow`.

---

## What it does

Two toolkits on top of Sable's sub-level physics:

### 1. Force monitor & limiter

Protect your physics builds from excessive forces:

- Filter forces above a configurable threshold (`[force_limiter].threshold`), with group allow-lists (`excluded_groups`, e.g. let `sable:gravity` pass).
- Optional auto-pause when a force is filtered, with a server-wide broadcast and a clickable `/sablesn forces` hint.
- Paginated force history: `/sablesn forces [page]` and `/sablesn forces filtered [page]`; click a sub-level ID to fill in a `/tp` command.
- Split confirmation (`/sablesn confirm` / `/sablesn deny`) before a heat-map driven split.
- Paused-physics stepping: `/sablesn tick <steps>` steps the specified number of physics ticks while paused, then restores the pause automatically.
- Speed auto-lock: bodies faster than `speed_limit_threshold` are locked automatically and announced in chat with a **clickable teleport suggestion**; bodies that are currently being dragged or rescaled are never falsely locked.
- Utility toggles: auto-lock freshly assembled sub-levels, disable block-placement collision checks, render surface outlines/axes.

### 2. Physics Staff Enhancements

Upgrade the Aeronautics **physics staff** into a practical build tool (master switch: `[staff_enhance].enable_staff_enhance`). Every binding is a real Minecraft `KeyMapping`, so it can be rebound in **Options → Controls → Aeronautics: Tweaks & Toolkit**.

| Key | Default | Action |
|---|---|---|
| Multi-select | Ctrl | Enter / leave multi-select mode |
| Region select | Z | Two-corner region select (see below) |
| Whole-group no-collision | V | Toggle the no-collision marker for the whole queue |
| Group centering / view lock | C | Ease the group centroid to the view centre — or, outside group control, hold to lock your view onto the nearest body |
| Ownership | O | Claim / release the whole queue |
| Snapshot | K | Create / cancel a snapshot of the whole queue |
| Restore | R | Roll the snapshot back |
| Scale | X | Hold X + scroll to scale the selection |
| Penetration modifier | Alt | Alt + scroll picks through layers (always available) |
| Open settings | Ctrl+O | Open this mod's in-game settings screen |

#### Multi-select & selection queue
- **Ctrl** toggles multi-select; RMB adds the pointed body, Shift+RMB removes it.
- **One body belongs to one player**: bodies already claimed or already owned by somebody else are refused, with a chat hint naming the owner. Your own queue is independent of other players'.
- Outline colour is assigned per player (12-colour palette), so different players' selections are visually distinct; other players' selections are rendered only within 64 blocks.
- Leaving multi-select keeps your queue (so you can go straight into group control) but releases the server-side claim.

#### Region select (Z)
- Press **Z** anywhere (multi-select not required) to start.
- **Scroll** sets the distance of the cursor point from you, **your view** sets its direction; before the first corner the preview is a single-block box.
- **RMB** confirms the first corner. While picking the second one, the bodies that *would* be selected are outlined live in yellow-green.
- **RMB** again selects everything intersecting the box; **Z** cancels.

#### Ownership (O)
- **O** claims the queue for you; press again when the whole queue is already yours to release it.
- Ownership is written to the save (`SavedData`), so it survives restarts, and the owner is shown on the HUD when you look at a body.
- OP back door: `/sablesn owner list`, `/sablesn owner clear <player>`, `/sablesn owner clear all`.

#### Snapshots (K / R)
- **K** snapshots the whole queue (a light-blue camera icon marks snapshot members); **K** again cancels.
- **R** restores it: blocks, position, orientation, velocity and name all return to the moment of the snapshot. Snapshots are per player and live in server memory.

#### View lock (C)
- Holding staff, outside multi-select and group control, holding **C** keeps re-picking the body with the smallest angle to your view and locks your view onto it; release to unlock.

#### Scaling (X + scroll)
- Hold **X** and scroll to scale the selected queue between `scale_min` and `scale_max` (0.2–8.0 by default).
- The group is scaled about its **common centroid**, keeping relative positions and orientations, so shapes never fall apart. The HUD shows the current factor, plus the factor of whatever body you are looking at.
- The first X press normalises a mixed-scale selection back to 1.0 before scaling.

#### Group control
- After leaving multi-select, RMB a queued body to unlock everything and enter group control: turn your view or walk to move the group, scroll to change the distance along the eye→centroid line, hold **C** to ease the centroid into the view centre, hold **TAB** and move the mouse to rotate about the centroid, RMB again to finish. LMB toggles locking and **V** toggles no-collision for the whole queue.

#### Ghosting / no player collision
- **V** toggles the no-collision marker for the whole queue (saved).
- `dragged_no_player_collision` (default on): a body that is currently being dragged no longer collides with **the player dragging it**.
- `scaled_no_player_collision` (default on): a body whose scale is not 1 no longer collides with **any** player.

#### Outlines & HUD
- Selection / hover outlines use thin elongated cuboid boxes in the spirit of Create blueprints and super glue, with adjustable thickness (`staff_outline_thickness`); when the pointed body is part of the queue the whole group's outline is thickened (`staff_outline_bold_scale`).
- Sub-level outlines have a "contour only" mode that draws just the exposed edges (with a fixed seam bug that used to draw an extra inward ring), plus always-visible / focused-only variants and an animated three-colour axis gizmo.
- The top-left HUD shows only what is currently relevant: mode, region-select step and distance, group control, view lock, penetration depth, selection count, the name and owner of the body under the crosshair, and the current scale.

## Configuration

`config/sablestopnow-common.toml`, or the in-game screen (**Ctrl+O**, or the mod list's *Config* button):

- `[force_limiter]` — force threshold, excluded groups, auto-pause, split confirmation, speed auto-lock, outlines/axes and more.
- `[staff_enhance]` — master switch, outline thickness, scale sensitivity/limits and the two ghost-collision options.

Keys are no longer GLFW codes: they live in the vanilla Controls screen, so rebinding takes effect immediately.

## Commands

- `/sablesn forces [page]`, `/sablesn forces filtered [page]`
- `/sablesn confirm`, `/sablesn deny`
- `/sablesn tick <steps>` (permission level 2)
- `/sablesn owner list | clear <player> | clear all` (permission level 2)

## Requirements

- Minecraft **1.21.1** · NeoForge **21.1.248**
- **Sable 2.0.4**
- **Simulated / Aeronautics 1.3.1** (bundled) — provides the physics staff & lock mechanics
- Create 6.0.10 (optional, Sable ecosystem)

## Highlights

- No registry additions — everything is additive behaviour on Sable/Simulated via events and Mixins.
- Full bilingual (EN/CN) prompts, in-game HUD hints and a custom animated settings screen.
- Server-authoritative selection, ownership, snapshot and no-collision state, synced to clients.

## Known limitations

- Scaling is a **visual + entity-collision** feature: Rapier collider size and mass do not follow the scale, so the physics of a rescaled body is still computed at its original size. Sable's serializer drops `scale`, so the mod persists it itself.
- Ghosting only removes collision — Sable's tracking still carries players standing on a ghosted body.
- The no-collision marker cannot affect terrain (Sable has no per-body "vs world" filter).
- Snapshots are not written to disk; restoring rebuilds the body (a brief respawn flicker).
- Group control drives members with physics motors; very large or fast structures may wobble slightly (stiffness/damping constants in `server/StaffEnhanceServer`).
