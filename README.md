# Ogsky

**Ogsky** is a server-side, compile-time modular Skyblock mod for **Minecraft 26.3 (Fabric, Java 25)**.

## Bundled Modules

| Module | Artifact | Role |
| :--- | :--- | :--- |
| `core` | `ogsky-core` | Skyblock dimensions (`ogsky:overworld`, `ogsky:nether`, `ogsky:end`), `O(1)` grid & RAM storage, `.nbt` blueprint paster, 3-tier flags/protection, event/service/command/DB/scan/mutation/GUI APIs |
| `panel` | `ogsky-panel` | Tabbed `PanelSnapshot` control panel (`/is panel`, `/is settings`) and shared anti-dupe `ChestMenu` `GuiHost` |
| `level` | `ogsky-level` | Block-worth evaluation, `/is level` / `/is top` / `/is values`, `IslandLevelProvider` service & `level.db` |
| `limits` | `ogsky-limits` | Physical per-island block & entity placement limits, liquid generator policies, optional level scaling & `limits.db` |
| `warps` | `ogsky-warps` | Multi-warp showcase, category/sort GUIs, warmup/cooldown safe teleport, particle markers, `manage_warps`/`use_warp` flags & `warps.db` |
| `border` | `ogsky-border` | Per-player client-side Vanilla `WorldBorder` island boundary display (`Blue`/`Green`/`Red`), `/is border` command, panel action toggle & `border.db` |

## Documentation

- [Agent & Development Rules](AGENTS.md)
- [Core Public API Reference](docs/core-api.md)
- [Module Architecture, Connections & Extension Guide](docs/modules.md)
- [Architecture Overview](docs/architecture.md)
- [Island & World Specification](docs/island-spec.md)
- [Flags, Protections, Commands & Events](docs/flags-and-events.md)
- [Shared Declarative GUI API](docs/gui-api.md)
- [Development Roadmap](docs/roadmap.md)

## Quick Build & Verification

```bash
# Run unit tests across all modules
./gradlew test

# Run headless dedicated-server GameTests (full default bundle or minimal subsets)
./gradlew runGameTest
./gradlew runGameTest -Pmodules=core

# Build with core only, custom subset, or default enabled modules (gradle.properties)
./gradlew build -Pmodules=core
./gradlew build -Pmodules=core,limits
./gradlew build

# Deploy bundled JAR to test server mods directory
./gradlew deployServer
```

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.
