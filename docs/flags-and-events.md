# Ogsky Flags, Protections, Commands & Domain Events (`docs/flags-and-events.md`)

This document specifies how **BentoBox / BSkyBlock**'s protection engine, 3-tier flags, domain events, and extensible command tree integrate into **`ogsky-core`** (`me.ogsammaenr.ogsky.core.api.*`).

> See also: [Core Public API Reference](core-api.md), [Modules & Configuration Guide](modules.md), [Architecture Overview](architecture.md), and [Minecraft 26.3 Source Verification](island-spec.md).

---

## 1. 3-Tier Flag Architecture (`FlagRegistry`)

Ogsky adopts BentoBox's 3-category `Flag.Type` pattern while storing values in RAM using sequential slot indices rather than boxed `HashMap<Flag, Integer>` maps.

### Flag Contract (`OgskyCoreApi.flags()`)

- `IslandFlagDefinition` registered inside `OgskyModule.onLoad(OgskyCoreApi)` via `OgskyCore.api().flags()` ([`FlagRegistry`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/flag/FlagRegistry.java)):
  - `Identifier id` (e.g., `ogsky:break_blocks`, `ogsky:pvp_overworld`, or `ogsky_limits:view_limits`)
  - `FlagType type`: `PROTECTION`, `SETTING`, `WORLD_SETTING`
  - `FlagMode mode`: `BASIC` | `ADVANCED` *(hint for `ogsky-panel` UI grouping/filtering, adapted from BentoBox `Flag.Mode`)*
  - `String iconItemId`: vanilla item identifier for UI icons (e.g., `"minecraft:iron_pickaxe"`)
  - `int sortOrder` *(used for deterministic slot ordering alongside `id.toString()`)*
  - Default values:
    - For `PROTECTION`: default minimum `byte` rank (`0..6`, see `IslandRole`) + `boolean wildernessAllowed`
    - For `SETTING`: default `boolean` *(stored in a compact 64-bit `long` mask; max `64` `SETTING` flags total across all bundled modules)*
    - For `WORLD_SETTING`: default `boolean` *(stored globally in `world_settings` SQLite table + `config/ogsky/core.json` defaults, editable by admins via `/isadmin worldflag`)*

### Deterministic Freeze & Slot Allocation

- After all `"ogsky-module"` entrypoints finish loading during `OgskyCoreMod.onInitialize()`, `OgskyCoreApiImpl.freezeRegistries()` calls `FlagRegistry.freeze()`, locking further flag registration and assigning deterministic per-`FlagType` contiguous slot indices `0..count(type)-1`.
- SQLite tables (`island_protection_flags`, `island_setting_flags`, `world_settings`) key persisted overrides by string `flag_id` (`Identifier`), so enabling or removing optional modules never corrupts existing islands' overrides.

---

## 2. Built-In BentoBox-Aligned Flag Catalog

### A. `PROTECTION` Flags (Minimum `IslandRole` byte `0..6`)

Defaults verified against [`BuiltinFlags`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/internal/flag/BuiltinFlags.java) (overridable in `config/ogsky/core.json` under `flags.protectionDefaults` and `flags.wildernessAllow`):

