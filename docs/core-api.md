# Ogsky Core Public API Reference (`docs/core-api.md`)

> Canonical reference for `me.ogsammaenr.ogsky.core.api.*` exposed by `modules/core` (`ogsky-core`).
> Optional modules (`panel`, `level`, `limits`, and any future `modules/<name>`) MUST interact with `ogsky-core` and with one another **exclusively** through these public contracts.

Related guides:
- [Module Architecture, Connections & Extension Guide](modules.md)
- [Architecture Overview](architecture.md)
- [Island & World Specification](island-spec.md)
- [Flags, Protections, Commands & Domain Events](flags-and-events.md)
- [Shared Declarative GUI API](gui-api.md)

---

## 1. Bootstrap & Entrypoint Lifecycle

### `OgskyCore` & `OgskyModule`

| Type | Package | Role |
| :--- | :--- | :--- |
| [`OgskyCore`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/OgskyCore.java) | `me.ogsammaenr.ogsky.core.api` | Static holder exposing `MOD_ID` (`"ogsky"`), `CORE_MOD_ID` (`"ogsky-core"`), `LOGGER`, `id(String path)`, `api()`, `bind(OgskyCoreApi)`, and `resetForTesting()`. |
| [`OgskyCoreApi`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/OgskyCoreApi.java) | `me.ogsammaenr.ogsky.core.api` | Root facade returning the 11 core registries/services. |
| [`OgskyModule`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/OgskyModule.java) | `me.ogsammaenr.ogsky.core.api` | Contract implemented by optional modules under Fabric's `"ogsky-module"` entrypoint key. |

```java
public interface OgskyModule {
    String moduleId();
    void onLoad(OgskyCoreApi core);
}
```

### Deterministic Startup & Runtime Lifecycle ([`OgskyCoreMod`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/internal/OgskyCoreMod.java))

1. **Core Mod Initialization (`ModInitializer.onInitialize`):**
   - Loads or creates `config/ogsky/core.json`.
   - Constructs `OgskyCoreApiImpl` (which registers all built-in `ogsky:*` flags into `FlagRegistry`) and binds `OgskyCore.bind(api)`, `PortalRouter`, `ProtectionResolver`, and the `ogsky:void` chunk generator codec.
   - Registers the `"core"` SQLite database migrations (`CoreIslandRepository.MIGRATIONS`).
   - Registers built-in `/is panel` island actions (`registerBuiltinActions()`).
   - Discovers all Fabric `"ogsky-module"` entrypoints, **sorts them alphabetically by `moduleId()`** (rejecting duplicates), and invokes `module.onLoad(api)` deterministically (`level` → `limits` → `panel` when all defaults are bundled).
   - Calls `api.freezeRegistries()` (`FlagRegistry.freeze()` + initializes `WORLD_SETTING` defaults from config).
   - Registers Fabric `CommandRegistrationCallback` (`/island`, `/is`, `/isadmin`, `/isa` + all contributed `IslandSubcommand`s) and `ProtectionListeners`.
2. **Server Lifecycle (`SERVER_STARTING` → `END_SERVER_TICK` → `SERVER_STOPPING` / `SERVER_STOPPED`):**
   - `SERVER_STARTING`: Activates `CoreWorldMutationService`, opens & migrates all registered SQLite DBs at `<world>/ogsky/data/<moduleId>.db`, loads all islands into `CoreIslandService` RAM structures, and loads/compresses blueprint bundles.
   - `END_SERVER_TICK`: Ticks tick-budgeted blueprint pasting/cleanup (`CoreBlueprintService.tick`) and chunk-budgeted island scans (`CoreIslandScanService.tick`).
   - Player disconnect: Clears per-player invites, protection boundary tracking, and `/is delete` confirmation state.
   - `SERVER_STOPPING`: Cancels active scan jobs, clears pending blueprint paste queues/invites.
   - `SERVER_STOPPED`: Unbinds active `CoreWorldMutationService`, clears RAM island/tracking state, and flushes + checkpoints + closes all module SQLite databases.

