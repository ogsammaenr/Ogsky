# Ogsky Architecture

## Overview

Ogsky is a server-side, compile-time modular Skyblock mod for **Minecraft 26.3 (Fabric)**, adapting proven architectural patterns from **BentoBox / BSkyBlock** (3-tier flags, rich domain events, `.nbt` blueprint bundles, composable subcommands, and tabbed panels) into a lightweight, RAM-first native Fabric codebase.

```text
+----------------------------------------------------------------------+
|                Root Bundle: ogsky-<version>.jar                      |
|  Selects modules at build time via -Pmodules=core,panel,level,       |
|  limits,warps                                                        |
|                                                                      |
|  META-INF/jars/                                                      |
|   ├── ogsky-core-<version>.jar   (+ sqlite-jdbc Jar-in-Jar)          |
|   ├── ogsky-panel-<version>.jar  (optional)                          |
|   ├── ogsky-level-<version>.jar  (optional)                          |
|   ├── ogsky-limits-<version>.jar (optional)                          |
|   └── ogsky-warps-<version>.jar  (optional)                          |
+----------------------------------------------------------------------+
```

## Module Dependency Rules

- `modules/core` (`ogsky-core`): Owns public contracts (`me.ogsammaenr.ogsky.core.api.*` including `OgskyEventBus`, `ServiceRegistry`, `FlagRegistry`, `IslandCommandRegistry`, `IslandActionRegistry`, `DatabaseService`, `IslandService`, `PermissionService`, `GuiService`, `IslandScanService`, `WorldMutationService`, `IslandLevelProvider`), Skyblock worldgen (`ogsky:overworld`, `ogsky:nether`, `ogsky:end`), `O(1)` `IslandGrid` + RAM storage, `.nbt` blueprint bundle loader & tick-budgeted paster (with lazy Nether/End pasting), tick-budgeted 3-dimension async block & entity scanner (`CoreIslandScanService`), 3-tier protection engine (`ProtectionResolver` + `ProtectionListeners`), Mixin mutation hooks (`CoreWorldMutationService`), Brigadier `/is` + `/isadmin` command tree root, and SQLite database lifecycle (`SqliteDatabaseService`). Zero dependency on optional modules.
- `modules/panel` (`ogsky-panel`): Depends only on `core`. Builds UI-agnostic, **tabbed/paginated `PanelSnapshot` ViewModels** combining `core.actions()`, `core.flags()`, island members, `core.guis()` summary contributors, and optional service data; binds `PanelGuiHost` onto `OgskyCore.api().guis()` so all optional module screens render through the shared dupe-proof `OgskyReadOnlyChestMenu` (`OgskyPanelMenu` + `OgskyCustomGuiMenu`); subscribes to `IslandLevelCalculatedEvent` and core events for live slot updates; persists preferences in `panel.db`; and registers `/is panel` & `/is settings` via `core.commands()`.
- `modules/level` (`ogsky-level`): Depends only on `core`. Loads configurable block/tag point values (`100 points = 1 level` default) and scoring-only caps (`blockPointLimits`) from `config/ogsky/level.json` (live-reloadable via `/isadmin level reload`). Scans islands via `core.scans()`, persists snapshots & top block breakdowns in `level.db`, exposes `IslandLevelProvider` via `core.services()`, publishes `IslandLevelCalculatedEvent`, reacts to `IslandDeletedEvent`, `IslandResetEvent`, and `BlueprintPasteCompletedEvent`, contributes `ogsky_level:summary`, `ogsky_level:top`, `ogsky_level:detail`, and `ogsky_level:values` into `core.guis()` plus `ogsky:island_level` / `ogsky_level:island_top` / `ogsky_level:block_values` actions (falling back cleanly to chat output when `ogsky-panel` is not bundled), and registers `/is level`, `/is top`, `/is values`, & `/isadmin level scan|scanall|reload` via `core.commands()`.
- `modules/limits` (`ogsky-limits`): Depends only on `core`. Enforces physical per-island block and entity placement limits loaded from `config/ogsky/limits.json` with multi-bucket accounting (individual rules, `#tag` rules, and named custom groups), fluid generator policies (`CANCEL`, `FALLBACK_TO_STONE`, `FULL_FALLBACK_WITH_AIR`), base multi-block counting, optional dynamic scaling via `core.services().find(IslandLevelProvider.class)`, compact RAM counters backed by `limits.db`, and tick-budgeted initial/rescan synchronization via `core.scans().scanIslandWithEntities(...)`. Registers protection flag `ogsky_limits:view_limits`, hooks all four `WorldMutationService` extension points (`core.mutations()`), contributes `ogsky_limits:summary`, `ogsky_limits:overview`, and action `ogsky_limits:island_limits` to `core.guis()`/`core.actions()` (with chat fallback when `ogsky-panel` is absent), and registers `/is limits` & `/isadmin limits reload|rescanall|rescan|inspect` via `core.commands()`.
- `modules/warps` (`ogsky-warps`): Depends only on `core`. Provides multi-warp per-island showcase, owner-permission-scaled warp limits, category/icon customization (defaulting to owner player heads), optional `IslandLevelProvider` showcase sorting (`Recent`, `Popularity`, `Island Level`, `Alphabetical`), `warmupSeconds` (cancelled on movement or `ServerLivingEntityEvents.AFTER_DAMAGE`) and `cooldownSeconds`, strict `CANCEL` location safety via `core.islands().teleportToLocation(...)`, anti-spam popularity counting, personal `ClientboundLevelParticlesPacket` markers (`/is warps show`), `ogsky_warps:manage_warps` & `ogsky_warps:use_warp` flags, `warps.db` persistence, GUI screens (`ogsky_warps:showcase`, `manage`, `edit`) with chat fallback, and `/is warp`, `/is warps`, `/is setwarp`, `/is delwarp`, `/isadmin warps` commands.