| Flag ID (`ogsky:*`) | Mode | Default Min Rank | Wilderness Allowed | Covers / Guarded Interactions |
| :--- | :--- | :--- | :--- | :--- |
| `break_blocks` | `BASIC` | `MEMBER (3)` | `false` | General block breaking (specialized overrides below check stricter requirement) |
| `place_blocks` | `BASIC` | `MEMBER (3)` | `false` | General block placing inside island boundaries |
| `container` | `BASIC` | `MEMBER (3)` | `false` | Chests, trapped chests, barrels, shulker boxes, dispensers, droppers, crafters, decorated pots, chiseled bookshelves |
| `furnace` | `BASIC` | `MEMBER (3)` | `false` | Furnaces, blast furnaces, smokers |
| `hopper_use` | `ADVANCED` | `MEMBER (3)` | `false` | Opening hopper inventories & modifying hopper state |
| `break_hoppers` | `ADVANCED` | `MEMBER (3)` | `false` | Breaking hoppers (checked alongside `break_blocks`; stricter rank applies) |
| `break_spawners` | `ADVANCED` | `ADMIN (4)` | `false` | Breaking spawners / trial spawners (checked alongside `break_blocks`; stricter rank applies) |
| `door` | `BASIC` | `COOP (1)` | `false` | Doors & fence gates |
| `trapdoor` | `BASIC` | `COOP (1)` | `false` | Trapdoors |
| `button_lever` | `BASIC` | `COOP (1)` | `false` | Buttons & levers |
| `pressure_plate` | `BASIC` | `VISITOR (0)` | `false` | Player/entity pressure plates & tripwires |
| `redstone` | `ADVANCED` | `MEMBER (3)` | `false` | Repeaters, comparators, daylight detectors, note blocks, copper bulbs |
| `bed` | `BASIC` | `MEMBER (3)` | `false` | Sleeping / interacting with beds and respawn anchors |
| `anvil` | `BASIC` | `COOP (1)` | `false` | Anvil interaction & damaging |
| `beacon` | `ADVANCED` | `MEMBER (3)` | `false` | Beacon GUI & effect selection |
| `brewing` | `ADVANCED` | `MEMBER (3)` | `false` | Brewing stands & cauldrons |
| `enchanting` | `BASIC` | `VISITOR (0)` | `false` | Enchanting tables, grindstones, stonecutters, looms, smithing tables, bells, jukeboxes, lecterns, composters |
| `armor_stand` | `ADVANCED` | `MEMBER (3)` | `false` | Placing, breaking, equipping/removing armor stands |
| `item_frame` | `ADVANCED` | `MEMBER (3)` | `false` | Placing, breaking, rotating items in (glow) item frames & paintings |
| `bucket` | `BASIC` | `MEMBER (3)` | `false` | Filling/emptying water, lava, milk, powder snow, or mob buckets |
| `crop_trample` | `BASIC` | `MEMBER (3)` | `false` | Farmland trampling protection |
| `harvest` | `BASIC` | `COOP (1)` | `false` | Harvesting/replanting crops, sweet berries, cave vines, cocoa |
| `breeding` | `BASIC` | `MEMBER (3)` | `false` | Breeding animals / feeding livestock |
| `shear_entity` | `BASIC` | `COOP (1)` | `false` | Shearing sheep, mooshrooms, snow golems |
| `hurt_animals` | `BASIC` | `MEMBER (3)` | `false` | Attacking passive mobs (animals, fish, ambient mobs, golems) |
| `hurt_villagers` | `ADVANCED` | `ADMIN (4)` | `false` | Attacking villagers & wandering traders |
| `hurt_monsters` | `BASIC` | `VISITOR (0)` | `true` | Attacking hostile mobs inside island bounds |
| `trading` | `BASIC` | `VISITOR (0)` | `true` | Trading with villagers / wandering traders |
| `mount_entity` | `BASIC` | `COOP (1)` | `false` | Riding mounts, boats, minecarts & leash usage |
| `ender_pearl` | `ADVANCED` | `COOP (1)` | `false` | Throwing ender pearls or chorus fruit teleportation onto/inside island |
| `drop_item` | `BASIC` | `VISITOR (0)` | `true` | Dropping items on an island |
| `pickup_item` | `BASIC` | `VISITOR (0)` | `true` | Picking up item drops on an island |
| `portal_use` | `BASIC` | `VISITOR (0)` | `true` | Entering island Nether/End portals |
| `flint_and_steel` | `ADVANCED` | `MEMBER (3)` | `false` | Igniting blocks/TNT/portals with flint & steel or fire charges |

#### Optional Module Flags

