---
name: fabric-verify-deploy
description: >-
  Use this skill to run JUnit tests, headless Fabric GameTests, selective -Pmodules builds,
  inspect Jar-in-Jar packaging, or deploy the bundled JAR to the test server mods folder.
---

# Verification, Selective Build, and Server Deployment

## 1. Fast Unit Tests (JUnit)

```bash
./gradlew test --console=plain
```

## 2. Headless Dedicated Server GameTests

```bash
./gradlew runGameTest --console=plain
```

To verify `core` boots cleanly without optional modules:

```bash
./gradlew runGameTest -Pmodules=core --console=plain
```

## 3. Selective Module Builds (`-Pmodules=...`)

Build all default modules (configured by `enabled_modules` in `gradle.properties`):

```bash
./gradlew build --console=plain
```

Build a custom subset (e.g., `core` + `limits` only, or `core` + `level` only):

```bash
./gradlew build -Pmodules=core,limits --console=plain
./gradlew runGameTest -Pmodules=core,limits --console=plain
```

> Note: Before committing or after interrupted GameTest runs, clean generated artifacts if needed (`rm -rf build/run/gameTest/world build/run/gameTest/config`).

Verify nested Jar-in-Jar packaging inside the final bundle:

```bash
unzip -l build/libs/ogsky-1.0.0.jar | rg "META-INF/jars|fabric.mod.json"
```

## 4. Deploy to Test Server

Copies the main bundle JAR (`ogsky-<version>.jar`, excluding `-sources.jar`) to `/mc-server-mods` after removing stale `ogsky-*.jar` files:

```bash
./gradlew deployServer --console=plain
```

Override target directory if needed:

```bash
./gradlew deployServer -Pmodules=core,panel -PserverModsDir=/path/to/mods --console=plain
```