> Related specs:
> - Core Public API reference (`me.ogsammaenr.ogsky.core.api.*`): [docs/core-api.md](core-api.md)
> - Module wiring, configs, commands, permissions, schemas & extension guide: [docs/modules.md](modules.md)
> - Domain model, grid, lazy Nether/End, `.nbt` blueprints & RAM layout: [docs/island-spec.md](island-spec.md)
> - 3-tier flags, protections, events & command contracts: [docs/flags-and-events.md](flags-and-events.md)
> - Shared declarative GUI abstraction (`core.guis()`): [docs/gui-api.md](gui-api.md)
> - Implementation phases: [docs/roadmap.md](roadmap.md)

## Per-Module SQLite Persistence

- Each module stores data in `<world>/ogsky/data/<moduleId>.db` managed by `SqliteDatabaseService`.
- Connections run with `PRAGMA journal_mode=WAL`, `PRAGMA synchronous=NORMAL`, `PRAGMA busy_timeout=5000`, and `PRAGMA foreign_keys=ON`.
- Each database serializes reads/writes onto its own daemon thread (`ogsky-db-<moduleId>`) to avoid blocking the server tick thread and prevent `SQLITE_BUSY` write races.
- Connections open on `ServerLifecycleEvents.SERVER_STARTING` and flush + checkpoint + close on `ServerLifecycleEvents.SERVER_STOPPED`.
- Removing a module from `-Pmodules=...` preserves its `.db` file untouched on disk.
- Domain event invariant: async DB callbacks MUST marshal back to the server thread (`server.execute(...)`) before publishing to `OgskyEventBus`.

## Hybrid GUI Strategy (`TabbedPanel` → `PanelSnapshot` + `core.api.gui`)

Inspired by BentoBox's `TabbedPanel` & `PanelItem` pattern:

1. **Server-Validated Declarative ViewModels (`PanelSnapshot` + `core.api.gui`):**
   - `modules/core` exposes UI-agnostic `GuiService` (`OgskyCore.api().guis()`), `GuiScreenModel`, `GuiButton`, and `IslandSummaryContributor` contracts (zero `ChestMenu`/`Slot`/`ItemStack` dependencies) so any optional module can declare paginated or fixed sub-screens and slot-8 summary contributions.
   - `ogsky-panel` builds the core 5-tab `PanelSnapshot` (`Actions`, `Protection Flags`, `Settings Flags`, `Members`, `Blueprints & Info`) incorporating registered `core.actions()`, `core.flags()`, island state, and `core.guis().collectSummaryLines(...)`.
2. **Server-Only Vanilla Clients (`OgskyReadOnlyChestMenu` + `PanelGuiHost`):**
   - `ogsky-panel` binds `PanelGuiHost` onto `core.guis()` and renders both `OgskyPanelMenu` and module-contributed `OgskyCustomGuiMenu` instances over a shared dupe-proof `OgskyReadOnlyChestMenu` (`ReadOnlySlot`, `ogsky_panel_item` marker, `ServerGamePacketListenerImplMixin` creative/offhand guards, two-click confirmations, and automatic `"Back to Control Panel"` return links).
   - When `ogsky-panel` is omitted via `-Pmodules=core,<mod>`, `core.guis().isAvailable()` is `false` and commands gracefully fall back to formatted chat output.
3. **Phase 5+ (Optional Client Companion Mod):**
   - A future `modules/client-ui` mod registers custom `CustomPacketPayload` channels. When `ServerPlayNetworking.canSend(player, ...)` is true, the server sends `PanelSnapshot` / `GuiScreenModel` state over the payload instead of opening a `ChestMenu`; vanilla clients seamlessly fall back to `ChestMenu`.