| Flag ID | Registered By | Type / Mode | Default | Covers |
| :--- | :--- | :--- | :--- | :--- |
| `ogsky_limits:view_limits` | `ogsky-limits` | `PROTECTION` / `BASIC` | Config `defaultViewLimitsRank` (`MEMBER (3)`) | Controls who may view island block/entity limits via `/is limits`, summary line, or GUI screen |

### B. `SETTING` Flags (Per-Island Boolean Toggles)

| Flag ID (`ogsky:*`) | Default | Behavior |
| :--- | :--- | :--- |
| `island_lock` | `false` | Blocks non-members (`VISITOR`) from entering/staying inside island bounds |
| `pvp_overworld` | `false` | Enables/disables PvP inside this island in `ogsky:overworld` |
| `pvp_nether` | `false` | Enables/disables PvP inside this island in `ogsky:nether` |
| `pvp_end` | `false` | Enables/disables PvP inside this island in `ogsky:end` |
| `animal_spawn` | `true` | Allows natural passive mob spawning on this island |
| `monster_spawn` | `true` | Allows natural hostile mob spawning on this island |
| `fire_spread` | `false` | Allows fire to spread to adjacent blocks inside this island |
| `leaf_decay` | `true` | Allows leaves to decay naturally inside this island |
| `enter_exit_messages` | `true` | Shows entry/exit notifications to players crossing island boundaries |
| `offline_redstone` | `true` | Controls whether redstone clocks/circuits run while island members are offline *(configurable cap)* |

### C. `WORLD_SETTING` Flags (Server-Admin Global Rules)

| Flag ID (`ogsky:*`) | Default | Behavior |
| :--- | :--- | :--- |
| `creeper_damage` | `false` | Whether creepers damage entities/knockback in `ogsky:*` |
| `creeper_griefing` | `false` | Whether creepers destroy island blocks in `ogsky:*` |
| `tnt_damage` | `false` | Whether primed TNT / TNT minecarts break island blocks |
| `wither_damage` | `false` | Whether Wither / Ender Dragon grief blocks outside authorized boss fights |
| `chest_damage` | `false` | Whether container blocks (`container`) are protected from explosions even if explosion damage is on |
| `piston_outside_bounds` | `false` | Prevents pistons pushing/pulling blocks across island boundaries |
| `liquid_flow_outside_bounds` | `false` | Stops water/lava from flowing outside an island's `halfSize` perimeter |
| `void_teleport` | `true` | Teleports falling players safely (configurable: island home or server spawn) instead of void death |
| `end_dragon_respawn` | `false` | Disables vanilla Ender Dragon center-island pillar logic in `ogsky:end` |

---

## 3. Core Protection Enforcement Pipeline

1. Locate position `(x, z)` in `ogsky:overworld | ogsky:nether | ogsky:end`:
   - Lookup `Island` in `O(1)` via RAM `IslandGrid` + check `abs(x - centerX) <= halfSize && abs(z - centerZ) <= halfSize`.
2. If outside any island boundary (**wilderness buffer**):
   - Apply global wilderness policy (by default deny block modification/griefing by non-admin players).
3. If inside an `Island`:
   - Check admin bypass permission (`ogsky.admin.bypass.<flag>` or `ogsky.admin.bypass.*`).
   - Resolve player's effective `IslandRole` (`byte 0..5`; banned -> force teleport/deny).
   - Compare `playerRole >= island.protectionRank(flagSlot)` *(for `PROTECTION`)* or check `island.isSettingEnabled(flagSlot)` *(for `SETTING`)*.
   - On denial, cancel the Fabric/server interaction cleanly and rate-limit warning feedback to avoid chat spam.

---

## 4. BentoBox-Aligned Domain Event Catalog (`OgskyEventBus`)

