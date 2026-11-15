package me.ogsammaenr.ogsky.level;

import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Validated configuration model persisted at {@code config/ogsky/level.json}.
 */
public final class LevelConfig {
	public FormulaConfig formula = new FormulaConfig();
	public ScanConfig scan = new ScanConfig();
	public TopConfig top = new TopConfig();
	public Map<String, Long> blockValues = new LinkedHashMap<>();
	public Map<String, Integer> blockPointLimits = new LinkedHashMap<>();

	public static LevelConfig defaults() {
		LevelConfig config = new LevelConfig();
		config.populateDefaultBlocks();
		config.ensureDefaultsAndValidate();
		return config;
	}

	private void populateDefaultBlocks() {
		// Curated Skyblock block point defaults (100 points = 1 island level by default).
		blockValues.put("minecraft:cobblestone", 1L);
		blockValues.put("minecraft:cobbled_deepslate", 1L);
		blockValues.put("minecraft:stone", 2L);
		blockValues.put("minecraft:deepslate", 2L);
		blockValues.put("minecraft:smooth_stone", 2L);
		blockValues.put("minecraft:stone_bricks", 3L);
		blockValues.put("minecraft:bricks", 4L);
		blockValues.put("minecraft:nether_bricks", 4L);
		blockValues.put("minecraft:end_stone", 4L);
		blockValues.put("minecraft:end_stone_bricks", 5L);
		blockValues.put("minecraft:prismarine", 5L);
		blockValues.put("minecraft:quartz_block", 5L);
		blockValues.put("minecraft:terracotta", 2L);
		blockValues.put("minecraft:glass", 2L);
		blockValues.put("minecraft:glowstone", 8L);
		blockValues.put("minecraft:sea_lantern", 10L);
		blockValues.put("minecraft:obsidian", 20L);
		blockValues.put("minecraft:crying_obsidian", 35L);

		// Tag-based wood and wool defaults.
		blockValues.put("#minecraft:logs", 2L);
		blockValues.put("#minecraft:planks", 1L);
		blockValues.put("#minecraft:wool", 2L);

		// Utility and redstone blocks.
		blockValues.put("minecraft:chest", 2L);
		blockValues.put("minecraft:barrel", 2L);
		blockValues.put("minecraft:bookshelf", 6L);
		blockValues.put("minecraft:piston", 5L);
		blockValues.put("minecraft:sticky_piston", 7L);
		blockValues.put("minecraft:observer", 8L);
		blockValues.put("minecraft:hopper", 12L);
		blockValues.put("minecraft:crafter", 12L);
		blockValues.put("minecraft:brewing_stand", 20L);
		blockValues.put("minecraft:anvil", 30L);
		blockValues.put("minecraft:enchanting_table", 40L);
		blockValues.put("minecraft:conduit", 200L);

		// Ore & mineral storage blocks.
		blockValues.put("minecraft:coal_block", 8L);
		blockValues.put("minecraft:copper_block", 10L);
		blockValues.put("minecraft:redstone_block", 12L);
		blockValues.put("minecraft:iron_block", 15L);
		blockValues.put("minecraft:lapis_block", 20L);
		blockValues.put("minecraft:gold_block", 50L);
		blockValues.put("minecraft:diamond_block", 100L);
		blockValues.put("minecraft:emerald_block", 150L);
		blockValues.put("minecraft:ancient_debris", 150L);
		blockValues.put("minecraft:netherite_block", 400L);

		// Endgame trophy blocks.
		blockValues.put("minecraft:sponge", 50L);
		blockValues.put("minecraft:wet_sponge", 50L);
		blockValues.put("minecraft:spawner", 250L);
		blockValues.put("minecraft:beacon", 500L);
		blockValues.put("minecraft:dragon_egg", 1000L);

		// Scoring-only caps (physical placement limits belong to a separate limits module).
		blockPointLimits.put("minecraft:cobblestone", 10_000);
		blockPointLimits.put("minecraft:stone", 10_000);
		blockPointLimits.put("minecraft:obsidian", 500);
		blockPointLimits.put("minecraft:hopper", 64);
	}

