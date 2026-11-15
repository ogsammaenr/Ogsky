# Ogsky Flags, Protections, Commands & Domain Events (`docs/flags-and-events.md`)

This document specifies how **BentoBox / BSkyBlock**'s protection engine, 3-tier flags, domain events, and extensible command tree integrate into **`ogsky-core`** (`me.ogsammaenr.ogsky.core.api.*`).

> **Minecraft 26.3 Reminder:** Before writing Fabric listeners, Brigadier nodes, or mixins for any item below, verify exact MC `26.3` / Fabric API class & method signatures using the `mc-source-lookup` skill.

---

## 1. 3-Tier Flag Architecture (`FlagRegistry`)

Ogsky adopts BentoBox's 3-category `Flag.Type` pattern while storing values in RAM using sequential slot indices rather than boxed `HashMap<Flag, Integer>` maps.

### Flag Contract (Planned Core API)

- `IslandFlagDefinition` registered at module startup via `OgskyCoreApi.flags()` (`FlagRegistry`):
  - `Identifier id` (e.g., `ogsky:break_blocks`, `ogsky:pvp_overworld`, or custom `ogsky-level:...`)
  - `FlagType type`: `PROTECTION`, `SETTING`, `WORLD_SETTING`
  - `FlagMode mode`: `BASIC` | `ADVANCED` *(hint for `ogsky-panel` UI grouping/filtering, inspired by BentoBox `Flag.Mode`)*
  - `String iconItemId`: vanilla item identifier for UI icons (e.g., `"minecraft:iron_pickaxe"`)
  - `int sortOrder`
  - Default value:
    - For `PROTECTION`: default minimum `byte` rank (`0..5`, typically `IslandRole.COOP` or `IslandRole.MEMBER`) + `boolean wildernessAllowed`
    - For `SETTING`: default `boolean`
    - For `WORLD_SETTING`: default `boolean` (stored globally in config / `world_meta`, editable by admins)

### Compact Slot Allocation

- During startup (`SERVER_STARTING` / after all `ogsky-module` entrypoints load), `FlagRegistry` freezes registration order and assigns deterministic slot indices per `FlagType` + persists flag ID-to-config/DB key mapping so optional modules can be enabled/disabled without corrupting existing island overrides.

---

## 2. Built-In BentoBox-Aligned Flag Catalog

### A. `PROTECTION` Flags (Minimum `IslandRole` byte `0..5`)

| Flag ID (`ogsky:*`) | Default Min Rank | Covers / Guarded Interactions |
| :--- | :--- | :--- |
| `break_blocks` | `COOP (1)` or `MEMBER (3)` *(configurable)* | General block breaking (excluding specialized flags below when defined) |
| `place_blocks` | `COOP (1)` or `MEMBER (3)` | General block placing inside island boundaries |
| `container` | `MEMBER (3)` | Chests, trapped chests, barrels, shulker boxes, dispensers, droppers, crafters, bookshelves |
| `furnace` | `MEMBER (3)` | Furnaces, blast furnaces, smokers |
| `hopper_use` | `MEMBER (3)` | Opening hopper inventories & modifying hopper state |
| `break_hoppers` | `MEMBER (3)` | Breaking hoppers (overrides `break_blocks` when stricter) |
| `break_spawners` | `ADMIN (4)` | Breaking monster spawners (overrides `break_blocks` when stricter) |
| `door` | `VISITOR (0)` or `COOP (1)` | Wooden/copper doors & fence gates |
| `trapdoor` | `COOP (1)` | Trapdoors |
| `button_lever` | `COOP (1)` | Buttons, levers |
| `pressure_plate` | `VISITOR (0)` | Player/entity pressure plates & tripwires |
| `redstone` | `MEMBER (3)` | Repeaters, comparators, daylight detectors, note blocks, copper bulbs |
| `bed` | `MEMBER (3)` | Sleeping / interacting with beds and respawn anchors |
| `anvil` | `COOP (1)` | Anvil interaction & damaging |
| `beacon` | `MEMBER (3)` | Beacon GUI & effect selection |
| `brewing` | `MEMBER (3)` | Brewing stands & cauldrons |
| `enchanting` | `VISITOR (0)` | Enchanting tables & grindstones & stonecutters & looms & smithing tables |
| `armor_stand` | `MEMBER (3)` | Placing, breaking, equipping/removing armor stands |
| `item_frame` | `MEMBER (3)` | Placing, breaking, rotating items in (glow) item frames & paintings |
| `bucket` | `MEMBER (3)` | Filling/emptying water, lava, milk, or mob buckets |
| `crop_trample` | `MEMBER (3)` | Farmland trampling protection |
| `harvest` | `COOP (1)` | Harvesting/replanting crops, sweet berries, cocoa, turtle eggs |
| `breeding` | `MEMBER (3)` | Breeding animals / feeding livestock |
| `shear_entity` | `COOP (1)` | Shearing sheep, mooshrooms, snow golems |
| `hurt_animals` | `MEMBER (3)` | Attacking passive mobs (cows, sheep, horses, pets) |
| `hurt_villagers` | `ADMIN (4)` | Attacking villagers, wandering traders, iron golems |
| `hurt_monsters` | `VISITOR (0)` | Attacking hostile mobs inside island bounds |
| `trading` | `VISITOR (0)` | Trading with villagers / wandering traders |
| `mount_entity` | `COOP (1)` | Riding horses, camels, boats, minecarts & leash usage |
| `ender_pearl` | `COOP (1)` | Throwing ender pearls or chorus fruit teleportation onto/inside island |
| `drop_item` | `VISITOR (0)` | Dropping items on an island |
| `pickup_item` | `VISITOR (0)` | Picking up item drops on an island |
| `portal_use` | `VISITOR (0)` | Entering island Nether/End portals |
| `flint_and_steel` | `MEMBER (3)` | Igniting blocks/TNT/portals with flint & steel or fire charges |

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

