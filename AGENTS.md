# Ogsky Development Guide (`AGENTS.md`)

Ogsky is a **server-side, compile-time modular Skyblock mod** for **Minecraft `26.3`** (unobfuscated Mojang mappings), **Fabric Loader `0.20.1`**, **Fabric API `0.166.2+26.3`**, and **Java `25`**.

## Non-Negotiable Architecture Rules

1. **Canonical Domain Specifications (`docs/island-spec.md` & `docs/flags-and-events.md`):**
   - Follow [docs/island-spec.md](docs/island-spec.md) for Skyblock grid placement, shared 3-dimension single-`Island` model (`ogsky:overworld`, `ogsky:nether`, `ogsky:end`) with lazy Nether/End `.nbt` structure loading, roles (`byte 0..5`), membership limits, `.nbt` blueprint bundles with tick-budgeted placement, and RAM-first primitive storage (`byte[]` / bitmasks, zero boxing).
   - Follow [docs/flags-and-events.md](docs/flags-and-events.md) for the BentoBox-aligned 3-tier flag system (`PROTECTION`, `SETTING`, `WORLD_SETTING` via `FlagRegistry`), full protection matrix, server-thread `OgskyEventBus` domain events, and `/is` Brigadier subcommand abstraction (`IslandCommandRegistry`).
2. **Dedicated Server Only (Current Phase):**
   - All modules use `loom.serverOnlyMinecraftJar()` and `"environment": "server"` in `fabric.mod.json`.
   - Never add `src/client` or import `net.minecraft.client.*` in server modules.
3. **Strict Module Boundaries (`modules/*`):**
   - `modules/core` (`ogsky-core`) exposes public contracts under `me.ogsammaenr.ogsky.core.api.*` and owns Skyblock worldgen, blueprints/paster, island lifecycle, flags & protection, `/is` command tree root, event bus, service registry, action registry, and SQLite database lifecycle.
   - `modules/core` MUST NEVER depend on or import any optional module (`panel`, `level`, etc.).
   - Optional modules (`modules/panel`, `modules/level`, future modules) depend **only** on `:modules:core` (`me.ogsammaenr.ogsky.core.api.*`). They MUST NEVER depend on or import each other.
   - Cross-module communication happens exclusively through `OgskyCore.api().events()`, `OgskyCore.api().services()`, `OgskyCore.api().actions()`, `OgskyCore.api().guis()`, `FlagRegistry`, and `IslandCommandRegistry`.
   - Optional modules (`level`, future `warps`, etc.) MUST NOT build custom `ChestMenu`/`Slot`/inventory mixins of their own. All custom module GUIs and `/is panel` summary lines go through `OgskyCore.api().guis()` ([docs/gui-api.md](docs/gui-api.md)) and fall back gracefully to chat output when `core.guis().isAvailable()` is `false`.
4. **Compile-Time Module Selection (`-Pmodules=...`):**
   - `settings.gradle` always includes all modules so IDE/agents index everything.
   - Root `build.gradle` bundles only selected modules (default from `gradle.properties` `enabled_modules`; `core` is always included) via Jar-in-Jar (`include`).
   - Any feature added to an optional module must allow the server to boot and function cleanly when only `core` is bundled (`-Pmodules=core`).
5. **Per-Module SQLite Databases & RAM Hot Path:**
   - Island hot-path state (`core`) lives in compact primitive RAM structures (zero `Byte` boxing, relative spawn offsets, bitmask flags, slot-indexed `byte[]` protection ranks); SQLite acts as async persistent backing store.
   - Each module persists its own state in `<world>/ogsky/data/<moduleId>.db` via `OgskyCore.api().databases().registerModule(...)`.
   - Never cross-query another module's `.db` file. Cross-module keys are `island_id` (`UUID`) and `player_uuid` (`UUID`) synchronized via domain events (e.g. `IslandDeletedEvent`) and startup/lazy orphan reconciliation.
   - Never run blocking SQLite queries/writes (`queryBlocking`/`executeBlocking`) on the server tick thread during gameplay; use `queryAsync`/`executeAsync` and marshal world updates & event publishing back onto `server.execute(...)`.
6. **Hybrid GUI Readiness (`core.api.gui` + `ogsky-panel`):**
   - Build server-side panel features around UI-agnostic, tabbed `PanelSnapshot` / ViewModel data (adapting BentoBox's `TabbedPanel`) and `core.api.gui` (`GuiScreenModel` / `GuiButton` / `GuiService`, see [docs/gui-api.md](docs/gui-api.md)) so vanilla clients render via `ogsky-panel`'s shared dupe-proof `ChestMenu` host (`OgskyReadOnlyChestMenu`) and a future optional client mod can render custom `Screen`s from the same server-validated state.

## Minecraft 26.3 Verification Rule

- Minecraft `26.3` is unobfuscated and newer than pre-trained assumptions. **Never guess MC 26.3 or Fabric API signatures.**
- Before writing Minecraft/Fabric integrations or mixins, verify exact class/method signatures using the `mc-source-lookup` skill (generated Loom server sources via `genSources` or `javap` on cached JARs).

## Mandatory Verification Gates

Before finishing any code change, run and verify:

1. `./gradlew test` — JUnit unit tests across all modules.
2. `./gradlew runGameTest` — Headless dedicated-server GameTests (default bundle).
3. `./gradlew build -Pmodules=core` and `./gradlew build` — Ensure both minimal (`core`-only) and full module bundles build cleanly.
4. When deploying to the host test server: `./gradlew deployServer` (copies the bundled `ogsky-<version>.jar` to `/mc-server-mods` or `-PserverModsDir=...`).
