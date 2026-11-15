package me.ogsammaenr.ogsky.level;

import me.ogsammaenr.ogsky.core.api.command.IslandCommandHelper;
import me.ogsammaenr.ogsky.core.api.flag.FlagRegistry;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import me.ogsammaenr.ogsky.core.api.permission.PermissionService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared formatting, player-resolution, and island-context helpers for {@code ogsky-level}.
 */
final class LevelHelper {
	private static final Identifier ISLAND_LOCK_FLAG = Identifier.fromNamespaceAndPath("ogsky", "island_lock");
	private static final String FALLBACK_ICON_ID = "minecraft:paper";

	private LevelHelper() {
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

	static String resolveItemIconId(Identifier blockId) {
		Optional<Block> block = BuiltInRegistries.BLOCK.get(blockId).map(ref -> ref.value());
		if (block.isEmpty()) {
			return FALLBACK_ICON_ID;
		}
		var asItem = block.get().asItem();
		if (asItem == Items.AIR) {
			return FALLBACK_ICON_ID;
		}
		return BuiltInRegistries.ITEM.getKey(asItem).toString();
	}

	static String formatBlockDisplayName(Identifier blockId) {
		Optional<Block> block = BuiltInRegistries.BLOCK.get(blockId).map(ref -> ref.value());
		if (block.isPresent()) {
			String translated = block.get().getName().getString();
			if (translated != null && !translated.isBlank()) {
				return translated;
			}
		}
		String path = blockId.getPath();
		String[] parts = path.split("_");
		StringBuilder sb = new StringBuilder();
		for (String part : parts) {
			if (part.isEmpty()) {
				continue;
			}
			if (!sb.isEmpty()) {
				sb.append(' ');
			}
			sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
		}
		return sb.isEmpty() ? blockId.toString() : sb.toString();
	}

	static String formatNumber(long value) {
		return NumberFormat.getIntegerInstance(Locale.US).format(value);
	}

	static List<LevelValuationTable.BlockValueEntry> filterValuedBlocks(
		List<LevelValuationTable.BlockValueEntry> entries,
		@Nullable String rawQuery
	) {
		if (rawQuery == null || rawQuery.isBlank()) {
			return entries;
		}
		String needle = rawQuery.trim().toLowerCase(Locale.ROOT);
		return entries.stream()
			.filter(entry -> entry.blockId().toString().toLowerCase(Locale.ROOT).contains(needle)
				|| formatBlockDisplayName(entry.blockId()).toLowerCase(Locale.ROOT).contains(needle))
			.toList();
	}
}
