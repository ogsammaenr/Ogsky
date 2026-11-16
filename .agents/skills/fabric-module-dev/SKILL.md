---
name: fabric-module-dev
description: >-
  Use this skill when creating a new optional Skyblock module under modules/<name>
  or wiring cross-module communication, database migrations, and panel actions.
---

# Creating and Wiring a New Ogsky Module

All optional features live in isolated Gradle subprojects under `modules/<name>` and depend strictly on `:modules:core`.

## Checklist for Adding `modules/<name>`

1. **Register the module in Gradle:**
   - Add `include 'modules:<name>'` in `settings.gradle`.
   - Add `'<name>'` to `availableModules` in root `build.gradle`.
   - Add `<name>` to `enabled_modules` in `gradle.properties` if it should be included in default builds.
   - Create `modules/<name>/build.gradle`:
     ```groovy
     dependencies {
         implementation project(':modules:core')
     }
     ```
2. **Add Fabric module metadata:**
   - Create `modules/<name>/src/main/resources/fabric.mod.json` with:
     - `"id": "ogsky-<name>"`
     - `"environment": "server"`
     - `"depends": { "fabricloader": ">=0.20.1", "minecraft": "~26.3", "java": ">=25", "fabric-api": "*", "ogsky-core": "*" }`
     - `"entrypoints": { "ogsky-module": ["me.ogsammaenr.ogsky.<name>.Ogsky<Name>Mod"] }` implementing `OgskyModule` (loaded deterministically by `ogsky-core` after core bootstrap; do NOT use Fabric's unordered `"main"` entrypoint in optional modules).
3. **Wire SQLite & Cross-Module Contracts:**
   - Review [docs/core-api.md](../../../docs/core-api.md), [docs/modules.md](../../../docs/modules.md), [docs/island-spec.md](../../../docs/island-spec.md), and [docs/flags-and-events.md](../../../docs/flags-and-events.md) before adding cross-module domain reactions, flags, mutation hooks, or `/is` subcommands.
   - Register schema migrations via `OgskyCore.api().databases().registerModule("<name>", migrations)`.
   - Never import another optional module. Use:
     - `OgskyCore.api().events().subscribe(...)` for lifecycle/team/flag reactions (always handle `IslandDeletedEvent` and `IslandResetEvent` if storing island-keyed data; keep listeners on main server thread).
     - `OgskyCore.api().actions().register(...)` to contribute entries to `ogsky-panel`.
     - `OgskyCore.api().guis().registerScreen(...)` and `registerSummaryContributor(...)` to expose interactive sub-screens and `/is panel` slot-8 summary lines without depending on `ogsky-panel` (always fall back to chat when `!OgskyCore.api().guis().isAvailable()`; never create custom `ChestMenu` subclasses or inventory mixins outside `modules/panel`). See [docs/gui-api.md](../../../docs/gui-api.md).
     - `OgskyCore.api().services().register(...)` / `find(...)` for optional capabilities (e.g., `IslandLevelProvider`).
     - `OgskyCore.api().mutations()` (`WorldMutationService`) for module-agnostic pre-placement/pre-spawn guards and committed block/entity lifecycle observers, and `OgskyCore.api().scans()` (`scanIsland` / `scanIslandWithEntities`) for 3-dimension chunk-budgeted counting.
     - Core `FlagRegistry` (`core.flags()`) for custom `PROTECTION` / `SETTING` / `WORLD_SETTING` flags and Core `IslandCommandRegistry` (`core.commands()`) to register `/is <subcommand>` and `/isadmin <subcommand>` nodes.
4. **Add Tests:**
   - Unit tests under `modules/<name>/src/test/java/...` using `OgskyCoreApiImpl` + `@TempDir` for isolated SQLite testing.
   - Integration assertions in a dedicated GameTest class under `src/gametest/java/me/ogsammaenr/ogsky/gametest/` (using `GameTestSupport`, registered in `src/gametest/resources/fabric.mod.json` under `"fabric-gametest"`, and gated with `FabricLoader.getInstance().isModLoaded("ogsky-<name>")`).
5. **Verify Combinations:**
   - `./gradlew test`
   - `./gradlew runGameTest`
   - `./gradlew build -Pmodules=core`
   - `./gradlew build -Pmodules=core,<name>` (e.g., `-Pmodules=core,limits`)
   - `./gradlew build`
