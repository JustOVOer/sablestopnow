# SableStopNow

> **中文说明：** [README.md](README.md)

A practical NeoForge mod (MC **1.21.1**) for **Create: Aeronautics / Simulated** + the **Sable** physics engine.

Two feature groups:

1. **Force monitor & limiter** (original) — threshold filtering, recording and optional auto-pause for forces applied to Sable sub-levels.
2. **Physics Staff Enhancements** (new in **v1.0.2**) — multi-select, box select, penetration picking, and whole-group control (move / rotate / lock) on top of the Aeronautics physics staff.

> Detailed key/config reference: [docs/staff-enhance-usage.md](docs/staff-enhance-usage.md) (CN).

---

## Features

### Force monitor & limiter (`/sablesn`)

- Discard forces above the configured `threshold`; `excluded_groups` lets specific force groups pass (e.g. `sable:gravity`).
- Optional auto-pause on filter with a server-wide broadcast + a clickable `/sablesn forces` hint.
- `/sablesn forces [page]` and `/sablesn forces filtered [page]` list force history; clicking a sub-level id fills in a `/tp` command.
- Split confirmation (`/sablesn confirm` / `/sablesn deny`) before a heat-map driven split.
- Utility toggles: auto-lock newly assembled sub-levels, disable block-placement collision checks, render sub-level surface outlines / axes.

### Physics Staff Enhancements (master switch `[staff_enhance].enable_staff_enhance`)

| Input | Effect |
|---|---|
| Hold staff + **Ctrl** | Enter / leave multi-select (staff RMB drag / LMB lock are suppressed while inside) |
| Multi-select **RMB / Shift+RMB** | Add / remove the pointed body from the selection queue |
| Multi-select **Alt+Scroll** | Penetration depth (0–16) |
| Multi-select **Z** | Box select: both corners are marked at the **player's own block** (walk between the two presses), everything inside is queued |
| After multi-select **Shift+RMB** | Clear the selection queue |
| After multi-select **LMB** | Smart lock toggle: all locked → unlock all; otherwise (including partially locked) → lock all first |
| After multi-select **RMB** a group member | Unlock all queued bodies, then start **group control** |
| Group control: **turn view / walk** | Group follows relative to your view (no initial teleport) |
| Group control: **Scroll** | Scale group distance along the eye→centroid line |
| Group control: **hold C** | Slowly ease the centroid to the view center |
| Group control: **TAB + mouse** | Rotate the whole group about its centroid |
| Outside multi-select **V** | Toggle a "no-collision" marker (visual + saved state only; Sable has no runtime ghost API yet) |

Selected / hovered bodies are outlined in a uniform cyan “contour only” style with a ring icon; every action shows a bilingual hint.

## Keys & Configuration

Config file: `.minecraft/config/sablestopnow-common.toml`

- `[force_limiter]` — threshold, excluded groups, auto-pause, lock new bodies, placement collision, split confirmation, outlines/axes.
- `[staff_enhance]` — master switch, GLFW key codes and sensitivities:

| Key / value | Default | Meaning |
|---|---|---|
| `enable_staff_enhance` | false | master switch |
| `key_multi_select` | 341 (Ctrl) | multi-select toggle |
| `key_box_select` | 90 (Z) | box select |
| `key_collision_toggle` | 86 (V) | no-collision marker |
| `key_center_pull` | 67 (C) | ease centroid to view center |
| `rotate_sensitivity` | 0.35 | TAB rotation sensitivity |
| `scroll_sensitivity` | 0.6 | scroll sensitivity |
| `center_pull_speed` | 0.06 | C ease speed per tick |

> TAB reuses Aeronautics' “Physics Staff Rotate Mode” key binding (change it in Controls). Other keys are edited in the toml and applied after a restart.

## Commands

- `/sablesn forces [page]`, `/sablesn forces filtered [page]`
- `/sablesn confirm`, `/sablesn deny`

## Dependencies

- Minecraft **1.21.1**, NeoForge **21.1.248**
- **Sable 2.0.4** (physics)
- **Simulated / Aeronautics 1.3.1** (bundled; provides the physics staff & locks)
- Create 6.0.10 (Sable ecosystem, optional)

## Building

```bash
gradlew.bat build        # jar in build/libs/sablestopnow-<version>.jar
gradlew.bat runClient    # dev client
```

## License

See `mod_license` in `gradle.properties` (default All Rights Reserved). `TEMPLATE_LICENSE.txt` is an MIT template — declare the license you intend before publishing.

## Known limitations

- The no-collision marker is a visual/saved-state placeholder (Sable has no per-body runtime ghost API).
- Group control drives each member with physics motors; very large / fast structures may wobble slightly (server stiffness/damping constants live in `server/StaffEnhanceServer`).