	public void ensureDefaultsAndValidate() {
		if (formula == null) formula = new FormulaConfig();
		if (scan == null) scan = new ScanConfig();
		if (top == null) top = new TopConfig();
		if (blockValues == null) blockValues = new LinkedHashMap<>();
		if (blockPointLimits == null) blockPointLimits = new LinkedHashMap<>();

		if (formula.pointsPerLevel < 1L) {
			throw new IllegalArgumentException("formula.pointsPerLevel must be >= 1, got: " + formula.pointsPerLevel);
		}
		if (formula.defaultBlockValue < 0L) {
			throw new IllegalArgumentException("formula.defaultBlockValue must be >= 0, got: " + formula.defaultBlockValue);
		}
		if (scan.cooldownSeconds < 0) {
			throw new IllegalArgumentException("scan.cooldownSeconds must be >= 0, got: " + scan.cooldownSeconds);
		}
		if (scan.chunksPerTick < 1) {
			throw new IllegalArgumentException("scan.chunksPerTick must be >= 1, got: " + scan.chunksPerTick);
		}
		if (scan.maxNanosPerTick < 100_000L) {
			throw new IllegalArgumentException("scan.maxNanosPerTick must be >= 100000, got: " + scan.maxNanosPerTick);
		}
		if (scan.maxInFlightDiskReads < 1) {
			throw new IllegalArgumentException("scan.maxInFlightDiskReads must be >= 1, got: " + scan.maxInFlightDiskReads);
		}
		if (top.maxCachedEntries < 1) {
			throw new IllegalArgumentException("top.maxCachedEntries must be >= 1, got: " + top.maxCachedEntries);
		}
		if (top.chatEntriesPerPage < 1) {
			throw new IllegalArgumentException("top.chatEntriesPerPage must be >= 1, got: " + top.chatEntriesPerPage);
		}
		if (top.detailTopBlocksLimit < 1 || top.detailTopBlocksLimit > 21) {
			throw new IllegalArgumentException("top.detailTopBlocksLimit must be in 1..21, got: " + top.detailTopBlocksLimit);
		}

		Map<String, Long> normalizedValues = new LinkedHashMap<>();
		for (Map.Entry<String, Long> entry : blockValues.entrySet()) {
			String key = normalizeBlockOrTagKey(entry.getKey());
			Long value = entry.getValue();
			if (value == null || value < 0L) {
				throw new IllegalArgumentException("Block value for '" + key + "' must be >= 0, got: " + value);
			}
			normalizedValues.put(key, value);
		}
		blockValues = normalizedValues;

		Map<String, Integer> normalizedLimits = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> entry : blockPointLimits.entrySet()) {
			String key = normalizeBlockOrTagKey(entry.getKey());
			Integer limit = entry.getValue();
			if (limit == null || limit < 0) {
				throw new IllegalArgumentException("Block point limit for '" + key + "' must be >= 0, got: " + limit);
			}
			normalizedLimits.put(key, limit);
		}
		blockPointLimits = normalizedLimits;
	}

	public static String normalizeBlockOrTagKey(String rawKey) {
		if (rawKey == null || rawKey.isBlank()) {
			throw new IllegalArgumentException("Block/tag key must not be blank.");
		}
		String trimmed = rawKey.trim().toLowerCase(Locale.ROOT);
		boolean isTag = trimmed.startsWith("#");
		String body = isTag ? trimmed.substring(1) : trimmed;
		Identifier parsed = body.contains(":")
			? Identifier.tryParse(body)
			: Identifier.tryBuild("minecraft", body);
		if (parsed == null) {
			throw new IllegalArgumentException("Invalid Minecraft block or tag identifier: '" + rawKey + "'");
		}
		return isTag ? "#" + parsed : parsed.toString();
	}

	public static final class FormulaConfig {
		public long pointsPerLevel = 100L;
		public long defaultBlockValue = 0L;
	}

	public static final class ScanConfig {
		public int cooldownSeconds = 30;
		public int chunksPerTick = 16;
		public long maxNanosPerTick = 4_000_000L;
		public boolean includeUnloadedChunks = true;
		public int maxInFlightDiskReads = 8;
		public boolean autoScanOnPasteComplete = true;
	}

	public static final class TopConfig {
		public int maxCachedEntries = 100;
		public int chatEntriesPerPage = 10;
		public int detailTopBlocksLimit = 14;
	}
}
