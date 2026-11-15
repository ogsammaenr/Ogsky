# Ogsky Island & World Specification (`docs/island-spec.md`)

This document defines the canonical domain rules for `ogsky-core` islands, grid placement, dimensions, `.nbt` blueprint lazy loading, roles, protection flags, and RAM/SQLite layout. Every module and agent MUST conform to this specification.

See also: [Flags & Domain Events Reference](flags-and-events.md), [Architecture](architecture.md), and [Roadmap](roadmap.md).

---

## 1. Dimensions & Lazy Nether/End Generation (Single Island Across 3 Worlds)

- Islands live in dedicated custom dimensions, keeping vanilla `minecraft:overworld` free for server spawn/lobby:
  - `ogsky:overworld`
  - `ogsky:nether`
  - `ogsky:end`
- **Single `Island` Object Invariant:**
  - There is **one** `Island` record per island in RAM and `core.db` shared across **all three dimensions** (`ogsky:overworld`, `ogsky:nether`, `ogsky:end`).
  - Never create per-dimension `Island` copies.
  - All three dimensions share the exact **1:1** `(centerX, centerZ)` coordinate column, boundary `halfSize`, member roles, and protection settings (no 1:8 Nether coordinate scaling).
  - Portal transitions between `ogsky:overworld ↔ ogsky:nether ↔ ogsky:end` route within the same island's `1:1` coordinate column rather than vanilla dimensions.
- **Lazy Dimension Initialization (`nether` & `end`):**
  - When an island is created (or reset), **only the Overworld structure (`overworld.nbt`) is pasted eagerly**.
  - **Nether (`nether.nbt`) and End (`end.nbt`) structures are loaded lazily** on the first valid portal transition or teleport into `ogsky:nether` / `ogsky:end` for that island.
  - Per-dimension initialization flags (`DIMENSION_OVERWORLD_READY`, `DIMENSION_NETHER_READY`, `DIMENSION_END_READY`) live inside the single `Island`'s bitmask in RAM and persist in `core.db` so lazy pasting runs at most once per dimension (and resets on island reset).
  - If a blueprint bundle omits `nether.nbt` or `end.nbt` (or lazy dimension pasting is disabled for a world), fallback portal/safe-platform handling is governed by `core` world config.
- **Dimension Compatibility (Server-Only):**
  - Dimension types and biomes must use vanilla/datapack-compatible definitions so unmodded vanilla clients can enter `ogsky:*` dimensions without a client mod.

---

## 2. Grid Placement & Boundaries

- Islands are allocated on a regular 2D grid (chunk/region aligned), allocated outward from `(0, 0)` in spiral order (with optional slot reuse only after full chunk cleanup).
- **Config-Driven Parameters (`config/ogsky/core.json`):**
  - `grid.cellSize`: default `1024` blocks (must be a positive multiple of `16`, recommended multiple of `512`).
  - `island.initialHalfSize`: default `50` blocks (`101 × 101` total square centered on `(centerX, centerZ)`).
  - `island.maxHalfSize`: configurable ceiling strictly `< cellSize / 2` so islands always retain an inter-island buffer zone.
- **World-Lock Invariant for `cellSize`:**
  - Once a world creates its first island, `cellSize` is persisted in `core.db` metadata (`world_meta`).
  - Subsequent server startups must validate config `cellSize` against the locked `core.db` value (fail loudly or keep locked value) so existing islands never shift coordinates.
- **O(1) Coordinate Lookup:**
  - `(x, z)` maps to `(gridX, gridZ)` via `Math.floorDiv(coord, cellSize)`, looking up the single `Island` from a packed `(long) gridX << 32 | (gridZ & 0xFFFFFFFFL)` RAM index in `O(1)` time, then checking `Math.abs(x - centerX) <= halfSize && Math.abs(z - centerZ) <= halfSize`.

---

## 3. `.nbt` Blueprint Bundles & Tick-Budgeted Paster

Inspired by BentoBox/BSkyBlock's `BlueprintBundle` + `BlueprintPaster`:

- **Storage Format:**
  - Starter islands use vanilla Minecraft `.nbt` structure files (`StructureTemplate`) stored under `config/ogsky/blueprints/<bundleId>/`:
    - `bundle.json`: metadata (display name, icon item ID, permission requirement, default biome overrides, relative spawn points per dimension or `[spawn]` anchor markers, `blocksPerTick` / budget hint).
    - `overworld.nbt` *(required)*
    - `nether.nbt` *(optional; pasted lazily on first Nether entry)*
    - `end.nbt` *(optional; pasted lazily on first End entry)*
- **Async Load + Tick-Budgeted Placement:**
  - `.nbt` files are read/parsed off the main tick thread (cached in RAM by bundle ID) and placed into target chunks on the server thread via a **tick-budgeted paste queue** (capping blocks placed or nanoseconds spent per tick) so simultaneous island creations never spike server MSPT.
  - While an island paste is in progress, the owner is notified and teleported once the Overworld spawn spot is placed and validated as safe.
  - Island reset / deletion similarly schedules chunk cleanup with bounded per-tick budgets before releasing a grid slot for reuse.

---

## 4. Roles (`byte 0..5`) & Membership Limits

