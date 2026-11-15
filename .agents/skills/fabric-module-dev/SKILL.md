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
   - Check `docs/island-spec.md` and `docs/flags-and-events.md` before adding cross-module domain reactions, flags, or `/is` subcommands.
   - Register schema migrations via `OgskyCore.api().databases().registerModule("<name>", migrations)`.
   - Never import another optional module. Use:
     - `OgskyCore.api().events().subscribe(...)` for lifecycle/team/flag reactions (always handle `IslandDeletedEvent` if storing island-keyed data; keep listeners on main server thread).
     - `OgskyCore.api().actions().register(...)` to contribute entries to `ogsky-panel`.
     - `OgskyCore.api().services().register(...)` / `find(...)` for optional capabilities.
     - *(Phase 1+)* Core `FlagRegistry` for custom `PROTECTION` / `SETTING` / `WORLD_SETTING` flags and Core `IslandCommandRegistry` to register `/is <subcommand>` nodes.
4. **Add Tests:**
   - Unit tests under `modules/<name>/src/test/java/...` using `OgskyCoreApiImpl` + `@TempDir` for isolated SQLite testing.
   - Integration assertions in `src/gametest/java/me/ogsammaenr/ogsky/gametest/OgskyBundleGameTests.java` gated with `FabricLoader.getInstance().isModLoaded("ogsky-<name>")`.
5. **Verify Both Combinations:**
   - `./gradlew test`
   - `./gradlew build -Pmodules=core`
   - `./gradlew build -Pmodules=core,<name>`
   - `./gradlew build`
