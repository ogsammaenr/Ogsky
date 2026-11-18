package me.ogsammaenr.ogsky.core;

import me.ogsammaenr.ogsky.core.api.blueprint.BlueprintStructureData;
import me.ogsammaenr.ogsky.core.api.island.RelativeSpawn;
import me.ogsammaenr.ogsky.core.internal.blueprint.BlueprintBundle;
import me.ogsammaenr.ogsky.core.internal.blueprint.BlueprintBundleRegistry;
import me.ogsammaenr.ogsky.core.internal.blueprint.BlueprintSignSupport;
import me.ogsammaenr.ogsky.core.internal.blueprint.CompressedBlueprint;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfig;
import me.ogsammaenr.ogsky.core.internal.flag.BuiltinFlags;
import me.ogsammaenr.ogsky.core.internal.flag.SimpleFlagRegistry;
import me.ogsammaenr.ogsky.core.internal.island.Island;
import me.ogsammaenr.ogsky.core.internal.world.OgskyDimensions;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueprintBundleRegistryTest {
	@TempDir
	Path tempDir;

	@Test
	void reloadDiscoversAndCompressesBundlesInRamWithoutGeneratingDefaultNbt() throws Exception {
		Path blueprintsRoot = this.tempDir.resolve("blueprints");
		BlueprintBundleRegistry registry = new BlueprintBundleRegistry(blueprintsRoot, "default");

		// Reloading an empty folder must not auto-generate a starter default/overworld.nbt file.
		registry.reload();
		assertTrue(Files.isDirectory(blueprintsRoot));
		assertFalse(Files.exists(blueprintsRoot.resolve("default").resolve("overworld.nbt")));
		assertTrue(registry.listAll().isEmpty());

		// Add two bundles: one with bundle.json (permission-gated) and one without bundle.json (defaults).
		Path vipDir = blueprintsRoot.resolve("vip_island");
		Files.createDirectories(vipDir);
		Files.writeString(vipDir.resolve("bundle.json"), """
			{
			  "id": "vip_island",
			  "displayName": "VIP Oasis",
			  "requiresPermission": true,
			  "order": 2,
			  "overworld": {
			    "file": "custom_ow.nbt",
			    "centerHorizontally": true,
			    "offsetY": -3,
			    "spawnOffsetX": 1,
			    "spawnOffsetY": 2,
			    "spawnOffsetZ": -1
			  },
			  "nether": {
			    "file": "../../escape.nbt"
			  }
			}
			""");
		Path customOwFile = vipDir.resolve("custom_ow.nbt");
		writeStructureNbt(customOwFile, new Vec3i(7, 5, 9), List.of(), List.of());

		Path classicDir = blueprintsRoot.resolve("classic");
		Files.createDirectories(classicDir);
		writeStructureNbt(classicDir.resolve("overworld.nbt"), new Vec3i(3, 3, 3), List.of(), List.of());
		writeStructureNbt(classicDir.resolve("nether.nbt"), new Vec3i(3, 3, 3), List.of(), List.of());

		// Corrupt .nbt file should be rejected cleanly without failing server startup/reload.
		Path brokenDir = blueprintsRoot.resolve("broken");
		Files.createDirectories(brokenDir);
		Files.write(brokenDir.resolve("overworld.nbt"), new byte[] { 0x01, 0x02 });

		registry.reload();

		List<BlueprintBundle> all = registry.listAll();
		assertEquals(2, all.size());
		assertEquals("classic", all.get(0).id());
		assertEquals("vip_island", all.get(1).id());

		BlueprintBundle vip = registry.find("vip_island").orElseThrow();
		assertEquals("VIP Oasis", vip.metadata().displayName);
		assertTrue(vip.metadata().requiresPermission);
		assertEquals("ogsky.blueprint.vip_island", vip.metadata().permissionNode);
		assertTrue(vip.hasOverworld());
		assertFalse(vip.hasNether(), "Path traversal entries outside bundle dir must be rejected");
		assertNull(vip.end());

		// Verify .nbt is compressed & self-contained in RAM even if disk file is removed after reload.
		Files.delete(customOwFile);
		CompressedBlueprint vipOw = vip.overworld();
		assertNotNull(vipOw);
		assertTrue(vipOw.compressedByteCount() > 0);
		assertEquals(new Vec3i(7, 5, 9), vipOw.size());
		assertEquals(7, vipOw.readDecompressedTag().getListOrEmpty("size").getIntOr(0, 0));

		BlueprintBundle classic = registry.find("classic").orElseThrow();
		assertTrue(classic.hasOverworld());
		assertTrue(classic.hasNether());
		assertFalse(classic.hasEnd());
		assertFalse(classic.metadata().requiresPermission);

		// Fallback centering & spawn when neither bedrock nor [SPAWN] sign is present.
		Island island = createTestIsland(512, 512, "vip_island");
		BlockPos pasteOrigin = vip.computePasteOrigin(
			island,
			OgskyDimensions.OVERWORLD,
			vipOw,
			68,
			-64,
			319
		);
		assertEquals(new BlockPos(512 - 3, 65, 512 - 4), pasteOrigin);

		RelativeSpawn spawn = vip.computeSpawn(island, OgskyDimensions.OVERWORLD, vipOw, pasteOrigin, 68);
		assertEquals(513.5D, spawn.worldX(island.centerX()));
		assertEquals(70.0D, spawn.worldY());
		assertEquals(511.5D, spawn.worldZ(island.centerZ()));
	}

	@Test
	void bedrockAnchorsCenterAndSpawnSignsExtractExactPositionAndFrontYaw() throws Exception {
		Path blueprintsRoot = this.tempDir.resolve("blueprints_anchors");
		BlueprintBundleRegistry registry = new BlueprintBundleRegistry(blueprintsRoot, "default");
		Path bundleDir = blueprintsRoot.resolve("default");
		Files.createDirectories(bundleDir);

		// Asymmetric 7x6x7 template with bedrock at (1, 2, 5) and standing [SPAWN] sign at (4, 4, 2) facing EAST (rotation=12 -> -90 deg).
		CompoundTag spawnSignNbt = signNbt(List.of("§e[spawn]", "", "", ""), List.of("", "", "", ""));
		writeStructureNbt(
			bundleDir.resolve("overworld.nbt"),
			new Vec3i(7, 6, 7),
			List.of(
				paletteEntry("minecraft:bedrock", Map.of()),
				paletteEntry("minecraft:oak_sign", Map.of("rotation", "12", "waterlogged", "false"))
			),
			List.of(
				blockEntry(1, 2, 5, 0, null),
				blockEntry(4, 4, 2, 1, spawnSignNbt)
			)
		);

		// Wall sign in nether.nbt facing NORTH (facing="north" -> 180 / -180 deg) with bedrock at (2, 1, 2).
		CompoundTag wallSpawnNbt = signNbt(List.of("", " [SPAWN] ", "", ""), List.of("", "", "", ""));
		writeStructureNbt(
			bundleDir.resolve("nether.nbt"),
			new Vec3i(5, 5, 5),
			List.of(
				paletteEntry("minecraft:bedrock", Map.of()),
				paletteEntry("minecraft:crimson_wall_sign", Map.of("facing", "north", "waterlogged", "false"))
			),
			List.of(
				blockEntry(2, 1, 2, 0, null),
				blockEntry(3, 2, 1, 1, wallSpawnNbt)
			)
		);

		// Hanging sign on back text facing WEST (rotation=4 -> 90 deg) in end.nbt.
		CompoundTag hangingSpawnNbt = signNbt(List.of("", "", "", ""), List.of("[SPAWN]", "", "", ""));
		writeStructureNbt(
			bundleDir.resolve("end.nbt"),
			new Vec3i(5, 5, 5),
			List.of(
				paletteEntry("minecraft:bamboo_hanging_sign", Map.of("rotation", "4", "attached", "false", "waterlogged", "false"))
			),
			List.of(
				blockEntry(1, 3, 2, 0, hangingSpawnNbt)
			)
		);

		registry.reload();

		BlueprintBundle bundle = registry.find("default").orElseThrow();
		Island island = createTestIsland(1024, -1024, "default");

		CompressedBlueprint ow = bundle.overworld();
		assertNotNull(ow);
		assertEquals(new BlockPos(1, 2, 5), ow.bedrockOffset());
		assertNotNull(ow.spawnMarker());
		assertEquals(new BlockPos(4, 4, 2), ow.spawnMarker().blockPos());
		assertEquals(-90.0F, ow.spawnMarker().yaw(), 0.01F);

		// Bedrock (1, 2, 5) must land right at (1024, 64, -1024).
		BlockPos owOrigin = bundle.computePasteOrigin(island, OgskyDimensions.OVERWORLD, ow, 64, -64, 319);
		assertEquals(new BlockPos(1024 - 1, 64 - 2, -1024 - 5), owOrigin);
		assertEquals(new BlockPos(1024, 64, -1024), owOrigin.offset(ow.bedrockOffset()));

		// [SPAWN] sign world coords & East facing (-90 deg).
		RelativeSpawn owSpawn = bundle.computeSpawn(island, OgskyDimensions.OVERWORLD, ow, owOrigin, 64);
		assertEquals(1023 + 4 + 0.5D, owSpawn.worldX(island.centerX()));
		assertEquals(62 + 4.0D, owSpawn.worldY());
		assertEquals(-1029 + 2 + 0.5D, owSpawn.worldZ(island.centerZ()));
		assertEquals(-90.0F, owSpawn.yaw(), 2.0F);

		// Nether wall sign facing NORTH -> 180 / -180 deg.
		CompressedBlueprint nether = bundle.nether();
		assertNotNull(nether);
		assertEquals(new BlockPos(2, 1, 2), nether.bedrockOffset());
		BlockPos netherOrigin = bundle.computePasteOrigin(island, OgskyDimensions.NETHER, nether, 64, 0, 255);
		RelativeSpawn netherSpawn = bundle.computeSpawn(island, OgskyDimensions.NETHER, nether, netherOrigin, 64);
		assertEquals(180.0F, Math.abs(netherSpawn.yaw()), 2.0F);

		// End hanging sign facing WEST -> 90 deg.
		CompressedBlueprint end = bundle.end();
		assertNotNull(end);
		assertNull(end.bedrockOffset());
		BlockPos endOrigin = bundle.computePasteOrigin(island, OgskyDimensions.END, end, 64, 0, 255);
		RelativeSpawn endSpawn = bundle.computeSpawn(island, OgskyDimensions.END, end, endOrigin, 64);
		assertEquals(90.0F, endSpawn.yaw(), 2.0F);

		// Welcome line placeholders & legacy '&' colors.
		BlueprintSignSupport.WelcomeContext ctx = new BlueprintSignSupport.WelcomeContext(
			"Ogsam",
			"default",
			"Starter Island",
			island.islandId(),
			island.gridX(),
			island.gridZ()
		);
		assertEquals("§aWelcome §fOgsam §7(Starter Island)", BlueprintSignSupport.formatLine("&aWelcome &f{player} &7({bundle})", ctx));
	}

	@Test
	void previewPngValidationPathTraversalAndStructureExtractionWork() throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();

		Path blueprintsRoot = this.tempDir.resolve("blueprints_preview");
		BlueprintBundleRegistry registry = new BlueprintBundleRegistry(blueprintsRoot, "alpha");
		assertEquals(0L, registry.revision());

		// 1. Valid bundle with default preview.png, stone, air, oak_log, welcome sign, and [SPAWN] sign
		Path alphaDir = blueprintsRoot.resolve("alpha");
		Files.createDirectories(alphaDir);
		byte[] validPng = new byte[] {
			(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
			0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
		};
		Files.write(alphaDir.resolve("preview.png"), validPng);
		writeStructureNbt(
			alphaDir.resolve("overworld.nbt"),
			new Vec3i(4, 3, 4),
			List.of(
				paletteEntry("minecraft:stone", Map.of()),
				paletteEntry("minecraft:air", Map.of()),
				paletteEntry("minecraft:oak_sign", Map.of("rotation", "0", "waterlogged", "false")),
				paletteEntry("minecraft:grass_block", Map.of("snowy", "false"))
			),
			List.of(
				blockEntry(0, 0, 0, 0, null),
				blockEntry(1, 0, 0, 1, null),
				blockEntry(1, 1, 0, 2, signNbt(List.of("[SPAWN]", "", "", ""), List.of("", "", "", ""))),
				blockEntry(2, 1, 0, 2, signNbt(List.of("[WELCOME]", "Hi", "", ""), List.of("", "", "", ""))),
				blockEntry(2, 0, 2, 3, null)
			)
		);

		// 2. Bundle with invalid PNG header -> bundle stays loaded without preview PNG
		Path badPngDir = blueprintsRoot.resolve("bad_png");
		Files.createDirectories(badPngDir);
		Files.writeString(badPngDir.resolve("bundle.json"), """
			{ "id": "bad_png", "previewImage": "fake.png" }
			""");
		Files.write(badPngDir.resolve("fake.png"), new byte[] { 0x47, 0x49, 0x46, 0x38 });
		writeStructureNbt(badPngDir.resolve("overworld.nbt"), new Vec3i(1, 1, 1), List.of(paletteEntry("minecraft:stone", Map.of())), List.of(blockEntry(0, 0, 0, 0, null)));

		// 3. Bundle attempting path traversal previewImage="../../outside.png"
		Path outsidePng = this.tempDir.resolve("outside.png");
		Files.write(outsidePng, validPng);
		Path escapeDir = blueprintsRoot.resolve("escape_png");
		Files.createDirectories(escapeDir);
		Files.writeString(escapeDir.resolve("bundle.json"), """
			{ "id": "escape_png", "previewImage": "../../outside.png" }
			""");
		writeStructureNbt(escapeDir.resolve("overworld.nbt"), new Vec3i(1, 1, 1), List.of(paletteEntry("minecraft:stone", Map.of())), List.of(blockEntry(0, 0, 0, 0, null)));

		registry.reload();
		assertEquals(1L, registry.revision());

		BlueprintBundle alpha = registry.find("alpha").orElseThrow();
		assertTrue(alpha.hasPreviewImage());
		assertTrue(alpha.toView().hasPreviewImage());
		byte[] loadedPng = alpha.findPreviewPngBytes().orElseThrow();
		assertEquals(validPng.length, loadedPng.length);
		loadedPng[0] = 0; // Defensive copy check
		assertEquals((byte) 0x89, alpha.findPreviewPngBytes().orElseThrow()[0]);

		BlueprintStructureData structure = alpha.overworld().extractStructureData(BuiltInRegistries.BLOCK);
		assertEquals(4, structure.sizeX());
		assertEquals(3, structure.sizeY());
		assertEquals(4, structure.sizeZ());
		// Should keep stone, [WELCOME] sign, grass_block; must omit air and [SPAWN] marker sign.
		assertEquals(3, structure.blocks().size());
		assertTrue(structure.blocks().stream().anyMatch(b -> b.state().getBlock().equals(Blocks.STONE) && b.x() == 0 && b.y() == 0 && b.z() == 0));
		assertTrue(structure.blocks().stream().anyMatch(b -> b.state().getBlock().equals(Blocks.OAK_SIGN) && b.x() == 2 && b.y() == 1 && b.z() == 0));
		assertTrue(structure.blocks().stream().anyMatch(b -> b.state().getBlock().equals(Blocks.GRASS_BLOCK) && b.x() == 2 && b.y() == 0 && b.z() == 2));
		assertFalse(structure.blocks().stream().anyMatch(b -> b.x() == 1 && b.y() == 1 && b.z() == 0));

		BlueprintBundle badPng = registry.find("bad_png").orElseThrow();
		assertFalse(badPng.hasPreviewImage());
		assertFalse(badPng.toView().hasPreviewImage());
		assertTrue(badPng.hasOverworld());

		BlueprintBundle escapePng = registry.find("escape_png").orElseThrow();
		assertFalse(escapePng.hasPreviewImage());
		assertTrue(escapePng.hasOverworld());

		registry.reload();
		assertEquals(2L, registry.revision());
	}

	private static Island createTestIsland(int centerX, int centerZ, String bundleId) {
		SimpleFlagRegistry flags = new SimpleFlagRegistry();
		BuiltinFlags.registerAll(flags);
		flags.freeze();
		return new Island(
			UUID.randomUUID(),
			UUID.randomUUID(),
			0,
			0,
			centerX,
			centerZ,
			(short) 50,
			bundleId,
			Instant.now(),
			flags,
			CoreConfig.defaults()
		);
	}

	private static void writeStructureNbt(
		Path file,
		Vec3i size,
		List<CompoundTag> paletteEntries,
		List<CompoundTag> blockEntries
	) throws Exception {
		CompoundTag root = new CompoundTag();
		ListTag sizeList = new ListTag();
		sizeList.add(IntTag.valueOf(size.getX()));
		sizeList.add(IntTag.valueOf(size.getY()));
		sizeList.add(IntTag.valueOf(size.getZ()));
		root.put("size", sizeList);

		ListTag palette = new ListTag();
		palette.addAll(paletteEntries);
		root.put("palette", palette);

		ListTag blocks = new ListTag();
		blocks.addAll(blockEntries);
		root.put("blocks", blocks);
		root.put("entities", new ListTag());
		NbtIo.writeCompressed(root, file);
	}

	private static CompoundTag paletteEntry(String id, Map<String, String> properties) {
		CompoundTag entry = new CompoundTag();
		entry.putString("id", id);
		if (!properties.isEmpty()) {
			CompoundTag props = new CompoundTag();
			properties.forEach(props::putString);
			entry.put("properties", props);
		}
		return entry;
	}

	private static CompoundTag blockEntry(int x, int y, int z, int stateIdx, CompoundTag nbt) {
		CompoundTag entry = new CompoundTag();
		ListTag pos = new ListTag();
		pos.add(IntTag.valueOf(x));
		pos.add(IntTag.valueOf(y));
		pos.add(IntTag.valueOf(z));
		entry.put("pos", pos);
		entry.putInt("state", stateIdx);
		if (nbt != null) {
			entry.put("nbt", nbt);
		}
		return entry;
	}

	private static CompoundTag signNbt(List<String> frontLines, List<String> backLines) {
		CompoundTag nbt = new CompoundTag();
		nbt.putString("id", "minecraft:sign");
		nbt.put("front_text", faceTag(frontLines));
		nbt.put("back_text", faceTag(backLines));
		return nbt;
	}

	private static CompoundTag faceTag(List<String> lines) {
		CompoundTag face = new CompoundTag();
		ListTag msgs = new ListTag();
		for (int i = 0; i < 4; i++) {
			msgs.add(StringTag.valueOf(i < lines.size() ? lines.get(i) : ""));
		}
		face.put("messages", msgs);
		return face;
	}
}
