package me.ogsammaenr.ogsky.warps;

import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Configuration model serialized to {@code config/ogsky/warps.json}.
 */
public final class WarpConfig {
	public static final String DEFAULT_WARP_NAME = "home";

	public boolean enabled = true;
	public String defaultManageWarpsRank = "ADMIN";
	public String defaultUseWarpRank = "VISITOR";

	public boolean allowOverworld = true;
	public boolean allowNether = true;
	public boolean allowEnd = true;

	public int defaultMaxWarpsPerIsland = 3;
	public List<Integer> permissionLimitTiers = new ArrayList<>(List.of(1, 3, 5, 10, 15, 25));
	public String limitBypassPermission = "ogsky.warps.limit.unlimited";

	public int maxWarpNameLength = 24;
	public int maxDescriptionLength = 80;
	public String defaultCategory = "general";
	public List<String> forbiddenIcons = new ArrayList<>(List.of("minecraft:air", "minecraft:barrier", "minecraft:command_block"));
	public List<CategorySpec> categories = defaultCategories();

	public int warmupSeconds = 3;
	public boolean cancelWarmupOnMove = true;
	public double warmupMoveToleranceBlocks = 0.25D;
	public boolean cancelWarmupOnDamage = true;
	public int cooldownSeconds = 5;
	public String bypassWarmupPermission = "ogsky.warps.bypass_warmup";
	public String bypassCooldownPermission = "ogsky.warps.bypass_cooldown";

	public boolean countIslandMembersInVisits = false;
	public int visitCountCooldownSeconds = 3600;

	public int markerDurationSeconds = 30;
	public int markerIntervalTicks = 5;
	public String markerParticle = "minecraft:end_rod";

	public int chatEntriesPerPage = 10;

	public static final class CategorySpec {
		public String id = "general";
		public String displayName = "General";
		public String iconItemId = "minecraft:oak_sign";
		public int order = 10;

		public CategorySpec() {
		}

		public CategorySpec(String id, String displayName, String iconItemId, int order) {
			this.id = id;
			this.displayName = displayName;
			this.iconItemId = iconItemId;
			this.order = order;
		}
	}

	public static WarpConfig defaults() {
		WarpConfig config = new WarpConfig();
		config.ensureDefaultsAndValidate();
		return config;
	}

	private static List<CategorySpec> defaultCategories() {
		List<CategorySpec> list = new ArrayList<>();
		list.add(new CategorySpec("general", "General", "minecraft:oak_sign", 10));
		list.add(new CategorySpec("shop", "Shop", "minecraft:emerald", 20));
		list.add(new CategorySpec("farm", "Farm", "minecraft:wheat", 30));
		list.add(new CategorySpec("spawner", "Mob Farm", "minecraft:spawner", 40));
		list.add(new CategorySpec("arena", "Arena", "minecraft:iron_sword", 50));
		list.add(new CategorySpec("other", "Other", "minecraft:ender_chest", 60));
		return list;
	}

