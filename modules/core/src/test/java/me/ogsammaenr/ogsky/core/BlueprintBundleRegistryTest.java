package me.ogsammaenr.ogsky.core;

import me.ogsammaenr.ogsky.core.api.island.RelativeSpawn;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfig;
import me.ogsammaenr.ogsky.core.internal.flag.BuiltinFlags;
import me.ogsammaenr.ogsky.core.internal.flag.SimpleFlagRegistry;
import me.ogsammaenr.ogsky.core.internal.island.Island;
import me.ogsammaenr.ogsky.core.internal.blueprint.BlueprintBundle;
import me.ogsammaenr.ogsky.core.internal.blueprint.BlueprintBundleRegistry;
import me.ogsammaenr.ogsky.core.internal.world.OgskyDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueprintBundleRegistryTest {
	@TempDir
	Path tempDir;

	@Test
	void reloadDiscoversBundlesWithoutAutoGeneratingDefaultNbt() throws Exception {
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
		Files.write(vipDir.resolve("custom_ow.nbt"), new byte[] { 0x01 });

		Path classicDir = blueprintsRoot.resolve("classic");
		Files.createDirectories(classicDir);
		Files.write(classicDir.resolve("overworld.nbt"), new byte[] { 0x02 });
		Files.write(classicDir.resolve("nether.nbt"), new byte[] { 0x03 });

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
		assertNull(vip.endFile());

		BlueprintBundle classic = registry.find("classic").orElseThrow();
		assertTrue(classic.hasOverworld());
		assertTrue(classic.hasNether());
		assertFalse(classic.hasEnd());
		assertFalse(classic.metadata().requiresPermission);

		// Verify computePasteOrigin and computeSpawn honor centering, offset, and island clamping.
		SimpleFlagRegistry flags = new SimpleFlagRegistry();
		BuiltinFlags.registerAll(flags);
		flags.freeze();
		Island island = new Island(
			UUID.randomUUID(),
			UUID.randomUUID(),
			0,
			0,
			512,
			512,
			(short) 50,
			"vip_island",
			Instant.now(),
			flags,
			CoreConfig.defaults()
		);

		BlockPos pasteOrigin = vip.computePasteOrigin(
			island,
			OgskyDimensions.OVERWORLD,
			new Vec3i(7, 5, 9),
			68,
			-64,
			319
		);
		assertEquals(new BlockPos(512 - 3, 65, 512 - 4), pasteOrigin);

		RelativeSpawn spawn = vip.computeSpawn(island, OgskyDimensions.OVERWORLD, 68);
		assertEquals(513.5D, spawn.worldX(island.centerX()));
		assertEquals(70.0D, spawn.worldY());
		assertEquals(511.5D, spawn.worldZ(island.centerZ()));
	}
}