### Registry Freeze & Threading Guarantees

| Subsystem | Registration Window | Threading Contract |
| :--- | :--- | :--- |
| `flags()` | Must register inside `OgskyModule.onLoad`; frozen immediately afterward (`api.freezeRegistries()`). | Frozen before server starts; runtime protection reads run lock-free on RAM `byte[]` / bitmask fields on the server tick thread. |
| `commands()` | Register inside `OgskyModule.onLoad` before Fabric fires `CommandRegistrationCallback`. | Synchronized collection; Brigadier trees execute on the server thread. |
| `databases()` | Register schemas inside `OgskyModule.onLoad` (or safely after `SERVER_STARTING`, which opens immediately). | All SQL executes on dedicated daemon thread `ogsky-db-<moduleId>`. Never block the tick thread during gameplay. |
| `services()`, `actions()`, `guis()`, `mutations()` | Typically inside `OgskyModule.onLoad`. | Thread-safe concurrent registries (`ServiceRegistry`, `SimpleIslandActionRegistry`, `SimpleGuiService`, `CopyOnWriteArrayList` + `volatile` fast-path booleans in `CoreWorldMutationService`). |
| `events()` | Subscribe inside `OgskyModule.onLoad`. | `OgskyEventBus` dispatches synchronously to subscribers; all domain events MUST be published on the **main server tick thread** (`server.execute(...)` from async callbacks). |

---

## 2. `OgskyCoreApi` Facade Overview

| Accessor | Return Type | Purpose |
| :--- | :--- | :--- |
| `events()` | `OgskyEventBus` | Typed synchronous pub/sub domain event bus + cancellable pre-action dispatch. |
| `services()` | `ServiceRegistry` | Type-safe singleton registry for cross-module optional capabilities (e.g., `IslandLevelProvider`). |
| `actions()` | `IslandActionRegistry` | Ordered action descriptors surfaced in `/is panel`'s Actions tab. |
| `databases()` | `DatabaseService` | Isolated per-module SQLite lifecycle manager (`<world>/ogsky/data/<moduleId>.db`). |
| `flags()` | `FlagRegistry` | 3-tier `PROTECTION`, `SETTING`, and `WORLD_SETTING` flag definitions and frozen slot indices. |
| `commands()` | `IslandCommandRegistry` | Pluggable Brigadier subcommand registry for `/island` (`/is`) and `/isadmin` (`/isa`). |
| `islands()` | `IslandService` | Read & mutate islands, grid lookups, roles/membership, protection/setting checks, and lifecycle ops. |
| `permissions()` | `PermissionService` | LuckPerms / `fabric-permission-api-v1` / fallback-OP permission and bypass checks. |
| `guis()` | `GuiService` | UI-agnostic screen definitions, slot-8 summary contributors, and optional `GuiHost` dispatch. |
| `scans()` | `IslandScanService` | Shared tick-budgeted 3-dimension block & entity counter across loaded and unloaded chunks. |
| `mutations()` | `WorldMutationService` | Pre-mutation placement/spawn guards and committed world block/entity lifecycle observers. |

---

## 3. Core Subsystem Reference

### 3.1 Events (`me.ogsammaenr.ogsky.core.api.event`)

- **`OgskyEventBus`**:
  - `<T extends OgskyEvent> Subscription subscribe(Class<T> eventType, Consumer<T> listener)`
  - `<T extends OgskyEvent> void publish(T event)`
  - `default <T extends CancellableOgskyEvent> boolean publishCancellable(T event)` — publishes `event` and returns `!event.isCancelled()` (`true` when allowed).
  - `Subscription` extends `AutoCloseable` (`unsubscribe()` / `close()`).
- **`CancellableOgskyEvent`** (base helper `AbstractCancellableOgskyEvent`):
  - `boolean isCancelled()`, `void setCancelled(boolean cancelled)`, `String cancelReason()`, `void cancel(String reason)`, `default void cancel()`.

#### Full Event Catalog (22 Domain Events)