	public void ensureDefaultsAndValidate() {
		this.defaultManageWarpsRank = normalizeRoleThreshold(this.defaultManageWarpsRank, IslandRole.ADMIN);
		this.defaultUseWarpRank = normalizeRoleThreshold(this.defaultUseWarpRank, IslandRole.VISITOR);

		this.defaultMaxWarpsPerIsland = Math.clamp(this.defaultMaxWarpsPerIsland, 1, 100);
		if (this.permissionLimitTiers == null) {
			this.permissionLimitTiers = new ArrayList<>(List.of(1, 3, 5, 10, 15, 25));
		} else {
			Set<Integer> normalizedTiers = new LinkedHashSet<>();
			for (Integer tier : this.permissionLimitTiers) {
				if (tier != null && tier > 0 && tier <= 500) {
					normalizedTiers.add(tier);
				}
			}
			List<Integer> sortedTiers = new ArrayList<>(normalizedTiers);
			sortedTiers.sort(Integer::compareTo);
			this.permissionLimitTiers = sortedTiers;
		}
		if (this.limitBypassPermission == null || this.limitBypassPermission.isBlank()) {
			this.limitBypassPermission = "ogsky.warps.limit.unlimited";
		} else {
			this.limitBypassPermission = this.limitBypassPermission.trim();
		}

		this.maxWarpNameLength = Math.clamp(this.maxWarpNameLength, 4, 48);
		this.maxDescriptionLength = Math.clamp(this.maxDescriptionLength, 10, 200);

		if (this.forbiddenIcons == null) {
			this.forbiddenIcons = new ArrayList<>(List.of("minecraft:air", "minecraft:barrier", "minecraft:command_block"));
		} else {
			Set<String> normalizedIcons = new LinkedHashSet<>();
			for (String rawIcon : this.forbiddenIcons) {
				if (rawIcon != null && !rawIcon.isBlank()) {
					String trimmed = rawIcon.trim().toLowerCase(Locale.ROOT);
					if (!trimmed.contains(":")) {
						trimmed = "minecraft:" + trimmed;
					}
					normalizedIcons.add(trimmed);
				}
			}
			this.forbiddenIcons = new ArrayList<>(normalizedIcons);
		}

		if (this.categories == null || this.categories.isEmpty()) {
			this.categories = defaultCategories();
		}
		Map<String, CategorySpec> deduped = new LinkedHashMap<>();
		for (CategorySpec raw : this.categories) {
			if (raw == null || raw.id == null || raw.id.isBlank()) {
				continue;
			}
			String id = raw.id.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
			if (id.isEmpty()) {
				continue;
			}
			String name = (raw.displayName == null || raw.displayName.isBlank()) ? id : raw.displayName.trim();
			String icon = (raw.iconItemId == null || raw.iconItemId.isBlank()) ? "minecraft:oak_sign" : raw.iconItemId.trim();
			deduped.putIfAbsent(id, new CategorySpec(id, name, icon, raw.order));
		}
		if (deduped.isEmpty()) {
			for (CategorySpec spec : defaultCategories()) {
				deduped.put(spec.id, spec);
			}
		}
		List<CategorySpec> sortedCats = new ArrayList<>(deduped.values());
		sortedCats.sort(Comparator.comparingInt((CategorySpec c) -> c.order).thenComparing(c -> c.id));
		this.categories = sortedCats;

		String normalizedDefaultCat = this.defaultCategory == null ? "" : this.defaultCategory.trim().toLowerCase(Locale.ROOT);
		if (!deduped.containsKey(normalizedDefaultCat)) {
			normalizedDefaultCat = this.categories.getFirst().id;
		}
		this.defaultCategory = normalizedDefaultCat;

		this.warmupSeconds = Math.clamp(this.warmupSeconds, 0, 60);
		if (!Double.isFinite(this.warmupMoveToleranceBlocks)) {
			this.warmupMoveToleranceBlocks = 0.25D;
		}
		this.warmupMoveToleranceBlocks = Math.clamp(this.warmupMoveToleranceBlocks, 0.05D, 3.0D);
		this.cooldownSeconds = Math.clamp(this.cooldownSeconds, 0, 3600);
		if (this.bypassWarmupPermission == null || this.bypassWarmupPermission.isBlank()) {
			this.bypassWarmupPermission = "ogsky.warps.bypass_warmup";
		} else {
			this.bypassWarmupPermission = this.bypassWarmupPermission.trim();
		}
		if (this.bypassCooldownPermission == null || this.bypassCooldownPermission.isBlank()) {
			this.bypassCooldownPermission = "ogsky.warps.bypass_cooldown";
		} else {
			this.bypassCooldownPermission = this.bypassCooldownPermission.trim();
		}

		this.visitCountCooldownSeconds = Math.clamp(this.visitCountCooldownSeconds, 0, 86400 * 30);

		this.markerDurationSeconds = Math.clamp(this.markerDurationSeconds, 1, 300);
		this.markerIntervalTicks = Math.clamp(this.markerIntervalTicks, 1, 40);
		if (this.markerParticle == null || this.markerParticle.isBlank()) {
			this.markerParticle = "minecraft:end_rod";
		} else {
			this.markerParticle = this.markerParticle.trim().toLowerCase(Locale.ROOT);
		}

		this.chatEntriesPerPage = Math.clamp(this.chatEntriesPerPage, 1, 30);
	}

	public byte manageWarpsRankByte() {
		try {
			return IslandRole.parseThreshold(this.defaultManageWarpsRank);
		} catch (IllegalArgumentException ignored) {
			return IslandRole.ADMIN;
		}
	}

	public byte useWarpRankByte() {
		try {
			return IslandRole.parseThreshold(this.defaultUseWarpRank);
		} catch (IllegalArgumentException ignored) {
			return IslandRole.VISITOR;
		}
	}

	public @Nullable CategorySpec findCategory(@Nullable String rawId) {
		if (rawId == null || rawId.isBlank()) {
			return null;
		}
		String key = rawId.trim().toLowerCase(Locale.ROOT);
		for (CategorySpec spec : this.categories) {
			if (spec.id.equals(key)) {
				return spec;
			}
		}
		return null;
	}

	public String categoryDisplayName(String categoryId) {
		CategorySpec spec = findCategory(categoryId);
		return spec != null ? spec.displayName : categoryId;
	}

	private static String normalizeRoleThreshold(@Nullable String raw, byte fallbackRole) {
		if (raw != null && !raw.isBlank()) {
			try {
				byte parsed = IslandRole.parseThreshold(raw);
				return IslandRole.defaultKey(parsed).toUpperCase(Locale.ROOT);
			} catch (IllegalArgumentException ignored) {
			}
		}
		return IslandRole.defaultKey(fallbackRole).toUpperCase(Locale.ROOT);
	}
}