Roles are stored strictly as `byte` values `0..5` in memory and SQLite, wrapped by `IslandRole` constants, with configurable display names in `config/ogsky/core.json`.

| Byte | Default Key | Default Name (Configurable) | Scope |
| :--- | :--- | :--- | :--- |
| `0` | `VISITOR` | Ziyaretçi / Visitor | Default for non-members (and wilderness fallback context) |
| `1` | `COOP` | Coop | Secondary cross-island temporary/session or limited helper |
| `2` | `TRUSTED` | Güvenilir / Trusted | Secondary cross-island trusted helper |
| `3` | `MEMBER` | Üye / Member | Primary island membership |
| `4` | `ADMIN` | Yönetici / Admin | Primary island management |
| `5` | `OWNER` | Sahip / Owner | Primary island owner (transferable) |

### Membership Rules (Enforced by `core`)

1. **Single Primary Island Rule (`role >= 3`):**
   - A player can belong to **at most 1 primary island** (`MEMBER`, `ADMIN`, or `OWNER`) at a time — either their own island (`5`) or a friend's island (`3` or `4`).
   - While a player holds a primary role (`3`, `4`, or `5`) on any island, they **cannot create** a new island or join another island as a primary member until they leave/disband their current primary island.
2. **Secondary Helper Roles (`role == 1` or `2`):**
   - Independently of their primary island, a player may hold secondary helper roles on **other** players' islands.
   - Default configurable caps per player (in `config/ogsky/core.json`):
     - Max `COOP` (`1`) islands per player: **`1`**
     - Max `TRUSTED` (`2`) islands per player: **`2`**
   - A player holds at most one role entry per island (never simultaneously primary and secondary on the same island).
   - Banned player UUIDs are tracked separately from active roles (`banned` set/array on `Island`).

---

## 5. 3-Tier Flag System (BentoBox Model)

Adapted directly from BentoBox's `Flag.Type` architecture (`PROTECTION`, `SETTING`, `WORLD_SETTING`) with compact primitive storage:

1. **`PROTECTION` Flags (Per-Island Rank Threshold, `byte 0..5`):**
   - Every protection flag stores the **minimum `IslandRole` (`byte 0..5`)** needed to perform that action inside the island boundaries (plus an optional `6` = `DISABLED` sentinel if completely blocked even for Owner when configured).
   - Outside any island boundary (inter-island wilderness buffer in `ogsky:*`), protection flags fall back to global wilderness defaults in `config/ogsky/core.json`.
   - Staff admin bypass permissions can override protection checks per-flag or globally.
2. **`SETTING` Flags (Per-Island Boolean Toggle):**
   - Island-level `boolean` switches toggled by Owner/Admin (e.g., PvP per dimension, mob spawning, fire spread, leaf decay, enter/exit titles, island lock).
3. **`WORLD_SETTING` Flags (Admin Global Rule):**
   - Dimension/world-wide admin switches configured in `config/ogsky/core.json` and admin commands (e.g., creeper block griefing, TNT block damage, void falling rescue/teleport, offline redstone throttling).

> Full list of built-in flags, defaults, and `FlagRegistry` SPI for optional modules: see [`docs/flags-and-events.md`](flags-and-events.md).

---

## 6. RAM-First Compact Data Model & Persistence

- **Hot Path = RAM, Cold Store = `core.db` (SQLite):**
  - All active islands are loaded from `core.db` into RAM during `SERVER_STARTING`.
  - Protection checks, spatial lookups, flag checks, and role checks read exclusively from RAM with zero SQLite queries on the server tick thread.
  - Mutations update RAM atomically on the server thread and enqueue async writes to `core.db` (`executeAsync`), flushed cleanly on `SERVER_STOPPED`.
- **Compact Primitive Layout (Avoid Boxing & Heavy Object Graphs):**
  - Island core fields:
    - `UUID islandId` (shared cross-module foreign key)
    - `int gridX, gridZ`, `int centerX, centerZ`
    - `short halfSize` (clamped to `maxHalfSize`)
    - **Relative spawn offsets** from `(centerX, 0, centerZ)` instead of heavy global `Vec3` objects (`short relSpawnX, relSpawnY, relSpawnZ`, quantized `byte yaw, pitch` for Overworld + optional packed lazy Nether/End spawn offsets on the same `Island` object).
    - **State & `SETTING` flags bitmask (`long stateAndSettingsMask`)**: stores both lifecycle state bits (`overworldReady`, `netherReady`, `endReady`, `pasting`, `locked`) and per-island boolean `SETTING` flag bits indexed by `FlagRegistry` slot!
    - **`PROTECTION` flag rank table (`byte[] protectionRanks`)**: compact fixed-length byte array indexed by `FlagRegistry` protection slot (`0..5`, initialized from global defaults; eliminates `HashMap<Flag, Byte>` boxing overhead).
    - **Members stored without `Byte` boxing:** parallel primitive arrays `long[] memberUuidHi`, `long[] memberUuidLo`, `byte[] memberRoles` (small contiguous arrays scanned in L1 cache without per-member `HashMap<UUID, Byte>` nodes), plus compact `long[] bannedUuidHi/Lo`.
  - Player reverse index in RAM:
    - `UUID primaryIslandId` (nullable single slot) + bounded array for secondary `COOP`/`TRUSTED` memberships.
