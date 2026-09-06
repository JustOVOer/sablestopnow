# SableStopNow

A practical NeoForge addon for **Create: Aeronautics / Simulated** and the **Sable** physics engine (Minecraft 1.21.1).

---

## What it does

SableStopNow gives you two toolkits on top of Sable's sub-level physics:

### 1. Force monitor & limiter
Protect your physics builds from excessive forces:

- Filter out forces above a configurable threshold (`[force_limiter].threshold`), with group allow-lists (`excluded_groups`, e.g. let `sable:gravity` pass).
- Optional auto-pause when a force is filtered, with a server-wide broadcast and a clickable `/sablesn forces` hint.
- Paginated force history: `/sablesn forces [page]` and `/sablesn forces filtered [page]`; click a sub-level ID to fill in a `/tp` command.
- Split confirmation (`/sablesn confirm` / `/sablesn deny`) before a heat-map driven split.
- Paused-physics stepping: `/sablesn tick <steps>` steps the specified number of physics ticks while paused, then restores the pause automatically.
- Speed auto-lock: bodies faster than `speed_limit_threshold` are locked automatically and announced in chat with a **clickable teleport suggestion**; bodies that are currently being dragged (single or group) are never falsely locked.
- Utility toggles: auto-lock freshly assembled sub-levels, disable block-placement collision checks, render surface outlines/axes.

### 2. Physics Staff Enhancements
Upgrade the Aeronautics **physics staff** into a practical build tool (master switch: `[staff_enhance].enable_staff_enhance`):

- **Multi-select** (Ctrl): queue physics bodies with Right-click, remove them with Shift+Right-click, pick through layers with Alt+Scroll (penetration depth 0–16).
- **Box select** (Z): mark two corners by walking between two presses; everything inside is queued.
- **Smart lock toggle** (Left-click): all queued bodies locked? Unlock them — otherwise lock them all first.
- **Group control**: after leaving multi-select, Right-click a queued body to unlock all and enter group control:
  - keeps the group’s position relative to your view (no teleport on start),
  - move it by turning your view or walking,
  - Scroll to scale the distance along the eye→centroid line,
  - hold **C** to slowly ease the centroid into the view center,
  - hold **TAB** and move the mouse to rotate the whole group about its centroid,
  - Right-click again to leave group control.
- **No-collision marker** (V): apply/remove it to the whole selection queue. With `[staff_enhance].ghost_real` enabled it truly stops the marked bodies from colliding with **other Sable bodies** (terrain/players still collide), using transient contact-disabled joints that are cleaned up when bodies move apart.
- Clear visual feedback: uniform cyan contour-only outlines, ring icons, hover preview, and an on-screen HUD showing multi-select state, penetration depth, selection count and box-select corner coordinates.

## Configuration

`config/sablestopnow-common.toml`

- `[force_limiter]` — force threshold, excluded groups, auto-pause, split confirmation, **speed auto-lock (`speed_limit_enabled` / `speed_limit_threshold`)**, outlines/axes and more.
- `[staff_enhance]` — master switch, GLFW key codes and sensitivities:

| Setting | Default | Meaning |
|---|---|---|
| `enable_staff_enhance` | false | master switch |
| `key_multi_select` | 341 (Ctrl) | multi-select toggle |
| `key_box_select` | 90 (Z) | box select |
| `key_collision_toggle` | 86 (V) | no-collision marker |
| `key_center_pull` | 67 (C) | ease centroid to view center |
| `rotate_sensitivity` | 0.35 | TAB rotation sensitivity |
| `scroll_sensitivity` | 0.6 | scroll sensitivity |
| `center_pull_speed` | 0.06 | C ease speed per tick |
| `ghost_real` | false | real no-collision vs other Sable bodies (experimental) |

> TAB reuses Aeronautics' “Physics Staff Rotate Mode” binding; other keys can be edited in the toml and take effect after a restart.

## Commands

- `/sablesn forces [page]`
- `/sablesn forces filtered [page]`
- `/sablesn confirm`
- `/sablesn deny`
- `/sablesn tick <steps>` (permission level 2)

## Requirements

- Minecraft **1.21.1** · NeoForge **21.1.248**
- **Sable 2.0.4**
- **Simulated / Aeronautics 1.3.1** (bundled) — provides the physics staff & lock/ghost mechanics
- Create 6.0.10 (optional, Sable ecosystem)

## Highlights

- No registry additions — everything is additive behaviour on Sable/Simulated via events and Mixins.
- Full bilingual (EN/CN) prompts and in-game HUD hints.
- Configurable keys and sensitivities, safe fallbacks during early startup.
- Server-authoritative lock/no-collision state, synced to clients.

## Known limitations

- The no-collision marker cannot affect terrain or players (Sable has no per-body “vs world” filter).
- Group control drives members with physics motors; very large or fast structures may wobble slightly (stiffness/damping constants in `server/StaffEnhanceServer`).