| Event Type | Category | Cancellable | Payload Accessors |
| :--- | :--- | :--- | :--- |
| `IslandPreCreateEvent` | Lifecycle | Yes | `UUID ownerId()`, `String bundleId()` |
| `IslandCreatedEvent` | Lifecycle | No | `UUID islandId()`, `UUID ownerId()` |
| `IslandDimensionInitEvent` | Lifecycle | No | `UUID islandId()`, `Identifier dimensionId()`, `String blueprintId()` |
| `BlueprintPasteCompletedEvent` | Lifecycle | No | `UUID islandId()`, `Identifier dimensionId()`, `String bundleId()` |
| `IslandResetEvent` | Lifecycle | Yes | `UUID islandId()`, `UUID ownerId()`, `String newBundleId()` |
| `IslandPreDeleteEvent` | Lifecycle | Yes | `UUID islandId()`, `UUID actorId()` *(nullable)*, `DeleteReason reason()` (`PLAYER_DELETE`, `PURGE`, `ADMIN`) |
| `IslandDeletedEvent` | Lifecycle | No | `UUID islandId()` |
| `IslandRangeChangeEvent` | Lifecycle | No | `UUID islandId()`, `short oldHalfSize()`, `short newHalfSize()` |
| `IslandLockChangedEvent` | Lifecycle | No | `UUID islandId()`, `@Nullable UUID actorId()`, `boolean locked()` |
| `IslandEnterEvent` | Boundary | Yes *(ignored if `adminBypass() == true`)* | `UUID islandId()`, `UUID playerId()`, `boolean adminBypass()` |
| `IslandExitEvent` | Boundary | No | `UUID islandId()`, `UUID playerId()` |
| `IslandBanEvent` | Boundary | Yes | `UUID islandId()`, `UUID actorId()`, `UUID targetId()` |
| `IslandUnbanEvent` | Boundary | No | `UUID islandId()`, `UUID actorId()`, `UUID targetId()` |
| `TeamInviteEvent` | Team | Yes | `UUID islandId()`, `UUID inviterId()`, `UUID inviteeId()`, `byte targetRole()` |
| `TeamJoinEvent` | Team | No | `UUID islandId()`, `UUID playerId()`, `byte role()` |
| `TeamLeaveEvent` | Team | Yes | `UUID islandId()`, `UUID playerId()`, `byte oldRole()` |
| `TeamKickEvent` | Team | Yes | `UUID islandId()`, `UUID actorId()`, `UUID targetId()`, `byte oldRole()` |
| `TeamRankChangeEvent` | Team | Yes | `UUID islandId()`, `UUID actorId()`, `UUID targetId()`, `byte oldRole()`, `byte newRole()` |
| `TeamOwnerChangeEvent` | Team | Yes | `UUID islandId()`, `UUID oldOwnerId()`, `UUID newOwnerId()` |
| `IslandProtectionFlagChangedEvent` | Flags | No | `UUID islandId()`, `@Nullable UUID actorId()`, `Identifier flagId()`, `byte oldMinRank()`, `byte newMinRank()` |
| `IslandSettingFlagChangedEvent` | Flags | No | `UUID islandId()`, `@Nullable UUID actorId()`, `Identifier flagId()`, `boolean oldValue()`, `boolean newValue()` |
| `IslandLevelCalculatedEvent` | Progression | No | `UUID islandId()`, `UUID ownerId()`, `long previousLevel()`, `long newLevel()`, `long blockPoints()` |

---

### 3.2 Optional Service Registry (`me.ogsammaenr.ogsky.core.api.service`)

**`ServiceRegistry`**:
- `<T> void register(Class<T> serviceType, T implementation)` — enforces `serviceType.isInstance(implementation)` and rejects duplicate registrations for the same class (`IllegalStateException`).
- `<T> Optional<T> find(Class<T> serviceType)` — safe optional lookup across modules without compile coupling.
- `default <T> T require(Class<T> serviceType)` — throws `IllegalStateException` if absent.

