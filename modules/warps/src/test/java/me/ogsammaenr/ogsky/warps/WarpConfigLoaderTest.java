package me.ogsammaenr.ogsky.warps;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarpConfigLoaderTest {
	@TempDir
	Path tempDir;

	@Test
	void createsDefaultsAndPreservesCustomEdits() throws Exception {
		Path path = tempDir.resolve("config").resolve("ogsky").resolve("warps.json");
		WarpConfig initial = WarpConfigLoader.loadOrCreate(path);

		assertTrue(Files.exists(path));
		assertTrue(initial.enabled);
		assertEquals("ADMIN", initial.defaultManageWarpsRank);
		assertEquals("VISITOR", initial.defaultUseWarpRank);
		assertEquals(3, initial.defaultMaxWarpsPerIsland);
		assertEquals(3, initial.warmupSeconds);
		assertEquals(5, initial.cooldownSeconds);
		assertTrue(initial.cancelWarmupOnMove);
		assertTrue(initial.cancelWarmupOnDamage);
		assertEquals("general", initial.defaultCategory);
		assertTrue(initial.findCategory("shop") != null);

		initial.warmupSeconds = 4;
		initial.cooldownSeconds = 12;
		initial.defaultMaxWarpsPerIsland = 5;
		initial.categories.add(new WarpConfig.CategorySpec("parkour", "Parkour", "minecraft:slime_block", 70));
		WarpConfigLoader.save(path, initial);

		WarpConfig reloaded = WarpConfigLoader.loadOrCreate(path);
		assertEquals(4, reloaded.warmupSeconds);
		assertEquals(12, reloaded.cooldownSeconds);
		assertEquals(5, reloaded.defaultMaxWarpsPerIsland);
		assertTrue(reloaded.findCategory("parkour") != null);
	}

	@Test
	void sanitizesInvalidRanksClampsTimingsAndHandlesCorruptJson() throws Exception {
		Path badPath = tempDir.resolve("bad-warps.json");
		Files.writeString(badPath, """
			{
			  "defaultManageWarpsRank": "INVALID_RANK",
			  "defaultUseWarpRank": "WRONG",
			  "defaultMaxWarpsPerIsland": -2,
			  "warmupSeconds": 999,
			  "cooldownSeconds": -10,
			  "warmupMoveToleranceBlocks": 0.0,
			  "defaultCategory": "missing_cat",
			  "categories": []
			}
			""");
		WarpConfig sanitized = WarpConfigLoader.loadOrCreate(badPath);
		assertEquals("ADMIN", sanitized.defaultManageWarpsRank);
		assertEquals("VISITOR", sanitized.defaultUseWarpRank);
		assertEquals(1, sanitized.defaultMaxWarpsPerIsland);
		assertEquals(60, sanitized.warmupSeconds);
		assertEquals(0, sanitized.cooldownSeconds);
		assertEquals(0.05D, sanitized.warmupMoveToleranceBlocks);
		assertFalse(sanitized.categories.isEmpty());
		assertEquals(sanitized.categories.getFirst().id, sanitized.defaultCategory);

		Path corrupt = tempDir.resolve("corrupt.json");
		Files.writeString(corrupt, "{ not valid json");
		WarpConfig fallback = WarpConfigLoader.loadOrCreate(corrupt);
		assertEquals(3, fallback.warmupSeconds);
		assertEquals(5, fallback.cooldownSeconds);
		assertEquals(List.of(1, 3, 5, 10, 15, 25), fallback.permissionLimitTiers);
	}
}
