package me.ogsammaenr.ogsky.limits;

import me.ogsammaenr.ogsky.core.api.command.IslandCommandHelper;
import me.ogsammaenr.ogsky.core.api.flag.FlagRegistry;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import me.ogsammaenr.ogsky.core.api.permission.PermissionService;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import org.jspecify.annotations.Nullable;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared formatting, filtering, player-resolution, and island-context helpers for {@code ogsky-limits}.
 */
final class LimitHelper {
	private static final Identifier ISLAND_LOCK_FLAG = Identifier.fromNamespaceAndPath("ogsky", "island_lock");

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

	private LimitHelper() {
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

	static Optional<IslandView> resolveContextIsland(
		ServerPlayer player,
		IslandCommandHelper helper,
		FlagRegistry flags,
		PermissionService permissions
	) {
		Optional<IslandView> standing = helper.islandAt(player);
		if (standing.isPresent() && standing.get().roleOf(player.getUUID()) > IslandRole.VISITOR) {
			return standing;
		}
		Optional<IslandView> primary = helper.primaryIsland(player);
		if (primary.isPresent()) {
			return primary;
		}
		if (standing.isPresent()) {
			IslandView island = standing.get();
			boolean locked = flags.find(ISLAND_LOCK_FLAG)
				.map(def -> island.isSettingEnabled(def.id()))
				.orElse(false);
			if (!locked || permissions.isBypassActive(player)) {
				return standing;
			}
		}
		return Optional.empty();
	}

	static String formatNumber(int value) {
		return NumberFormat.getIntegerInstance(Locale.US).format(value);
	}

	static String normalizeFilter(@Nullable String rawFilter) {
		if (rawFilter == null) {
			return "all";
		}
		return switch (rawFilter.trim().toLowerCase(Locale.ROOT)) {
			case "block", "blocks" -> "blocks";
			case "entity", "entities" -> "entities";
			default -> "all";
		};
	}

	static String normalizeSort(@Nullable String rawSort) {
		if (rawSort == null) {
			return "usage";
		}
		return switch (rawSort.trim().toLowerCase(Locale.ROOT)) {
			case "name", "alpha" -> "name";
			case "kind", "type" -> "kind";
			default -> "usage";
		};
	}

	static List<IslandLimitService.RuleStatus> filterAndSortStatuses(
		List<IslandLimitService.RuleStatus> statuses,
		String rawFilter,
		@Nullable String rawQuery,
		String rawSort
	) {
		String filter = normalizeFilter(rawFilter);
		String query = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.ROOT);
		String sort = normalizeSort(rawSort);

		List<IslandLimitService.RuleStatus> result = new ArrayList<>(statuses.size());
		for (IslandLimitService.RuleStatus status : statuses) {
			LimitRuleTable.LimitRule rule = status.rule();
			if ("blocks".equals(filter) && rule.kind() != LimitRuleTable.RuleKind.BLOCK) {
				continue;
			}
			if ("entities".equals(filter) && rule.kind() != LimitRuleTable.RuleKind.ENTITY) {
				continue;
			}
			if (!query.isEmpty() && !matchesQuery(rule, query)) {
				continue;
			}
			result.add(status);
		}

		Comparator<IslandLimitService.RuleStatus> comparator = switch (sort) {
			case "name" -> Comparator
				.comparing((IslandLimitService.RuleStatus s) -> s.rule().displayName(), String.CASE_INSENSITIVE_ORDER)
				.thenComparing(s -> s.rule().key());
			case "kind" -> Comparator
				.comparing((IslandLimitService.RuleStatus s) -> s.rule().kind())
				.thenComparing(s -> s.rule().displayName(), String.CASE_INSENSITIVE_ORDER);
			default -> Comparator
				.comparingDouble(IslandLimitService.RuleStatus::usageRatio).reversed()
				.thenComparing(Comparator.comparingInt(IslandLimitService.RuleStatus::used).reversed())
				.thenComparing(s -> s.rule().displayName(), String.CASE_INSENSITIVE_ORDER);
		};
		result.sort(comparator);
		return result;
	}

	private static boolean matchesQuery(LimitRuleTable.LimitRule rule, String lowerQuery) {
		if (rule.key().toLowerCase(Locale.ROOT).contains(lowerQuery)
			|| rule.displayName().toLowerCase(Locale.ROOT).contains(lowerQuery)) {
			return true;
		}
		for (String entry : rule.sourceEntries()) {
			if (entry.toLowerCase(Locale.ROOT).contains(lowerQuery)) {
				return true;
			}
		}
		return false;
	}

	static ChatFormatting statusColor(IslandLimitService.RuleStatus status) {
		int max = status.effectiveLimit();
		int used = status.used();
		if (max <= 0) {
			return used > 0 ? ChatFormatting.RED : ChatFormatting.GRAY;
		}
		if (used >= max) {
			return ChatFormatting.RED;
		}
		if ((double) used / (double) max >= 0.75D) {
			return ChatFormatting.YELLOW;
		}
		return ChatFormatting.GREEN;
	}

	static String usageBar(int used, int max) {
		final int segments = 16;
		if (max <= 0) {
			return "[" + (used > 0 ? "!".repeat(segments) : ".".repeat(segments)) + "] " + (used > 0 ? "MAX" : "0%");
		}
		double ratio = Math.min(1.0D, Math.max(0.0D, (double) used / (double) max));
		int filled = (int) Math.round(ratio * segments);
		int percent = (int) Math.round(ratio * 100.0D);
		return "[" + "|".repeat(filled) + ".".repeat(segments - filled) + "] " + percent + "%";
	}
}