Cross-module optional API example (`me.ogsammaenr.ogsky.core.api.level`):
- **`IslandLevelProvider`**: Registered by `ogsky-level` (`core.services().register(IslandLevelProvider.class, levelService)`), optionally queried by `ogsky-limits` when `levelScaling.enabled` is true:
  - `Optional<IslandLevelSnapshot> findSnapshot(UUID islandId)`
  - `OptionalInt rankOf(UUID islandId)` *(1-based rank)*
  - `List<IslandLevelSnapshot> topIslands(int limit)`
  - `int rankedIslandCount()`
  - `boolean isScanInProgress(UUID islandId)`
- **`IslandLevelSnapshot`**: `(UUID islandId, UUID ownerId, long levelValue, long blockPoints, long pointsPerLevel, long pointsToNextLevel, Instant updatedAt, List<BlockPointBreakdown> topBlocks)`
- **`BlockPointBreakdown`**: `(Identifier blockId, int rawCount, int countedForPoints, long pointsEarned)`

---

### 3.3 SQLite Databases (`me.ogsammaenr.ogsky.core.api.db`)

- **`DatabaseService`**:
  - `void registerModule(String moduleId, List<SchemaMigration> migrations)` — registers migrations (`moduleId` must match `^[a-z0-9_-]+$`). Opens immediately if a world is already active, otherwise on `SERVER_STARTING`.
  - `Optional<ModuleDatabase> find(String moduleId)` / `default ModuleDatabase require(String moduleId)`.
- **`ModuleDatabase`**:
  - `String moduleId()`, `Path databasePath()` (`<world>/ogsky/data/<moduleId>.db`).
  - `<T> CompletableFuture<T> queryAsync(SqlFunction<Connection, T> work)`
  - `CompletableFuture<Void> executeAsync(SqlConsumer<Connection> work)`
  - `<T> T queryBlocking(SqlFunction<Connection, T> work)` *(startup / migration / test use only — never call on live server ticks)*
  - `void executeBlocking(SqlConsumer<Connection> work)` *(startup / shutdown / test use only)*
  - `CompletableFuture<Void> flush()` — completes after all previously queued async DB tasks finish.
- **`SchemaMigration`**:
  - `(int version, String description, SqlConsumer<Connection> step)`
  - Static helper `SchemaMigration.sql(int version, String description, String... statements)` executes DDL/DML statements sequentially inside a transaction and records applied versions in `ogsky_schema_migrations`.
  - Connections automatically apply: `PRAGMA journal_mode = WAL`, `PRAGMA synchronous = NORMAL`, `PRAGMA busy_timeout = 5000`, `PRAGMA foreign_keys = ON`.

---

### 3.4 Islands & Roles (`me.ogsammaenr.ogsky.core.api.island`)

#### `IslandRole` Constants
- `VISITOR = 0`, `COOP = 1`, `TRUSTED = 2`, `MEMBER = 3`, `ADMIN = 4`, `OWNER = 5`, `DISABLED = 6` *(sentinel rank for blocking a `PROTECTION` flag even for owners)*.
- Static helpers: `isValidMemberRole(byte)` (`1..5`), `isValidThreshold(byte)` (`0..6`), `isPrimary(byte)` (`3..5`), `isSecondary(byte)` (`1..2`), `defaultKey(byte)`, `parseThreshold(String)`.

#### `RelativeSpawn` & `IslandMemberEntry`
- `RelativeSpawn(short relX, short relY, short relZ, byte yawQuantized, byte pitchQuantized)` — stores offsets from `(centerX, 0, centerZ)` with quantized angles (`256/360` step). Helpers: `atCenter(int y)`, `fromAbsolute(...)`, `worldX(centerX)`, `worldY()`, `worldZ(centerZ)`, `blockX(centerX)`, `blockY()`, `blockZ(centerZ)`, `yaw()`, `pitch()`.
- `IslandMemberEntry(UUID playerUuid, byte role)` — validates `1..5`.

