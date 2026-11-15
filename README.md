# Ogsky

**Ogsky** is a server-side, compile-time modular Skyblock mod for **Minecraft 26.3 (Fabric, Java 25)**.

## Documentation

- [Agent & Development Rules](AGENTS.md)
- [Architecture Overview](docs/architecture.md)
- [Island & World Specification](docs/island-spec.md)
- [Flags, Protections, Commands & Events](docs/flags-and-events.md)
- [Development Roadmap](docs/roadmap.md)

## Quick Build & Verification

```bash
# Run unit tests across all modules
./gradlew test

# Run headless dedicated-server GameTests
./gradlew runGameTest

# Build with core only vs. default enabled modules (gradle.properties)
./gradlew build -Pmodules=core
./gradlew build
```

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.
