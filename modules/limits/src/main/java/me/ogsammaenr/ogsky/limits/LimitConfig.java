package me.ogsammaenr.ogsky.limits;

import me.ogsammaenr.ogsky.core.api.island.IslandRole;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Configuration schema stored at {@code config/ogsky/limits.json}.
 */
public final class LimitConfig {
	public enum LiquidGeneratorPolicy {
		CANCEL,
		FALLBACK_TO_STONE,
		FULL_FALLBACK_WITH_AIR
	}

	public static final class GroupLimitConfig {
		public String displayName = "";
		public String icon = "minecraft:barrier";
		public int limit = 0;
		public List<String> entries = List.of();

		public GroupLimitConfig() {
		}

		public GroupLimitConfig(String displayName, String icon, int limit, List<String> entries) {
			this.displayName = displayName;
			this.icon = icon;
			this.limit = limit;
			this.entries = List.copyOf(entries);
		}
	}

	public static final class LevelScalingConfig {
		public boolean enabled = false;
		public double bonusPer100Levels = 0.1D;
		public double maxBonusMultiplier = 2.0D;

		public static LevelScalingConfig disabled() {
			return new LevelScalingConfig();
		}
	}

	public boolean enforce = true;
	public boolean countExistingOnFirstAccess = true;
	public boolean autoScanOnBlueprintComplete = true;
	public String defaultViewLimitsRank = "MEMBER";
	public String bypassPermission = "ogsky.limits.bypass";
	public int notifyCooldownMillis = 1500;
	public int scanChunksPerTick = 8;
	public long scanMaxMillisPerTick = 4L;
	public boolean scanIncludeUnloadedChunks = true;
	public int dbFlushIntervalTicks = 20;
	public LiquidGeneratorPolicy liquidGeneratorPolicy = LiquidGeneratorPolicy.CANCEL;
	public boolean countBaseMultiBlockPartsOnly = true;
	public LevelScalingConfig levelScaling = LevelScalingConfig.disabled();
	public Map<String, Integer> blockLimits = defaultBlockLimits();
	public Map<String, GroupLimitConfig> blockGroups = defaultBlockGroups();
	public Map<String, Integer> entityLimits = defaultEntityLimits();
	public Map<String, GroupLimitConfig> entityGroups = defaultEntityGroups();

	public static LimitConfig defaults() {
		LimitConfig cfg = new LimitConfig();
		cfg.ensureDefaultsAndValidate();
		return cfg;
	}

	public void ensureDefaultsAndValidate() {
		if (defaultViewLimitsRank == null || defaultViewLimitsRank.isBlank()) {
			defaultViewLimitsRank = "MEMBER";
		} else {
			defaultViewLimitsRank = defaultViewLimitsRank.trim().toUpperCase(Locale.ROOT);
			// Validate threshold parses cleanly.
			parseDefaultViewLimitsRole();
		}
		if (bypassPermission == null || bypassPermission.isBlank()) {
			bypassPermission = "ogsky.limits.bypass";
		}
		if (notifyCooldownMillis < 0) {
			notifyCooldownMillis = 1500;
		}
		if (scanChunksPerTick < 1) {
			scanChunksPerTick = 8;
		}
		if (scanMaxMillisPerTick < 1L) {
			scanMaxMillisPerTick = 4L;
		}
		if (dbFlushIntervalTicks < 1) {
			dbFlushIntervalTicks = 20;
		}
		if (liquidGeneratorPolicy == null) {
			liquidGeneratorPolicy = LiquidGeneratorPolicy.CANCEL;
		}
		if (levelScaling == null) {
			levelScaling = LevelScalingConfig.disabled();
		}
		if (levelScaling.bonusPer100Levels < 0.0D) {
			levelScaling.bonusPer100Levels = 0.0D;
		}
		if (levelScaling.maxBonusMultiplier < 1.0D) {
			levelScaling.maxBonusMultiplier = 1.0D;
		}
		if (blockLimits == null) {
			blockLimits = defaultBlockLimits();
		}
		if (blockGroups == null) {
			blockGroups = defaultBlockGroups();
		}
		if (entityLimits == null) {
			entityLimits = defaultEntityLimits();
		}
		if (entityGroups == null) {
			entityGroups = defaultEntityGroups();
		}
	}

	public byte parseDefaultViewLimitsRole() {
		try {
			return IslandRole.parseThreshold(defaultViewLimitsRank);
		} catch (IllegalArgumentException ignored) {
			defaultViewLimitsRank = "MEMBER";
			return IslandRole.MEMBER;
		}
	}

	public static Map<String, Integer> defaultBlockLimits() {
		Map<String, Integer> map = new LinkedHashMap<>();
		map.put("minecraft:hopper", 64);
		map.put("minecraft:piston", 32);
		map.put("minecraft:sticky_piston", 32);
		map.put("minecraft:observer", 32);
		map.put("minecraft:dispenser", 32);
		map.put("minecraft:dropper", 32);
		map.put("minecraft:crafter", 16);
		map.put("minecraft:beacon", 4);
		map.put("minecraft:spawner", 4);
		map.put("minecraft:conduit", 2);
		map.put("#minecraft:shulker_boxes", 24);
		map.put("#minecraft:beds", 12);
		map.put("minecraft:brewing_stand", 8);
		map.put("minecraft:obsidian", 64);
		return map;
	}

	public static Map<String, GroupLimitConfig> defaultBlockGroups() {
		Map<String, GroupLimitConfig> groups = new LinkedHashMap<>();
		groups.put(
			"piston_family",
			new GroupLimitConfig(
				"Total Pistons",
				"minecraft:piston",
				48,
				List.of("minecraft:piston", "minecraft:sticky_piston")
			)
		);
		return groups;
	}

	public static Map<String, Integer> defaultEntityLimits() {
		Map<String, Integer> map = new LinkedHashMap<>();
		map.put("minecraft:villager", 20);
		map.put("minecraft:iron_golem", 8);
		map.put("minecraft:snow_golem", 8);
		map.put("minecraft:copper_golem", 8);
		map.put("minecraft:armor_stand", 16);
		map.put("minecraft:item_frame", 32);
		map.put("minecraft:glow_item_frame", 16);
		map.put("minecraft:painting", 24);
		map.put("minecraft:end_crystal", 4);
		map.put("#minecraft:boat", 8);
		return map;
	}

	public static Map<String, GroupLimitConfig> defaultEntityGroups() {
		Map<String, GroupLimitConfig> groups = new LinkedHashMap<>();
		groups.put(
			"farm_animals",
			new GroupLimitConfig(
				"Farm Animals",
				"minecraft:wheat",
				32,
				List.of("minecraft:cow", "minecraft:sheep", "minecraft:pig", "minecraft:chicken")
			)
		);
		groups.put(
			"minecarts",
			new GroupLimitConfig(
				"Minecarts",
				"minecraft:minecart",
				12,
				List.of(
					"minecraft:minecart",
					"minecraft:chest_minecart",
					"minecraft:hopper_minecart",
					"minecraft:furnace_minecart",
					"minecraft:tnt_minecart"
				)
			)
		);
		return groups;
	}
}