All domain events implement [`OgskyEvent`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/event/OgskyEvent.java) and are dispatched via `OgskyCore.api().events()`. Full payload definitions and signatures are documented in [`docs/core-api.md`](core-api.md#31-events-meogsammaenrogskycoreapievent).

> **Thread-Safety Rule (Learned from BentoBox):** All domain events MUST be published synchronously on the **main Minecraft server tick thread**. Async SQLite / blueprint callbacks must marshal via `server.execute(...)` before calling `events().publish(...)`.

1. **Island Lifecycle & State Events:**
   - [`IslandPreCreateEvent`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/event/IslandPreCreateEvent.java) *(cancellable: `UUID ownerId`, `String bundleId`)*
   - [`IslandCreatedEvent`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/event/IslandCreatedEvent.java) `(UUID islandId, UUID ownerId)` *(post-registration & Overworld slot allocation)*
   - [`IslandDimensionInitEvent`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/event/IslandDimensionInitEvent.java) `(UUID islandId, Identifier dimensionId, String blueprintId)` *(fired when `ogsky:overworld`, or lazy `ogsky:nether` / `ogsky:end`, is initialized)*
   - [`BlueprintPasteCompletedEvent`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/event/BlueprintPasteCompletedEvent.java) `(UUID islandId, Identifier dimensionId, String bundleId)` *(fired on server thread when tick-budgeted `.nbt` paste completes)*
   - [`IslandResetEvent`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/event/IslandResetEvent.java) *(cancellable: `UUID islandId, UUID ownerId, String newBundleId`)*
   - [`IslandPreDeleteEvent`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/event/IslandPreDeleteEvent.java) *(cancellable: `UUID islandId, UUID actorId, DeleteReason` — `PLAYER_DELETE`, `PURGE`, `ADMIN`)*
   - [`IslandDeletedEvent`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/event/IslandDeletedEvent.java) `(UUID islandId)` *(triggers `level.db`, `limits.db`, and `panel` cleanup)*
   - [`IslandRangeChangeEvent`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/event/IslandRangeChangeEvent.java) `(UUID islandId, short oldHalfSize, short newHalfSize)` *(triggers `limits` rescan & `panel` refresh)*
   - [`IslandLockChangedEvent`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/event/IslandLockChangedEvent.java) `(UUID islandId, UUID actorId, boolean locked)`
2. **Player Boundary & Access Events:**
   - [`IslandEnterEvent`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/event/IslandEnterEvent.java) *(cancellable unless `adminBypass == true`: `UUID islandId, UUID playerId, boolean adminBypass`)*
   - [`IslandExitEvent`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/event/IslandExitEvent.java) `(UUID islandId, UUID playerId)`
   - [`IslandBanEvent`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/event/IslandBanEvent.java) *(cancellable: `UUID islandId, UUID actorId, UUID targetId`)*
   - [`IslandUnbanEvent`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/event/IslandUnbanEvent.java) `(UUID islandId, UUID actorId, UUID targetId)`
3. **Team & Role Membership Events (Adapting BentoBox `TeamEvent` + Primary/Secondary Rules):**
   - [`TeamInviteEvent`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/event/TeamInviteEvent.java) *(cancellable: `UUID islandId, UUID inviterId, UUID inviteeId, byte targetRole`)*
   - [`TeamJoinEvent`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/event/TeamJoinEvent.java) `(UUID islandId, UUID playerId, byte role)` *(fired after primary/secondary cap validation succeeds)*
   - [`TeamLeaveEvent`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/event/TeamLeaveEvent.java) *(cancellable: `UUID islandId, UUID playerId, byte oldRole`)*
   - [`TeamKickEvent`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/event/TeamKickEvent.java) *(cancellable: `UUID islandId, UUID actorId, UUID targetId, byte oldRole`)*
   - [`TeamRankChangeEvent`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/event/TeamRankChangeEvent.java) *(cancellable: `UUID islandId, UUID actorId, UUID targetId, byte oldRole, byte newRole`)*
   - [`TeamOwnerChangeEvent`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/event/TeamOwnerChangeEvent.java) *(cancellable: `UUID islandId, UUID oldOwnerId, UUID newOwnerId`)*
4. **Flag & Progression Events:**
   - [`IslandProtectionFlagChangedEvent`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/event/IslandProtectionFlagChangedEvent.java) `(UUID islandId, UUID actorId, Identifier flagId, byte oldMinRank, byte newMinRank)`
   - [`IslandSettingFlagChangedEvent`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/event/IslandSettingFlagChangedEvent.java) `(UUID islandId, UUID actorId, Identifier flagId, boolean oldValue, boolean newValue)`
   - [`IslandLevelCalculatedEvent`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/event/IslandLevelCalculatedEvent.java) `(UUID islandId, UUID ownerId, long previousLevel, long newLevel, long blockPoints)` *(published by `ogsky-level`, observed by `ogsky-panel`)*

---

## 5. Core Command & World Mutation Abstractions

### Extensible Brigadier Command Tree ([`IslandCommandRegistry`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/command/IslandCommandRegistry.java))

Rather than building a custom string parser, `core` mounts native Minecraft **Brigadier** command trees (`/island` / `/is` and `/isadmin` / `/isa`) and merges module-contributed [`IslandSubcommand`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/command/IslandSubcommand.java) nodes:

- **Core exposes `OgskyCore.api().commands()` (`IslandCommandRegistry`):**
  - `registerSubcommand(IslandSubcommand)` mounts subcommands + aliases under `/island` and `/is`.
  - `registerAdminSubcommand(IslandSubcommand)` mounts subcommands + aliases under `/isadmin` and `/isa`.
  - Supplies [`IslandCommandHelper`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/command/IslandCommandHelper.java) providing RAM-resolved `primaryIsland(player)`, `islandAt(player)`, and permission checks (`hasPermission(source, node, fallbackOpLevel)`).
- **Commands Across Current Modules (full permission matrix in [`docs/modules.md`](modules.md)):**
  - `core`: `/is` (teleport home), `help`, `create [bundle]`, `reset [bundle]`, `delete [confirm]`, `home [dimension]`, `visit <player>`, `sethome`, `info [player]`, `members`, `invite <player>`, `accept`, `deny`, `cancel <player>`, `kick <player>`, `leave`, `coop <player>`, `uncoop <player>`, `trust <player>`, `untrust <player>`, `promote <player>`, `demote <player>`, `transfer <player>`, `ban <player>`, `unban <player>`, `lock [locked]`, `flags`, `flag <flag> <value>`; `/isadmin` `help|reload|delete|setrange|tp|info|bypass|worldflags|worldflag`.
  - `panel` *(optional)*: `/is panel [tab]` (`gui`, `menu`) & `/is settings [category]` (`setting`).
  - `level` *(optional)*: `/is level [player]` (`lvl`), `/is top [page]` (`leaderboard`), `/is values [page | search <query>]` (`value`, `worth`), `/isadmin level scan|scanall|reload`.
  - `limits` *(optional)*: `/is limits [page|all|blocks|entities|chat|search <query>]` (`limit`), `/isadmin limits reload|rescanall|rescan <player>|inspect <player> [page]`.
  - When `core` is built alone (`-Pmodules=core`), optional module subcommands simply aren't registered while all core `/is` & `/isadmin` commands function normally.

### World Mutation Interceptors (`OgskyCore.api().mutations()`)

For optional modules needing pre-placement/pre-spawn vetoes or block/entity lifecycle accounting (without coupling `core` to those modules), [`WorldMutationService`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/mutation/WorldMutationService.java) exposes `BlockPlacementGuard`, `BlockChangeObserver`, `EntitySpawnGuard`, and `EntityLifecycleObserver` backed by volatile fast-path Mixin guards in `ogsky-core`. See [`docs/core-api.md`](core-api.md#310-world-mutation-guards--observers-meogsammaenrogskycoreapimutation) for all call sites and threading guarantees.
