package me.ogsammaenr.ogsky.warps;

import me.ogsammaenr.ogsky.core.api.island.IslandView;
import me.ogsammaenr.ogsky.core.api.level.IslandLevelProvider;
import me.ogsammaenr.ogsky.core.api.level.IslandLevelSnapshot;
import me.ogsammaenr.ogsky.core.api.world.OgskyDimensionKeys;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Shared validation, formatting, sorting, player-lookup, and pagination helpers for {@code ogsky-warps}.
 */
final class WarpHelper {
	private static final Pattern WARP_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");
	private static final Set<String> RESERVED_WARP_NAMES = Set.of("all", "off");

	record PageSlice(int start, int end, int page, int totalPages) {
		static PageSlice of(int totalItems, int requestedPage, int pageSize) {
			int safeSize = Math.max(1, pageSize);
			int totalPages = Math.max(1, (totalItems + safeSize - 1) / safeSize);
			int clampedPage = Math.clamp(requestedPage, 1, totalPages);
			int start = (clampedPage - 1) * safeSize;
			int end = Math.min(totalItems, start + safeSize);
			return new PageSlice(start, end, clampedPage, totalPages);
		}
	}

	record ShowcaseEntry(
		IslandView island,
		IslandWarp warp,
		String ownerName,
		long islandLevel
	) {
	}

	private WarpHelper() {
	}

	static String resolvePlayerName(MinecraftServer server, UUID playerUuid) {
		ServerPlayer online = server.getPlayerList().getPlayer(playerUuid);
		if (online != null) {
			return online.getScoreboardName();
		}
		return server.services().nameToIdCache().get(playerUuid)
			.map(NameAndId::name)
			.orElseGet(() -> playerUuid.toString().substring(0, 8));
	}

	static @Nullable UUID resolveTargetUuid(MinecraftServer server, String input) {
		if (input == null || input.isBlank()) {
			return null;
		}
		ServerPlayer online = server.getPlayerList().getPlayerByName(input);
		if (online != null) {
			return online.getUUID();
		}
		Optional<NameAndId> cached = server.services().nameToIdCache().get(input);
		if (cached.isPresent()) {
			return cached.get().id();
		}
		try {
			return UUID.fromString(input);
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	static @Nullable String validateDisplayName(String rawName, int maxLength) {
		if (rawName == null) {
			return null;
		}
		String trimmed = rawName.trim();
		if (trimmed.isEmpty() || trimmed.length() > maxLength) {
			return null;
		}
		if (!WARP_NAME_PATTERN.matcher(trimmed).matches()) {
			return null;
		}
		String lower = trimmed.toLowerCase(Locale.ROOT);
		if (RESERVED_WARP_NAMES.contains(lower)) {
			return null;
		}
		return trimmed;
	}

	static String normalizeSlug(String rawName) {
		return rawName == null ? "" : rawName.trim().toLowerCase(Locale.ROOT);
	}

	static String normalizeSort(@Nullable String rawSort) {
		if (rawSort == null || rawSort.isBlank()) {
			return "recent";
		}
		return switch (rawSort.trim().toLowerCase(Locale.ROOT)) {
			case "popular", "visits", "top" -> "popular";
			case "level", "lvl" -> "level";
			case "alpha", "name", "az", "a-z" -> "alpha";
			default -> "recent";
		};
	}

	static String normalizeCategoryFilter(@Nullable String rawCategory, WarpConfig config) {
		if (rawCategory == null || rawCategory.isBlank() || "all".equalsIgnoreCase(rawCategory.trim())) {
			return "all";
		}
		WarpConfig.CategorySpec spec = config.findCategory(rawCategory);
		return spec != null ? spec.id : "all";
	}

	static @Nullable ResourceKey<Level> toDimensionKey(Identifier dimId) {
		for (ResourceKey<Level> key : OgskyDimensionKeys.ALL) {
			if (key.identifier().equals(dimId)) {
				return key;
			}
		}
		return null;
	}

	static String formatDimensionName(Identifier dimId) {
		ResourceKey<Level> key = toDimensionKey(dimId);
		return key != null ? OgskyDimensionKeys.dimensionName(key) : dimId.toString();
	}

	static Optional<String> validateIconItemId(@Nullable String rawItemId, WarpConfig config) {
		if (rawItemId == null || rawItemId.isBlank()) {
			return Optional.empty();
		}
		String trimmed = rawItemId.trim().toLowerCase(Locale.ROOT);
		if (!trimmed.contains(":")) {
			trimmed = "minecraft:" + trimmed;
		}
		Identifier parsed = Identifier.tryParse(trimmed);
		if (parsed == null) {
			return Optional.empty();
		}
		String canonical = parsed.toString();
		if (config.forbiddenIcons.contains(canonical)) {
			return Optional.empty();
		}
		Optional<Item> resolved = BuiltInRegistries.ITEM.getOptional(parsed);
		if (resolved.isEmpty() || BuiltInRegistries.ITEM.getKey(resolved.get()).getPath().equals("air")) {
			return Optional.empty();
		}
		return Optional.of(canonical);
	}

	static long resolveIslandLevel(
		@Nullable IslandLevelProvider levelProvider,
		Map<UUID, Long> levelCache,
		UUID islandId
	) {
		if (levelProvider == null) {
			return 0L;
		}
		return levelCache.computeIfAbsent(islandId, id ->
			levelProvider.findSnapshot(id).map(IslandLevelSnapshot::levelValue).orElse(0L)
		);
	}

	static Comparator<ShowcaseEntry> showcaseComparator(String sortMode) {
		return switch (normalizeSort(sortMode)) {
			case "popular" -> Comparator
				.comparingLong((ShowcaseEntry e) -> e.warp().visitCount()).reversed()
				.thenComparing(Comparator.comparingLong((ShowcaseEntry e) -> e.warp().updatedAt()).reversed())
				.thenComparing(e -> e.warp().displayName(), String.CASE_INSENSITIVE_ORDER);
			case "level" -> Comparator
				.comparingLong(ShowcaseEntry::islandLevel).reversed()
				.thenComparing(Comparator.comparingLong((ShowcaseEntry e) -> e.warp().visitCount()).reversed())
				.thenComparing(e -> e.warp().displayName(), String.CASE_INSENSITIVE_ORDER);
			case "alpha" -> Comparator
				.comparing((ShowcaseEntry e) -> e.warp().displayName(), String.CASE_INSENSITIVE_ORDER)
				.thenComparing(ShowcaseEntry::ownerName, String.CASE_INSENSITIVE_ORDER)
				.thenComparing(e -> e.warp().slug());
			default -> Comparator
				.comparingLong((ShowcaseEntry e) -> e.warp().updatedAt()).reversed()
				.thenComparing(Comparator.comparingLong((ShowcaseEntry e) -> e.warp().createdAt()).reversed())
				.thenComparing(e -> e.warp().displayName(), String.CASE_INSENSITIVE_ORDER);
		};
	}

	static Map<UUID, Long> newLevelCache() {
		return new HashMap<>();
	}
}