#### `IslandView` (Read-Only RAM Projection)
- Identity & coordinates: `islandId()`, `ownerId()`, `gridX()`, `gridZ()`, `centerX()`, `centerZ()`, `short halfSize()`, `minX()`, `maxX()`, `minZ()`, `maxZ()`, `bundleId()`, `createdAt()`, `contains(int x, int z)`.
- State flags: `isOverworldReady()`, `isNetherReady()`, `isEndReady()`, `isPasting()`, `isDeleting()`.
- Spawns: `overworldSpawn()`, `netherSpawn()`, `endSpawn()`.
- Access & flags: `roleOf(UUID)`, `isBanned(UUID)`, `members()`, `bannedPlayers()`, `protectionRank(Identifier flagId)`, `isSettingEnabled(Identifier flagId)`.

#### `IslandService`
- **Queries (RAM-backed, zero blocking SQLite I/O):**
  - `findById(UUID)`, `findPrimaryIsland(UUID)`, `findSecondaryIslands(UUID)`, `findAt(ResourceKey<Level>, int x, int z)`, `findByGridCell(int gridX, int gridZ)`, `allIslands()`
  - `islandCount()`, `lockedCellSize()`, `maxCoop()`, `maxTrusted()`, `defaultBlueprintId()`, `availableBlueprints()`, `roleDisplayName(byte)`
  - `isWorldSettingEnabled(Identifier flagId)`, `isProtectionAllowed(ResourceKey<Level> dimension, int x, int z, UUID playerUuid, Identifier flagId, boolean adminBypass)`, `isSettingEnabledAt(ResourceKey<Level> dimension, int x, int z, Identifier flagId)`
  - `canEnterIsland(UUID islandId, ServerPlayer player)`, `isLocationSafe(ServerLevel level, double x, double y, double z)`
- **Mutations (return `IslandOperationResult<T>(boolean success, String code, String message, @Nullable T value)`):**
  - `setProtectionRank(@Nullable UUID actorId, UUID islandId, Identifier flagId, byte newMinRank, boolean bypassRole)`
  - `setSettingFlag(@Nullable MinecraftServer server, @Nullable UUID actorId, UUID islandId, Identifier flagId, boolean enabled, boolean bypassRole)`
  - `setWorldSetting(Identifier flagId, boolean enabled)`
  - `kickMember(...)`, `promoteMember(...)`, `demoteMember(...)`, `transferOwnership(...)`, `removeCoop(...)`, `removeTrust(...)`, `banPlayer(...)`, `unbanPlayer(...)`, `leaveIsland(...)`
  - `createIsland(@Nullable MinecraftServer server, @Nullable ServerPlayer actor, UUID ownerId, @Nullable String requestedBundleId)`
  - `resetIsland(@Nullable MinecraftServer server, UUID actorId, UUID islandId, @Nullable String requestedBundleId, boolean bypassOwnership)`
  - `teleportHome(MinecraftServer server, ServerPlayer player, UUID islandId, @Nullable ResourceKey<Level> targetDimension)`
  - `teleportToLocation(MinecraftServer server, ServerPlayer player, UUID islandId, ResourceKey<Level> targetDimension, double x, double y, double z, float yaw, float pitch, boolean enforceEntryAccess)`
  - `setHome(ServerPlayer player, UUID islandId, boolean bypassRole)`

---

### 3.5 Flags (`me.ogsammaenr.ogsky.core.api.flag`)

- **`FlagType`**: `PROTECTION` (`byte 0..6`), `SETTING` (`boolean` packed into a 64-bit `long` mask — max `64` `SETTING` flags total across all modules), `WORLD_SETTING` (global boolean).
- **`FlagMode`**: `BASIC`, `ADVANCED` (UI filter categories).
- **`IslandFlagDefinition`**: Record `(Identifier id, FlagType type, FlagMode mode, String iconItemId, int sortOrder, byte defaultMinRank, boolean defaultBoolValue, boolean wildernessAllowed)` with convenience factories:
  - `IslandFlagDefinition.protection(id, mode, iconItemId, sortOrder, defaultMinRank, wildernessAllowed)`
  - `IslandFlagDefinition.setting(id, mode, iconItemId, sortOrder, defaultValue)`
  - `IslandFlagDefinition.worldSetting(id, mode, iconItemId, sortOrder, defaultValue)`
