# Ogsky Development Roadmap

## Phase 0 — Modular & Agentic Foundation
- [x] Multi-project Gradle structure (`modules/core`, `modules/panel`, `modules/level`, `modules/limits`)
- [x] Compile-time module selection via `-Pmodules=...` and Fabric Jar-in-Jar bundling
- [x] `OgskyCoreApi`: `OgskyEventBus`, `ServiceRegistry`, `IslandActionRegistry`, `DatabaseService` (SQLite + WAL + migrations)
- [x] JUnit unit tests + headless `runGameTest` server verification + `deployServer` task
- [x] Agentic rules (`AGENTS.md`) & skills (`.agents/skills/`)
- [x] Comprehensive architecture, API, and module documentation ([`docs/core-api.md`](core-api.md), [`docs/modules.md`](modules.md), [`docs/island-spec.md`](island-spec.md), [`docs/flags-and-events.md`](flags-and-events.md), [`docs/architecture.md`](architecture.md))

## Phase 1 — `core`: Worldgen, Blueprints, Flags, Events & Protection
See canonical rules in [`docs/island-spec.md`](island-spec.md) and [`docs/flags-and-events.md`](flags-and-events.md).

- [x] `config/ogsky/core.json`: grid `cellSize` (`1024`), `initialHalfSize` (`50`), configurable role names (`0..5`, `6=DISABLED`), secondary helper caps (`1 coop`, `2 trusted`), wilderness/flag defaults, blueprint paste tick budget
- [x] Void Skyblock `ChunkGenerator` + custom dimensions (`ogsky:overworld`, `ogsky:nether`, `ogsky:end`) with 1:1 island-column portal routing
- [x] Compact RAM-first `Island` model (`O(1)` grid index, bitmask settings/state, `byte[]` protection flag table, primitive member arrays) + async `core.db` persistence & `cellSize` world-lock
- [x] `.nbt` `StructureTemplate` blueprint bundle loader (`config/ogsky/blueprints/<bundle>/`) + tick-budgeted paster (eager Overworld + **lazy Nether/End pasting** on first portal entry)
- [x] Role (`byte 0..5`) & membership rules (1 primary island per player + capped secondary `coop`/`trusted`) + full BentoBox-aligned `OgskyEventBus` lifecycle/team/boundary/flag events (server-thread dispatched)
- [x] `FlagRegistry` (`PROTECTION`, `SETTING`, `WORLD_SETTING`) + comprehensive BentoBox-aligned protection listeners (blocks, containers, entities, redstone, liquids, pistons, explosions, enter/exit lock & bans, void teleport rescue)
- [x] `IslandCommandRegistry`: Brigadier `/island` (`/is`) + `/isadmin` (`/isa`) command abstraction supporting subcommand registration by optional modules
- [x] Declarative GUI API (`OgskyCore.api().guis()` / `core.api.gui`, see [`docs/gui-api.md`](gui-api.md)) allowing optional modules to contribute sub-screens and island summary lines with safe `-Pmodules=core,<mod>` fallback

## Phase 2 — `panel`: Server-Side Tabbed Control Panel & Shared GUI Renderer
- [x] Expand `PanelSnapshot` ViewModel to `TabbedPanel` model (tabs for Actions, Protection Flags, Settings Flags, Members/Coop/Trusted, Blueprints/Info + pagination)
- [x] Hardened vanilla `ChestMenu` interactive renderer (`OgskyReadOnlyChestMenu` + `OgskyPanelMenu` + `PanelGuiHost` / `OgskyCustomGuiMenu`) with rank/flag mutation feedback, two-click confirm, and anti-dupe/swap/creative protections
- [x] Register `/is panel` & `/is settings` via `core.commands()` + player UI preference persistence in `panel.db`

## Phase 3 — `level`: Island Level & Leaderboard
- [x] Configurable `config/ogsky/level.json` block-value table (`100` pts/level formula & scoring-only `blockPointLimits` caps, cleanly separated from `modules/limits` physical placement limits) & chunk-batched async island scanner (`core.scans()` / `IslandScanService` covering shared 3 dimensions)
- [x] `level.db` storage, `/is level`, `/is top`, `/is values`, and `/isadmin level scan|scanall|reload` via `core.commands()`, orphan island cleanup on startup & `IslandDeletedEvent` listener
- [x] Panel & GUI integration via `core.guis()` (`ogsky_level:summary`, `ogsky_level:top` paged screen, `ogsky_level:detail` breakdown screen, `ogsky_level:values` paged block-worth screen) + `IslandActionDescriptor` (`ogsky:island_level`, `ogsky_level:island_top`, `ogsky_level:block_values`) + `IslandLevelProvider` service & `IslandLevelCalculatedEvent` (with chat fallback when `ogsky-panel` is absent)

## Phase 4 — `limits`: Physical Block & Entity Placement Limits
- [x] Core module-agnostic `WorldMutationService` (`core.mutations()`) with volatile fast-path mixin guards/observers (`BlockPlacementGuard`, `BlockChangeObserver`, `EntitySpawnGuard`, `EntityLifecycleObserver`) covering player placement, dispensers, liquid generators (`LiquidBlockGeneratorMixin`, `LavaFluidGeneratorMixin`), breeding, golems/withers, natural spawns, and cross-border movement
- [x] Extended `IslandScanService.scanIslandWithEntities(...)` for 3-dimension loaded + disk entity counting alongside block scanning
- [x] `config/ogsky/limits.json`: multi-bucket rules (block/entity exact IDs, `#tags`, custom groups), fluid generator policies (`CANCEL`, `FALLBACK_TO_STONE`, `FULL_FALLBACK_WITH_AIR`), base multi-block part filtering, `rules_hash` resync tracking, and optional `IslandLevelProvider` level-scaling bonus
- [x] `limits.db` async batch persistence + protection flag `ogsky_limits:view_limits` + `/is limits` (`blocks|entities|chat|search`) & `/isadmin limits reload|rescanall|rescan|inspect`
- [x] Panel & GUI integration (`ogsky_limits:summary`, filterable/sortable `ogsky_limits:overview`, action `ogsky_limits:island_limits`) with chat fallback when `ogsky-panel` is absent

## Phase 5+ — Additional Modules & Optional Client Companion
- [ ] Additional Skyblock modules (challenges, upgrades, warp/visit, generator/cobble ore upgrades)
- [ ] Optional `modules/client-ui` mod (detectable via `ServerPlayNetworking.canSend`) rendering custom client `Screen`s from `PanelSnapshot` / `GuiScreenModel`