All domain events implement `OgskyEvent` and are dispatched via `OgskyCore.api().events()`.

> **Thread-Safety Rule (Learned from BentoBox):** All domain events MUST be published synchronously on the **main Minecraft server tick thread**. Async SQLite / blueprint callbacks must marshal via `server.execute(...)` before calling `events().publish(...)`.

### Implemented in Phase 0
- [`IslandCreatedEvent`](file:///workspace/ogsky-template-26.3/modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/event/IslandCreatedEvent.java) `(UUID islandId, UUID ownerId)`
- [`IslandDeletedEvent`](file:///workspace/ogsky-template-26.3/modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/event/IslandDeletedEvent.java) `(UUID islandId)`

### Planned in Phase 1 (Aligned with BentoBox `IslandEvent`, `TeamEvent`, `FlagEvent`)

1. **Island Lifecycle & State Events:**
   - `IslandPreCreateEvent` *(cancellable: `UUID ownerId`, `String bundleId`)*
   - `IslandCreatedEvent` *(already exists; post-registration & initial Overworld allocation)*
   - `IslandDimensionInitEvent` `(UUID islandId, Identifier dimensionId, String blueprintId)` *(fired when lazy `ogsky:nether` or `ogsky:end` island structure is initialized)*
   - `BlueprintPasteCompletedEvent` `(UUID islandId, Identifier dimensionId, String bundleId)` *(fired on server thread when tick-budgeted `.nbt` paste completes)*
   - `IslandResetEvent` *(cancellable pre-reset or post-reset record: `UUID islandId, UUID ownerId, String newBundleId`)*
   - `IslandPreDeleteEvent` *(cancellable: `UUID islandId, UUID actorId, DeleteReason` — `PLAYER_DELETE`, `PURGE`, `ADMIN`)*
   - `IslandDeletedEvent` *(already exists; triggers `level.db`, `panel.db`, etc. cleanup)*
   - `IslandRangeChangeEvent` `(UUID islandId, short oldHalfSize, short newHalfSize)` *(supports upgrades/admin resize)*
   - `IslandLockChangedEvent` `(UUID islandId, UUID actorId, boolean locked)`
2. **Player Boundary & Access Events:**
   - `IslandEnterEvent` *(cancellable when not admin bypass: `UUID islandId, UUID playerId`)*
   - `IslandExitEvent` `(UUID islandId, UUID playerId)`
   - `IslandBanEvent` *(cancellable: `UUID islandId, UUID actorId, UUID targetId`)*
   - `IslandUnbanEvent` `(UUID islandId, UUID actorId, UUID targetId)`
3. **Team & Role Membership Events (Adapting BentoBox `TeamEvent` + Ogsky Primary/Secondary Rules):**
   - `TeamInviteEvent` *(cancellable: `UUID islandId, UUID inviterId, UUID inviteeId, byte targetRole`)*
   - `TeamJoinEvent` `(UUID islandId, UUID playerId, byte role)` *(fired after primary/secondary cap validation succeeds)*
   - `TeamLeaveEvent` *(cancellable: `UUID islandId, UUID playerId, byte oldRole`)*
   - `TeamKickEvent` *(cancellable: `UUID islandId, UUID actorId, UUID targetId, byte oldRole`)*
   - `TeamRankChangeEvent` *(cancellable: `UUID islandId, UUID actorId, UUID targetId, byte oldRole, byte newRole`)*
   - `TeamOwnerChangeEvent` *(cancellable: `UUID islandId, UUID oldOwnerId, UUID newOwnerId`)*
4. **Flag Events:**
   - `IslandProtectionFlagChangedEvent` `(UUID islandId, UUID actorId, Identifier flagId, byte oldMinRank, byte newMinRank)`
   - `IslandSettingFlagChangedEvent` `(UUID islandId, UUID actorId, Identifier flagId, boolean oldValue, boolean newValue)`

---

## 5. Core Command Abstraction (`IslandCommandRegistry` — Adapting `CompositeCommand`)

Rather than writing a Bukkit-style string command parser, `core` wraps Fabric/Minecraft's native **Brigadier** dispatcher while providing BentoBox `CompositeCommand`-style ergonomics:

- **Core exposes `OgskyCoreApi.commands()` (`IslandCommandRegistry`, Planned Phase 1):**
  - Registers subcommand trees cleanly under `/island` and alias `/is`.
  - Provides **`IslandCommandContext`** helper methods resolved from RAM:
    - Player's primary `Island` (or current standing `Island`)
    - Built-in pre-flight checks:
      - Requires primary island / must NOT have primary island
      - Requires minimum `IslandRole` (`byte 0..5`)
      - Requires cooldown / admin permission node (`ogsky.admin.*`)
      - Standardized localized error responses
- **Examples Across Modules:**
  - `core`: `/is create [bundle]`, `/is home`, `/is reset`, `/is delete`, `/is invite`, `/is accept`, `/is kick`, `/is leave`, `/is coop`, `/is trust`, `/is untrust`, `/is ban`, `/is unban`, `/is promote`, `/is demote`, `/is transfer`, `/is lock`, `/is sethome`, `/is info`, `/is flag ...`, `/isadmin ...`
  - `panel` *(optional)*: registers `/is panel` & `/is settings` via `OgskyCore.api().commands()`.
  - `level` *(optional)*: registers `/is level` & `/is top` via `OgskyCore.api().commands()`.
  - When `modules=core` is built alone, `/is panel` or `/is level` simply aren't registered while all core `/is` commands keep working cleanly.