- **`FlagRegistry`**:
  - `register(IslandFlagDefinition)`, `find(Identifier)`, `require(Identifier)`, `listAll()`, `listByType(FlagType)`, `slotOf(Identifier)`, `count(FlagType)`, `isFrozen()`.
  - Once frozen after `OgskyModule.onLoad`, registering additional flags throws `IllegalStateException`.

---

### 3.6 Commands & Panel Actions (`core.api.command` & `core.api.action`)

- **`IslandCommandRegistry`**:
  - `registerSubcommand(IslandSubcommand)` — mounts under `/island` and `/is`.
  - `registerAdminSubcommand(IslandSubcommand)` — mounts under `/isadmin` and `/isa`.
  - Built-in literals (`help`, `create`, `flags`, etc.) take precedence; colliding custom literals/aliases log a warning when mounted (and duplicate names within custom registrations throw `IllegalStateException`).
- **`IslandSubcommand`**:
  - `String name()`, `default List<String> aliases()`, `default String permissionNode()` (empty string allows all players for `/is`, or fallback OP level `2` under `/isadmin`), `void register(LiteralArgumentBuilder<CommandSourceStack> node, IslandCommandHelper helper)`.
- **`IslandCommandHelper`**:
  - `hasPermission(CommandSourceStack source, String permissionNode, int fallbackOpLevel)`
  - `Optional<IslandView> primaryIsland(ServerPlayer player)`
  - `Optional<IslandView> islandAt(ServerPlayer player)`
- **`IslandActionRegistry`** & **`IslandActionDescriptor`**:
  - `register(IslandActionDescriptor)`, `listOrdered()` *(sorted by `sortOrder` then `id.toString()`)*, `find(Identifier)`.
  - `IslandActionDescriptor(Identifier id, String title, String description, String iconItemId, int sortOrder, boolean closePanelOnExecute, IslandActionHandler handler)` + 5-arg convenience constructor (`description = ""`, `closePanelOnExecute = false`).
  - `IslandActionHandler.execute(IslandActionContext(ServerPlayer actor, UUID islandId))`.

---

### 3.7 Permissions (`me.ogsammaenr.ogsky.core.api.permission`)

