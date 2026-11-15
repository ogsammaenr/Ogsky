# Ogsky Architecture

## Overview

Ogsky is a server-side, compile-time modular Skyblock mod for **Minecraft 26.3 (Fabric)**, adapting proven architectural patterns from **BentoBox / BSkyBlock** (3-tier flags, rich domain events, `.nbt` blueprint bundles, composable subcommands, and tabbed panels) into a lightweight, RAM-first native Fabric codebase.

```text
+---------------------------------------------------------------+
|               Root Bundle: ogsky-<version>.jar                |
|  Selects modules at build time via -Pmodules=core,panel,level |
|                                                               |
|  META-INF/jars/                                               |
|   ├── ogsky-core-<version>.jar  (+ sqlite-jdbc Jar-in-Jar)    |
|   ├── ogsky-panel-<version>.jar (optional)                    |
|   └── ogsky-level-<version>.jar (optional)                    |
+---------------------------------------------------------------+
```

## Module Dependency Rules

- `modules/core` (`ogsky-core`): Owns Skyblock worldgen (`ogsky:overworld`, `ogsky:nether`, `ogsky:end`), `O(1)` `IslandGrid` + RAM `IslandCache`, `.nbt` blueprint bundle loader & tick-budgeted paster (with lazy Nether/End pasting), 3-tier protection engine (`FlagRegistry`), Brigadier `/is` command tree abstraction (`IslandCommandRegistry`), `OgskyEventBus`, `ServiceRegistry`, `IslandActionRegistry`, and `DatabaseService`. Zero dependency on optional modules.
- `modules/panel` (`ogsky-panel`): Depends only on `core`. Builds UI-agnostic, **tabbed/paginated `PanelSnapshot` ViewModels** combining `core.actions()`, `core.flags()`, island members, and optional service data, renders vanilla `ChestMenu` (and will later emit optional `CustomPacketPayload` packets for a companion client UI mod). Also registers `/is panel` & `/is settings` via `core.commands()`.
- `modules/level` (`ogsky-level`): Depends only on `core`. Calculates and stores island levels in `level.db`, reacts to `IslandDeletedEvent`, contributes `ogsky:island_level` action descriptor into `core.actions()`, and registers `/is level` & `/is top` via `core.commands()`.

> Related specs:
> - Domain model, grid, lazy Nether/End, `.nbt` blueprints & RAM layout: [docs/island-spec.md](island-spec.md)
> - 3-tier flags, protections, events & command contracts: [docs/flags-and-events.md](flags-and-events.md)
> - Implementation phases: [docs/roadmap.md](roadmap.md)

## Per-Module SQLite Persistence

- Each module stores data in `<world>/ogsky/data/<moduleId>.db` managed by `SqliteDatabaseService`.
- Connections run with `PRAGMA journal_mode=WAL`, `PRAGMA synchronous=NORMAL`, `PRAGMA busy_timeout=5000`, and `PRAGMA foreign_keys=ON`.
- Each database serializes reads/writes onto its own daemon thread (`ogsky-db-<moduleId>`) to avoid blocking the server tick thread and prevent `SQLITE_BUSY` write races.
- Connections open on `ServerLifecycleEvents.SERVER_STARTING` and flush + checkpoint + close on `ServerLifecycleEvents.SERVER_STOPPED`.
- Removing a module from `-Pmodules=...` preserves its `.db` file untouched on disk.
- Domain event invariant: async DB callbacks MUST marshal back to the server thread (`server.execute(...)`) before publishing to `OgskyEventBus`.

## Hybrid GUI Strategy (`TabbedPanel` → `PanelSnapshot`)

Inspired by BentoBox's `TabbedPanel` & `PanelItem` pattern:

1. **Server-Validated Tabbed ViewModel (`PanelSnapshot`):**
   - `ogsky-panel` builds UI-agnostic tabs (`Actions`, `Protections`, `Settings`, `Members`, `Blueprints` / module tabs) from `core.actions()`, `core.flags()`, island state, and optional registered services.
2. **Phase 1–2 (Server-Only Vanilla Clients):**
   - `panel` renders `PanelSnapshot` tabs & paginated items via server-side `ChestMenu` + click handlers executing server-validated flag/role/action mutations.
3. **Phase 3+ (Optional Client Companion Mod):**
   - A future `modules/client-ui` mod registers custom `CustomPacketPayload` channels. When `ServerPlayNetworking.canSend(player, ...)` is true, the server sends `PanelSnapshot` over the payload instead of opening a `ChestMenu`; vanilla clients seamlessly fall back to `ChestMenu`.
