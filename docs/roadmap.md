# Ogsky Development Roadmap

## Phase 0 — Modular & Agentic Foundation (Current)
- [x] Multi-project Gradle structure (`modules/core`, `modules/panel`, `modules/level`)
- [x] Compile-time module selection via `-Pmodules=...` and Fabric Jar-in-Jar bundling
- [x] `OgskyCoreApi`: `OgskyEventBus`, `ServiceRegistry`, `IslandActionRegistry`, `DatabaseService` (SQLite + WAL + migrations)
- [x] JUnit unit tests + headless `runGameTest` server verification + `deployServer` task
- [x] Agentic rules (`AGENTS.md`) & skills (`.agents/skills/`)
- [x] BentoBox/BSkyBlock architectural integration specs (`docs/island-spec.md`, `docs/flags-and-events.md`, `docs/architecture.md`)

## Phase 1 — `core`: Worldgen, Blueprints, Flags, Events & Protection
See canonical rules in [`docs/island-spec.md`](island-spec.md) and [`docs/flags-and-events.md`](flags-and-events.md).

- [x] `config/ogsky/core.json`: grid `cellSize` (`1024`), `initialHalfSize` (`50`), configurable role names (`0..5`), secondary helper caps (`1 coop`, `2 trusted`), wilderness/flag defaults, blueprint paste tick budget
- [x] Void Skyblock `ChunkGenerator` + custom dimensions (`ogsky:overworld`, `ogsky:nether`, `ogsky:end`) with 1:1 island-column portal routing
- [x] Compact RAM-first `Island` model (`O(1)` grid index, bitmask settings/state, `byte[]` protection flag table, primitive member arrays) + async `core.db` persistence & `cellSize` world-lock
- [x] `.nbt` `StructureTemplate` blueprint bundle loader (`config/ogsky/blueprints/<bundle>/`) + tick-budgeted paster (eager Overworld + **lazy Nether/End pasting** on first portal entry)
- [x] Role (`byte 0..5`) & membership rules (1 primary island per player + capped secondary `coop`/`trusted`) + full BentoBox-aligned `OgskyEventBus` lifecycle/team/boundary/flag events (server-thread dispatched)
- [x] `FlagRegistry` (`PROTECTION`, `SETTING`, `WORLD_SETTING`) + comprehensive BentoBox-aligned protection listeners (blocks, containers, entities, redstone, liquids, pistons, explosions, enter/exit lock & bans, void teleport rescue)
- [x] `IslandCommandRegistry`: Brigadier `/island` (`/is`) + `/isadmin` command abstraction supporting subcommand registration by optional modules
- [x] Declarative GUI API (`OgskyCore.api().guis()` / `core.api.gui`, see [`docs/gui-api.md`](gui-api.md)) allowing optional modules to contribute sub-screens and island summary lines with safe `-Pmodules=core,<mod>` fallback

## Phase 2 — `panel`: Server-Side Tabbed Control Panel & Shared GUI Renderer
- [x] Expand `PanelSnapshot` ViewModel to `TabbedPanel` model (tabs for Actions, Protection Flags, Settings Flags, Members/Coop/Trusted, Blueprints/Info + pagination)
- [x] Hardened vanilla `ChestMenu` interactive renderer (`OgskyReadOnlyChestMenu` + `OgskyPanelMenu` + `PanelGuiHost` / `OgskyCustomGuiMenu`) with rank/flag mutation feedback, two-click confirm, and anti-dupe/swap/creative protections
- [x] Register `/is panel` & `/is settings` via `core.commands()` + player UI preference persistence in `panel.db`

## Phase 3 — `level`: Island Level & Leaderboard
- [ ] Configurable block-value table & chunk-batched async island scanner (covering shared 3 dimensions)
- [ ] `level.db` storage, `/is level` & `/is top` via `core.commands()`, orphan island cleanup on startup & `IslandDeletedEvent` listener
- [ ] Panel & GUI integration via `core.guis()` (`IslandSummaryContributor`, `/is top` paged screen) + `IslandActionDescriptor` + optional `IslandLevelProvider` service (with chat fallback when `ogsky-panel` is absent)

## Phase 4+ — Additional Modules & Optional Client Companion
- [ ] Additional Skyblock modules (challenges, upgrades, warp/visit, generator/cobble ore upgrades)
- [ ] Optional `modules/client-ui` mod (detectable via `ServerPlayNetworking.canSend`) rendering custom client `Screen`s from `PanelSnapshot`
