package me.ogsammaenr.ogsky.level;

import me.ogsammaenr.ogsky.core.api.level.BlockPointBreakdown;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LevelValuationTableTest {
	private static Identifier id(String raw) {
		return Identifier.parse(raw);
	}

	@Test
	void evaluatesFormulaTagPrecedenceCapsAndTopBreakdown() {
		LevelConfig config = LevelConfig.defaults();
		config.formula.pointsPerLevel = 100L;
		config.formula.defaultBlockValue = 0L;
		config.blockValues.clear();
		config.blockPointLimits.clear();

		config.blockValues.put("#minecraft:logs", 2L);
		config.blockValues.put("minecraft:oak_log", 5L);
		config.blockValues.put("minecraft:diamond_block", 100L);
		config.blockValues.put("minecraft:cobblestone", 1L);
		config.blockPointLimits.put("minecraft:cobblestone", 10);

		LevelValuationTable table = LevelValuationTable.compileForUnitTest(config, tagId -> {
			if (tagId.equals(id("minecraft:logs"))) {
				return List.of(id("minecraft:oak_log"), id("minecraft:birch_log"));
			}
			return List.of();
		});

		LevelValuationTable.EvaluationResult result = table.evaluateBlockIds(Map.of(
			id("minecraft:diamond_block"), 2,     // 2 * 100 = 200 pts
			id("minecraft:oak_log"), 10,          // exact overrides tag -> 10 * 5 = 50 pts
			id("minecraft:birch_log"), 10,        // from tag -> 10 * 2 = 20 pts
			id("minecraft:cobblestone"), 25,      // capped at 10 -> 10 * 1 = 10 pts
			id("minecraft:dirt"), 64              // default 0 -> ignored
		));

		// Total = 200 + 50 + 20 + 10 = 280 pts => level 2, next level in 20 pts
		assertEquals(280L, result.blockPoints());
		assertEquals(2L, result.levelValue());
		assertEquals(100L, result.pointsPerLevel());
		assertEquals(20L, result.pointsToNextLevel());

		List<BlockPointBreakdown> top = result.topBlocks();
		assertEquals(4, top.size());
		assertEquals(id("minecraft:diamond_block"), top.get(0).blockId());
		assertEquals(200L, top.get(0).pointsEarned());

		BlockPointBreakdown cobble = top.stream()
			.filter(b -> b.blockId().equals(id("minecraft:cobblestone")))
			.findFirst()
			.orElseThrow();
		assertEquals(25, cobble.rawCount());
		assertEquals(10, cobble.countedForPoints());
		assertEquals(10L, cobble.pointsEarned());
	}

	@Test
	void supportsNonZeroDefaultBlockValue() {
		LevelConfig config = LevelConfig.defaults();
		config.formula.pointsPerLevel = 100L;
		config.formula.defaultBlockValue = 1L;
		config.blockValues.clear();
		config.blockValues.put("minecraft:diamond_block", 100L);
		config.blockPointLimits.clear();

		LevelValuationTable table = LevelValuationTable.compileForUnitTest(config, tag -> List.of());
		LevelValuationTable.EvaluationResult result = table.evaluateBlockIds(Map.of(
			id("minecraft:dirt"), 50,
			id("minecraft:diamond_block"), 1
		));

		assertEquals(150L, result.blockPoints());
		assertEquals(1L, result.levelValue());
		assertEquals(50L, result.pointsToNextLevel());
	}

	@Test
	void valuedBlocksReturnsSortedPositiveRulesWithPrecedenceAndCaps() {
		LevelConfig config = LevelConfig.defaults();
		config.formula.pointsPerLevel = 100L;
		config.formula.defaultBlockValue = 0L;
		config.blockValues.clear();
		config.blockPointLimits.clear();

		config.blockValues.put("#minecraft:logs", 2L);
		config.blockValues.put("minecraft:oak_log", 5L);      // exact overrides #logs
		config.blockValues.put("minecraft:spruce_log", 0L);   // explicit 0 excluded from valuedBlocks
		config.blockValues.put("minecraft:diamond_block", 100L);
		config.blockPointLimits.put("minecraft:diamond_block", 8);
		config.blockPointLimits.put("minecraft:dirt", 64);    // cap-only with 0 pts excluded from valuedBlocks

		LevelValuationTable table = LevelValuationTable.compileForUnitTest(config, tagId -> {
			if (tagId.equals(id("minecraft:logs"))) {
				return List.of(id("minecraft:oak_log"), id("minecraft:birch_log"), id("minecraft:spruce_log"));
			}
			return List.of();
		});

		List<LevelValuationTable.BlockValueEntry> entries = table.valuedBlocks();
		assertEquals(3, entries.size());
		assertEquals(new LevelValuationTable.BlockValueEntry(id("minecraft:diamond_block"), 100L, 8), entries.get(0));
		assertEquals(new LevelValuationTable.BlockValueEntry(id("minecraft:oak_log"), 5L, LevelValuationTable.UNLIMITED), entries.get(1));
		assertEquals(new LevelValuationTable.BlockValueEntry(id("minecraft:birch_log"), 2L, LevelValuationTable.UNLIMITED), entries.get(2));
	}
}
