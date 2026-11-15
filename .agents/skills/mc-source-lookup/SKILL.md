---
name: mc-source-lookup
description: >-
  Use this skill before writing Minecraft 26.3, Fabric API, or SQLite integration code
  to verify exact class names, method signatures, and bytecode/source implementations
  from local Loom caches instead of guessing.
---

# Minecraft 26.3 & Fabric API Source Lookup

Never assume method names or package layouts for Minecraft `26.3` (unobfuscated Mojang mappings) or Fabric API `0.166.2+26.3`.

## 1. Generate Decompiled Minecraft Server Sources (If Not Yet Generated)

```bash
./gradlew :modules:core:genSources --console=plain
```

## 2. Locate Cached JARs and Sources

- **Minecraft decompiled server sources:** `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-server-*/26.3/minecraft-server-*-26.3-sources.jar`
- **Minecraft server/common bytecode JAR:** `/home/excalibur/.gradle/caches/fabric-loom/26.3/minecraft-server.jar` (or `minecraft-common.jar`)
- **Fabric API modules:** `/home/excalibur/.gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api/`

## 3. Read Decompiled Java Source or Inspect Bytecode with `javap`

Read a decompiled MC `26.3` server class directly from the generated sources JAR:

```bash
SRC_JAR=$(find .gradle/loom-cache/minecraftMaven -name 'minecraft-server-*-sources.jar' | head -n 1)
unzip -p "$SRC_JAR" net/minecraft/world/level/chunk/ChunkGenerator.java | head -n 120
```

Search class names inside the sources JAR or bytecode JAR:

```bash
SRC_JAR=$(find .gradle/loom-cache/minecraftMaven -name 'minecraft-server-*-sources.jar' | head -n 1)
unzip -l "$SRC_JAR" | rg -i "ChestMenu|SimpleMenuProvider|ChunkGenerator"
```

Inspect bytecode signatures quickly with `javap`:

```bash
javap -cp /home/excalibur/.gradle/caches/fabric-loom/26.3/minecraft-server.jar net.minecraft.world.level.chunk.ChunkGenerator
```

Inspect a Fabric API module JAR:

```bash
JAR=$(find /home/excalibur/.gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api/fabric-networking-api-v1 -name '*.jar' ! -name '*-sources.jar' | head -n 1)
unzip -l "$JAR"
javap -cp "$JAR" net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
```