**`PermissionService`**:
- Resolution order implemented by [`LuckPermsPermissionBridge`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/internal/permission/LuckPermsPermissionBridge.java):
  1. **LuckPerms** (detected via reflection if installed — honors explicit `TRUE` / `FALSE` tristates)
  2. **`fabric-permission-api-v1`** (`PermissionContextOwner.checkPermission(ogsky:<path_without_ogsky_prefix>)`, skipped if node contains `*`)
  3. **Vanilla OP level fallback** (`fallbackOpLevel <= 0` returns `true`; otherwise compares player's `PermissionLevel`).
- Methods:
  - `boolean hasPermission(ServerPlayer player, String permissionNode, int fallbackOpLevel)`
  - `boolean hasAdmin(ServerPlayer player)` — checks `"ogsky.admin"` with configurable `config.admin.fallbackOpLevel` (default `2`).
  - `boolean isBypassActive(ServerPlayer player)` — checks `/isadmin bypass` per-session toggle first, then `"ogsky.admin.bypass.*"` (and `ProtectionResolver` checks flag-specific `"ogsky.admin.bypass.<flagPath>"`).
  - `boolean hasBlueprintPermission(ServerPlayer player, String bundleId)` — checks `"ogsky.blueprint.<bundleId>"` with fallback `0`.
  - `boolean hasBlueprintPermission(ServerPlayer player, BlueprintBundleView bundle)` — resolves `bundle.permissionNode()` (or `"ogsky.blueprint.<id>"`) with fallback `config.admin.fallbackOpLevel` when `bundle.requiresPermission()` is `true`, else `0`.
  - `Optional<Boolean> checkExplicitPermission(UUID playerUuid, String permissionNode)` — queries explicit LuckPerms decision without OP-level fallback.

---

### 3.8 Declarative GUI Service (`me.ogsammaenr.ogsky.core.api.gui`)

See [`docs/gui-api.md`](gui-api.md) for the complete `PAGED_GRID` (54-slot) / `CUSTOM_FIXED` (1–6 row) layout guide and live examples.

Key `GuiService` (`core.guis()`) methods:
- `boolean isAvailable()` — `true` only when a `GuiHost` (such as `ogsky-panel`) is registered.
- `void registerHost(GuiHost host)` — single host slot (throws `IllegalStateException` if a second distinct host attempts to bind).
- `void registerScreen(Identifier screenId, GuiScreenProvider provider)` — registers a module screen builder (`MAIN_PANEL_SCREEN_ID = ogsky:main_panel`).
- `void registerSummaryContributor(Identifier contributorId, int sortOrder, IslandSummaryContributor contributor)` — appends lines to slot `8` on `/is panel`.
- `boolean openScreen(ServerPlayer player, Identifier screenId, @Nullable UUID islandId)` / `openScreen(ServerPlayer player, GuiOpenRequest request)` — returns `false` when no `GuiHost` is loaded or the screen ID is unknown so callers can run chat fallbacks.
- `void refreshIslandScreens(UUID islandId)` / `void refreshScreen(ServerPlayer player, Identifier screenId)` — live updates open menus after async results arrive.
- `Optional<GuiScreenProvider> findScreen(Identifier screenId)` & `List<Component> collectSummaryLines(IslandSummaryContext context)`.

---

### 3.9 Tick-Budgeted Island Scanning (`me.ogsammaenr.ogsky.core.api.scan`)

`IslandScanService` (`core.scans()`) performs a non-blocking, tick-budgeted traversal across an island's bounding square in all initialized Skyblock dimensions (`ogsky:overworld`, `ogsky:nether`, `ogsky:end`):
- **Loaded chunks:** iterates in-memory `LevelChunkSection` palettes & loaded `ServerLevel` entity tracking directly without allocating `BlockPos` per block.
- **Unloaded chunks (`includeUnloadedChunks = true`):** queues bounded non-blocking `ChunkMap.read(ChunkPos)` + entity storage disk reads (`maxInFlightDiskReads`) for chunks inside the island column without generating unborn void chunks.
- **Methods:**
  - `CompletableFuture<IslandScanResult> scanIsland(MinecraftServer server, UUID islandId, IslandScanOptions options, BlockStateCountConsumer consumer)`
  - `default CompletableFuture<IslandScanResult> scanIsland(MinecraftServer server, UUID islandId, BlockStateCountConsumer consumer)` *(uses `IslandScanOptions.defaults()`: `chunksPerTick=16`, `maxNanosPerTick=4_000_000L`, `includeUnloadedChunks=true`, `maxInFlightDiskReads=8`)*
  - `default CompletableFuture<IslandScanResult> scanIslandWithEntities(MinecraftServer server, UUID islandId, IslandScanOptions options, BlockStateCountConsumer blockConsumer, @Nullable EntityTypeCountConsumer entityConsumer)`
  - `void cancelScansForIsland(UUID islandId)` — automatically invoked on `IslandDeletedEvent` and non-cancelled `IslandResetEvent`.
- **Thread Contract:** Both `BlockStateCountConsumer.accept(BlockState state, int count)` and `EntityTypeCountConsumer.accept(EntityType<?> entityType, int count)` execute strictly on the server tick thread. `IslandScanResult` reports `(UUID islandId, int plannedChunks, int loadedChunksScanned, int diskChunksScanned, int skippedUnbornChunks, long nonAirBlocksCounted, boolean aborted, String abortReason)`.

---

### 3.10 World Mutation Guards & Observers (`me.ogsammaenr.ogsky.core.api.mutation`)

`WorldMutationService` (`core.mutations()`) decouples `ogsky-core` Minecraft 26.3 Mixin interception points from feature modules such as `ogsky-limits`.

> **Performance Guarantee:** [`CoreWorldMutationService`](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/internal/mutation/CoreWorldMutationService.java) guards every Mixin injection with `volatile boolean` fast-path flags (`hasBlockPlacementGuards()`, `hasBlockChangeObservers()`, etc.). When no module registers guards/observers (e.g., `-Pmodules=core`), Mixin hot paths return immediately. Calls off the main server thread (e.g., chunk generation/worldgen worker threads) are ignored (`Allow` / no-op) so worldgen never contends on server-thread state.

| Contract | Registration Method | Purpose & MC 26.3 Hook Coverage |
| :--- | :--- | :--- |
| `BlockPlacementGuard` | `registerBlockPlacementGuard(...)` | Evaluates before guarded placements or fluid generators mutate blocks. Sources (`BlockPlacementSource`): `PLAYER` (`BlockItemPlaceMixin`, `SolidBucketItemMixin`, `BucketItemEmptyMixin`), `DISPENSER` (`BucketItemEmptyMixin`), `LIQUID_GENERATOR` (`LiquidBlockGeneratorMixin`, `LavaFluidGeneratorMixin`), `WORLD_EFFECT`. Evaluators return `BlockMutationDecision.allow()`, `.deny()`, or `.replace(BlockState)` (evaluated sequentially; first `DENY` short-circuits, `REPLACE` chains effective state). |
| `BlockChangeObserver` | `registerBlockChangeObserver(...)` | Invoked via `LevelChunkSetBlockMixin` after any block state change commits in a `ServerLevel` chunk (`onBlockChanged(level, pos, oldState, newState, updateFlags)`). |
| `EntitySpawnGuard` | `registerEntitySpawnGuard(...)` | Pre-spawn gate consulted **before** entities are created or added to a `ServerLevel`. Sources (`EntitySpawnSource`): `ITEM_USE` (`ArmorStandItem`, `HangingEntityItem`, `MinecartItem`, `BoatItem`, `EndCrystalItem`, `SpawnEggItem`, `BucketItemEmpty`, `MobBucketItemEmpty`), `DISPENSER` (`ArmorStand/Minecart/Boat/SpawnEgg/Bucket`), `BREEDING` (`AnimalBreedingMixin`), `STRUCTURE_SUMMON` (`CarvedPumpkinGolemMixin`, `WitherSkullSpawnMixin`), `NATURAL` (`NaturalSpawnerMixin`), `CROSS_ISLAND_MOVE` (`EntityLifecycleExtensionMixin`), `OTHER` (`ServerLevelAddEntityMixin`). |
| `EntityLifecycleObserver` | `registerEntityLifecycleObserver(...)` | Notified on real entity creation (`onEntityAdded`), permanent destruction/dimension transfer (`onEntityRemoved` — **does not** fire on chunk unloads/loads), and horizontal block column crossings (`onEntityCrossedBlockColumn`, returning `false` vetoes border crossings). |

---

### 3.11 Blueprints & Dimensions (`core.api.blueprint` & `core.api.world`)

- **`BlueprintBundleView`**: `(String id, String displayName, String description, String iconItemId, boolean requiresPermission, String permissionNode, int order, boolean hasOverworld, boolean hasNether, boolean hasEnd)` returned by `IslandService.availableBlueprints()`.
- **`OgskyDimensionKeys`**:
  - `OVERWORLD` (`ogsky:overworld`), `NETHER` (`ogsky:nether`), `END` (`ogsky:end`), `ALL` (`List.of(OVERWORLD, NETHER, END)`).
  - Helpers: `isSkyblockDimension(ResourceKey<Level>)`, `dimensionName(ResourceKey<Level>)` (`"overworld"`, `"nether"`, `"end"`, or identifier string).
