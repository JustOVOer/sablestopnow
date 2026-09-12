# Aeronautics: Tweaks & Toolkit (`sablestopnow`)

> **中文说明：** [README.md](README.md) ｜ **Changelog:** [CHANGELOG_EN.md](CHANGELOG_EN.md) ([中文](CHANGELOG.md))

A practical NeoForge mod (MC **1.21.1**) for **Create: Aeronautics / Simulated** + the **Sable** physics engine. The display name is now *Aeronautics: Tweaks & Toolkit*; the mod id and package stay `sablestopnow` (save-compatible).

Two feature groups:

1. **Force monitor & limiter** (original) — threshold filtering, recording and optional auto-pause for forces applied to Sable sub-levels.
2. **Physics Staff Enhancements** — turn the Aeronautics physics staff into a full build tool: per-player multi-select, ownership, region select, view lock, snapshots, group scaling and ghosting.

> Detailed key/config reference: [docs/staff-enhance-usage.md](docs/staff-enhance-usage.md) (CN).
> Implementation notes & pitfalls: [docs/staff-enhance-design.md](docs/staff-enhance-design.md) (CN).
> English mod-page blurb: [DESCRIPTION.md](DESCRIPTION.md).

---

## Features

### Force monitor & limiter (`/sablesn`)

- Discard forces above the configured `threshold`; `excluded_groups` lets specific force groups pass (e.g. `sable:gravity`).
- Optional auto-pause on filter with a server-wide broadcast + a clickable `/sablesn forces` hint.
- `/sablesn forces [page]` and `/sablesn forces filtered [page]` list force history; clicking a sub-level id fills in a `/tp` command.
- Split confirmation (`/sablesn confirm` / `/sablesn deny`) before a heat-map driven split.
- Paused-physics stepping: `/sablesn tick <steps>` steps the specified number of physics ticks while paused, then restores the pause automatically.
- Speed auto-lock: bodies faster than `speed_limit_threshold` are locked automatically and announced in chat with a **clickable teleport suggestion**; bodies that are currently dragged or being rescaled are never falsely locked.
- Utility toggles: auto-lock newly assembled sub-levels, disable block-placement collision checks, render sub-level surface outlines / axes.

### Physics Staff Enhancements (master switch `[staff_enhance].enable_staff_enhance`)

**Every binding is a real Minecraft `KeyMapping`** — rebind them any time in **Options → Controls → Aeronautics: Tweaks & Toolkit**, no restart needed:

| Key | Default | Action |
|---|---|---|
| Multi-select | Ctrl | Enter / leave multi-select |
| Region select | Z | Region select (scroll = distance, view = direction, RMB = corner) |
| Whole-group no-collision | V | Toggle the no-collision marker for the whole queue |
| Group centering / view lock | C | Ease the centroid to the view centre — or hold to lock your view onto the nearest body |
| Ownership | O | Claim / release the queue |
| Snapshot | K | Create / cancel a snapshot |
| Restore | R | Roll the snapshot back |
| Scale | X | Hold X + scroll to scale the queue |
| Penetration modifier | Alt | Alt + scroll picks through layers (**always available**) |
| Open settings | Ctrl+O | Open this mod's settings screen (no staff needed) |

Highlights:

- **Per-player selections**: each player owns an independent queue, and a body can only be claimed by one player at a time — bodies already claimed or owned by someone else are refused with a chat hint naming them. Outline colours come from a 12-colour per-player palette, and other players' selections render only within 64 blocks.
- **Ownership**: press O to claim/release; it is written to the save, and the owner is shown on the HUD when you look at a body. OPs get `/sablesn owner`.
- **Region select**: press Z, set the distance with the scroll wheel and the direction with your view (a one-block preview box before the first corner); RMB confirms the first corner and the bodies that *would* be selected are outlined live in yellow-green; RMB again selects everything intersecting the box.
- **View lock**: outside multi-select/group control, holding C re-picks the body with the smallest angle to your view every tick and locks your view onto it; release to unlock.
- **Snapshots**: K stores, R restores — blocks, position, orientation, velocity and name all come back. Snapshots are per player.
- **Group scaling**: X + scroll scales the whole queue about its **common centroid**, so shapes stay intact; the HUD shows the current factor and the factor of the body you are looking at.
- **Ghosting**: a body being dragged no longer collides with the player dragging it, and a body with scale ≠ 1 no longer collides with any player (both switchable in the settings screen).
- **Group control**: after leaving multi-select, RMB a queued body — turn your view or walk to move the group, scroll to change the distance, hold C to centre it, TAB + mouse to rotate, LMB to toggle locking, V to toggle no-collision, RMB to finish.
- **Outlines & HUD**: selection/hover use Create-blueprint-style thin cuboid boxes (adjustable thickness; the whole group's outline thickens when the pointed body is a member), and the HUD only shows what is relevant right now (mode, penetration, selection count, region progress, view lock, body name/owner, scale).

## Keys & Configuration

Config file: `.minecraft/config/sablestopnow-common.toml`, or the in-game screen (**Ctrl+O**); every change is written straight back to the file.

- `[force_limiter]` — threshold, excluded groups, auto-pause, lock new bodies, placement collision, split confirmation, speed auto-lock (`speed_limit_enabled` / `speed_limit_threshold`), outlines/axes.
- `[staff_enhance]` — master switch, outline thickness, scale sensitivity/limits and the ghosting options (keys live in the vanilla Controls screen).

| Setting | Default | Meaning |
|---|---|---|
| `enable_staff_enhance` | false | master switch |
| `staff_outline_thickness` | 0.06 | selection/hover outline thickness |
| `staff_outline_bold_scale` | 2.2 | bold multiplier when a group member is pointed at |
| `scale_sensitivity` | 0.08 | X + scroll scaling sensitivity |
| `scale_min` / `scale_max` | 0.2 / 8.0 | scaling range |
| `dragged_no_player_collision` | true | dragged body vs. the dragging player |
| `scaled_no_player_collision` | true | rescaled body vs. every player |
| `ghost_real` | false | make V no-collision real (vs OTHER Sable bodies; experimental) |

## Commands

- `/sablesn forces [page]`, `/sablesn forces filtered [page]`
- `/sablesn confirm`, `/sablesn deny`
- `/sablesn tick <steps>` (permission level 2)
- `/sablesn owner list | clear <player> | clear all` (permission level 2)

## Dependencies

- Minecraft **1.21.1**, NeoForge **21.1.248**
- **Sable 2.0.4** (physics)
- **Simulated / Aeronautics 1.3.1** (bundled; provides the physics staff & locks)
- Create 6.0.10 (Sable ecosystem, optional)

## Building

```bash
gradlew.bat build        # jar in build/libs/aeronautics-tweaks-toolkit-1.1.0.jar
gradlew.bat runClient    # dev client
```

## License

See `mod_license` in `gradle.properties`. `TEMPLATE_LICENSE.txt` is an MIT template — declare the license you intend before publishing.

## Known limitations

- **Scaling is a visual + entity-collision feature**: Rapier collider size and mass do not follow the scale, so the physics of a rescaled body is still computed at its original size. Sable's serializer drops `scale`, so the mod persists it itself.
- Ghosting only removes collision — Sable's tracking still carries players standing on a ghosted body.
- The no-collision marker is a visual/saved-state placeholder by default; with `ghost_real` enabled it is real, but only vs **other Sable bodies** (terrain and players still collide).
- Snapshots are not written to disk; restoring rebuilds the body (a brief respawn flicker).
- Group control drives each member with physics motors; very large / fast structures may wobble slightly (server stiffness/damping constants live in `server/StaffEnhanceServer`).
