package me.ogsammaenr.ogsky.limits;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LimitConfigLoaderTest {
	@TempDir
	Path tempDir;

	@Test
	void createsDefaultsAndPreservesUserEdits() throws Exception {
		Path path = tempDir.resolve("config").resolve("ogsky").resolve("limits.json");
		LimitConfig initial = LimitConfigLoader.loadOrCreate(path);

		assertTrue(Files.exists(path));
		assertTrue(initial.enforce);
		assertEquals("MEMBER", initial.defaultViewLimitsRank);
		assertEquals(LimitConfig.LiquidGeneratorPolicy.CANCEL, initial.liquidGeneratorPolicy);
		assertTrue(initial.countBaseMultiBlockPartsOnly);
		assertEquals(Integer.valueOf(64), initial.blockLimits.get("minecraft:hopper"));
		assertEquals(Integer.valueOf(24), initial.blockLimits.get("#minecraft:shulker_boxes"));
		assertTrue(initial.blockGroups.containsKey("piston_family"));
		assertTrue(initial.entityGroups.containsKey("farm_animals"));

		initial.blockLimits.put("minecraft:hopper", 128);
		initial.blockLimits.remove("minecraft:conduit");
		initial.entityGroups.put(
			"golems",
			new LimitConfig.GroupLimitConfig("Golems", "minecraft:iron_block", 10, List.of("minecraft:iron_golem", "minecraft:snow_golem"))
		);
		LimitConfigLoader.save(path, initial);

		LimitConfig reloaded = LimitConfigLoader.loadOrCreate(path);
		assertEquals(Integer.valueOf(128), reloaded.blockLimits.get("minecraft:hopper"));
		assertFalse(reloaded.blockLimits.containsKey("minecraft:conduit"));
		assertTrue(reloaded.entityGroups.containsKey("golems"));
		assertEquals(10, reloaded.entityGroups.get("golems").limit);
	}

	@Test
	void sanitizesInvalidRankAndFallsBackOnCorruptJson() throws Exception {
		Path badRank = tempDir.resolve("bad-rank.json");
		Files.writeString(badRank, """
			{
			  "defaultViewLimitsRank": "NOT_A_RANK",
			  "scanChunksPerTick": -5,
			  "dbFlushIntervalTicks": 0
			}
			""");
		LimitConfig sanitized = LimitConfigLoader.loadOrCreate(badRank);
		assertEquals("MEMBER", sanitized.defaultViewLimitsRank);
		assertEquals(8, sanitized.scanChunksPerTick);
		assertEquals(20, sanitized.dbFlushIntervalTicks);

		Path corrupt = tempDir.resolve("corrupt.json");
		Files.writeString(corrupt, "{ invalid json");
		LimitConfig fallback = LimitConfigLoader.loadOrCreate(corrupt);
		assertEquals(Integer.valueOf(64), fallback.blockLimits.get("minecraft:hopper"));
	}
}
