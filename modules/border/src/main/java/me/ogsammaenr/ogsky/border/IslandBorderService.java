package me.ogsammaenr.ogsky.border;

import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.OgskyCoreApi;
import me.ogsammaenr.ogsky.core.api.gui.GuiService;
import me.ogsammaenr.ogsky.core.api.island.IslandOperationResult;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import me.ogsammaenr.ogsky.core.api.world.OgskyDimensionKeys;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages per-player island border preferences and synchronizes client-side vanilla
 * {@link WorldBorder} packets with the island the player currently occupies.
 */
public final class IslandBorderService {
	public static final String PERM_BORDER = "ogsky.command.border";
	public static final String PERM_BORDER_COLOR = "ogsky.command.border.color";

	private static final double COLOR_LERP_EPSILON = 1.0E-4D;
	private static final long COLOR_LERP_TICKS = 1_000_000_000L;

	public record RenderedBorderKey(
		UUID islandId,
		ResourceKey<Level> dimension,
		int centerX,
		int centerZ,
		short halfSize,
		BorderColor color,
		int warningBlocks,
		int warningTime
	) {
	}

	private final OgskyCoreApi core;
	private final Path configPath;
	private final BorderRepository repository;

	private volatile BorderConfig config;
	private final Map<UUID, PlayerBorderPreferences> preferences = new ConcurrentHashMap<>();
	private final Map<UUID, RenderedBorderKey> activeRendered = new ConcurrentHashMap<>();

	public IslandBorderService(OgskyCoreApi core, Path configPath, BorderRepository repository) {
		this.core = Objects.requireNonNull(core, "core");
		this.configPath = Objects.requireNonNull(configPath, "configPath");
		this.repository = Objects.requireNonNull(repository, "repository");
		this.config = BorderConfigLoader.loadOrCreate(configPath);
	}

	public BorderConfig config() {
		return this.config;
	}

	public PlayerBorderPreferences preferencesFor(UUID playerUuid) {
		Objects.requireNonNull(playerUuid, "playerUuid");
		PlayerBorderPreferences existing = this.preferences.get(playerUuid);
		return existing != null ? existing : PlayerBorderPreferences.defaults(playerUuid, this.config);
	}

	public Optional<RenderedBorderKey> activeRenderedFor(UUID playerUuid) {
		Objects.requireNonNull(playerUuid, "playerUuid");
		return Optional.ofNullable(this.activeRendered.get(playerUuid));
	}

	public boolean isBorderEnabled(UUID playerUuid) {
		return preferencesFor(playerUuid).enabled();
	}

	public BorderColor borderColor(UUID playerUuid) {
		return preferencesFor(playerUuid).color();
	}

	public boolean hasBorderPermission(ServerPlayer player) {
		return this.core.permissions().hasPermission(player, PERM_BORDER, 0);
	}

	public boolean hasColorPermission(ServerPlayer player) {
		return hasBorderPermission(player) && this.core.permissions().hasPermission(player, PERM_BORDER_COLOR, 0);
	}

	public boolean isDimensionAllowed(ResourceKey<Level> dimension) {
		return isDimensionAllowed(dimension, this.config);
	}

	private static boolean isDimensionAllowed(ResourceKey<Level> dimension, BorderConfig cfg) {
		if (OgskyDimensionKeys.OVERWORLD.equals(dimension)) {
			return cfg.allowInOverworld;
		}
		if (OgskyDimensionKeys.NETHER.equals(dimension)) {
			return cfg.allowInNether;
		}
		if (OgskyDimensionKeys.END.equals(dimension)) {
			return cfg.allowInEnd;
		}
		return false;
	}

	public void onServerStarted(MinecraftServer server) {
		loadFromDatabase();
		syncAllOnlinePlayers(server);
	}

	public void loadFromDatabase() {
		this.preferences.clear();
		this.activeRendered.clear();
		Map<UUID, PlayerBorderPreferences> loaded = this.repository.loadAllBlocking(this.config.defaultColorEnum());
		this.preferences.putAll(loaded);
	}

	public void reload(@Nullable MinecraftServer server) {
		this.config = BorderConfigLoader.loadOrCreate(this.configPath);
		if (server != null) {
			syncAllOnlinePlayers(server);
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				this.core.guis().refreshScreen(player, GuiService.MAIN_PANEL_SCREEN_ID);
			}
		}
		OgskyCore.LOGGER.info(
			"Reloaded Ogsky border config (enabled={}, defaultColor={}).",
			this.config.enabled,
			this.config.defaultColor
		);
	}

	public void clearRuntimeState() {
		this.preferences.clear();
		this.activeRendered.clear();
	}

	public void onPlayerJoin(ServerPlayer player) {
		Objects.requireNonNull(player, "player");
		invalidateAndSync(player);
	}

	public void onPlayerDimensionChanged(ServerPlayer player) {
		Objects.requireNonNull(player, "player");
		// Fabric fires AFTER_PLAYER_CHANGE_LEVEL before PlayerList.sendLevelInfo; invalidate so END_SERVER_TICK re-syncs after vanilla.
		this.activeRendered.remove(player.getUUID());
	}

	public void onPlayerRespawn(ServerPlayer player) {
		Objects.requireNonNull(player, "player");
		this.activeRendered.remove(player.getUUID());
	}

	public void onPlayerDisconnect(UUID playerUuid) {
		if (playerUuid != null) {
			this.activeRendered.remove(playerUuid);
		}
	}

	public void onIslandRangeChanged(@Nullable MinecraftServer server, UUID islandId) {
		if (server != null) {
			syncAllOnlinePlayers(server);
		}
	}

	public void onIslandDeletedOrReset(@Nullable MinecraftServer server, UUID islandId) {
		if (server != null) {
			syncAllOnlinePlayers(server);
		}
	}

	public void onServerTick(MinecraftServer server) {
		syncAllOnlinePlayers(server);
	}

	public void syncAllOnlinePlayers(MinecraftServer server) {
		Objects.requireNonNull(server, "server");
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			syncPlayerBorder(player);
		}
	}

	public void invalidateAndSync(ServerPlayer player) {
		Objects.requireNonNull(player, "player");
		this.activeRendered.remove(player.getUUID());
		syncPlayerBorder(player);
	}

	public IslandOperationResult<PlayerBorderPreferences> toggleBorder(
		ServerPlayer actor,
		@Nullable Boolean explicitState
	) {
		return toggleBorder(actor, explicitState, true);
	}

	public IslandOperationResult<PlayerBorderPreferences> toggleBorder(
		ServerPlayer actor,
		@Nullable Boolean explicitState,
		boolean refreshPanel
	) {
		Objects.requireNonNull(actor, "actor");
		BorderConfig cfg = this.config;
		if (!cfg.enabled) {
			return IslandOperationResult.fail("disabled", "Island border is currently disabled.");
		}
		if (!hasBorderPermission(actor)) {
			return IslandOperationResult.fail("no_permission", "You do not have permission to toggle the island border.");
		}

		PlayerBorderPreferences current = preferencesFor(actor.getUUID());
		boolean nextEnabled = explicitState != null ? explicitState : !current.enabled();
		if (explicitState != null && current.enabled() == nextEnabled) {
			syncPlayerBorder(actor);
			return IslandOperationResult.ok(
				"unchanged",
				"Island border is already " + (nextEnabled ? "enabled" : "disabled") + ".",
				current
			);
		}

		PlayerBorderPreferences updated = current.withEnabled(nextEnabled);
		this.preferences.put(actor.getUUID(), updated);
		this.repository.upsertAsync(updated);
		syncPlayerBorder(actor);
		if (refreshPanel) {
			this.core.guis().refreshScreen(actor, GuiService.MAIN_PANEL_SCREEN_ID);
		}

		if (!nextEnabled) {
			return IslandOperationResult.ok("disabled", "Island border disabled.", updated);
		}
		boolean visibleNow = this.activeRendered.containsKey(actor.getUUID());
		String message = visibleNow
			? "Island border enabled (" + updated.color().displayName() + ")."
			: "Island border enabled (" + updated.color().displayName() + "). It will appear when you enter an island.";
		return IslandOperationResult.ok("enabled", message, updated);
	}

	public IslandOperationResult<PlayerBorderPreferences> setBorderColor(
		ServerPlayer actor,
		BorderColor color
	) {
		Objects.requireNonNull(actor, "actor");
		Objects.requireNonNull(color, "color");
		BorderConfig cfg = this.config;
		if (!cfg.enabled) {
			return IslandOperationResult.fail("disabled", "Island border is currently disabled.");
		}
		if (!cfg.allowColorSelection) {
			return IslandOperationResult.fail("color_disabled", "Border color selection is disabled on this server.");
		}
		if (!hasColorPermission(actor)) {
			return IslandOperationResult.fail("no_permission", "You do not have permission to change the island border color.");
		}

		PlayerBorderPreferences current = preferencesFor(actor.getUUID());
		PlayerBorderPreferences updated = current.withColor(color);
		this.preferences.put(actor.getUUID(), updated);
		this.repository.upsertAsync(updated);
		syncPlayerBorder(actor);
		this.core.guis().refreshScreen(actor, GuiService.MAIN_PANEL_SCREEN_ID);

		String msg = updated.enabled()
			? "Island border color set to " + color.displayName() + "."
			: "Island border color set to " + color.displayName() + " (enable with /is border).";
		return IslandOperationResult.ok("color_updated", msg, updated);
	}

	public IslandOperationResult<PlayerBorderPreferences> adminSetEnabled(
		@Nullable MinecraftServer server,
		UUID targetUuid,
		String targetDisplayName,
		@Nullable Boolean explicitState
	) {
		Objects.requireNonNull(targetUuid, "targetUuid");
		Objects.requireNonNull(targetDisplayName, "targetDisplayName");
		PlayerBorderPreferences current = preferencesFor(targetUuid);
		boolean nextEnabled = explicitState != null ? explicitState : !current.enabled();
		PlayerBorderPreferences updated = current.withEnabled(nextEnabled);
		this.preferences.put(targetUuid, updated);
		this.repository.upsertAsync(updated);

		if (server != null) {
			ServerPlayer online = server.getPlayerList().getPlayer(targetUuid);
			if (online != null) {
				syncPlayerBorder(online);
				this.core.guis().refreshScreen(online, GuiService.MAIN_PANEL_SCREEN_ID);
			}
		}

		return IslandOperationResult.ok(
			nextEnabled ? "enabled" : "disabled",
			"Set island border for " + targetDisplayName + " to " + (nextEnabled ? "ON" : "OFF") + ".",
			updated
		);
	}

	public void syncPlayerBorder(ServerPlayer player) {
		Objects.requireNonNull(player, "player");
		UUID playerId = player.getUUID();
		BorderConfig cfg = this.config;
		PlayerBorderPreferences prefs = preferencesFor(playerId);

		Optional<IslandView> visibleIsland = resolveVisibleIsland(player, cfg, prefs);
		if (visibleIsland.isPresent()) {
			IslandView island = visibleIsland.get();
			ServerLevel level = player.level();
			RenderedBorderKey nextKey = new RenderedBorderKey(
				island.islandId(),
				level.dimension(),
				island.centerX(),
				island.centerZ(),
				island.halfSize(),
				prefs.color(),
				cfg.warningBlocks,
				cfg.warningTime
			);
			RenderedBorderKey existing = this.activeRendered.get(playerId);
			if (!nextKey.equals(existing)) {
				if (sendBorderPacket(player, createIslandWorldBorder(
					island,
					prefs.color(),
					level.getGameTime(),
					cfg.warningBlocks,
					cfg.warningTime
				))) {
					this.activeRendered.put(playerId, nextKey);
				}
			}
			return;
		}

		if (this.activeRendered.remove(playerId) != null) {
			sendBorderPacket(player, player.level().getWorldBorder());
		}
	}

	public Optional<IslandView> resolveDisplayableIslandAt(ServerPlayer player) {
		Objects.requireNonNull(player, "player");
		return resolveVisibleIsland(player, this.config, preferencesFor(player.getUUID()));
	}

	private Optional<IslandView> resolveVisibleIsland(
		ServerPlayer player,
		BorderConfig cfg,
		PlayerBorderPreferences prefs
	) {
		if (!cfg.enabled || !prefs.enabled() || player.isRemoved()) {
			return Optional.empty();
		}
		if (!hasBorderPermission(player)) {
			return Optional.empty();
		}
		ResourceKey<Level> dimension = player.level().dimension();
		if (!isDimensionAllowed(dimension, cfg)) {
			return Optional.empty();
		}
		Optional<IslandView> maybeIsland = this.core.islands().findAt(
			dimension,
			player.getBlockX(),
			player.getBlockZ()
		);
		if (maybeIsland.isEmpty()) {
			return Optional.empty();
		}
		IslandView island = maybeIsland.get();
		if (island.isDeleting() || !this.core.islands().canEnterIsland(island.islandId(), player)) {
			return Optional.empty();
		}
		return Optional.of(island);
	}

	/**
	 * Builds a virtual {@link WorldBorder} aligned exactly to {@code island}'s block perimeter
	 * ({@code minX..maxX+1}, {@code minZ..maxZ+1}) with the requested {@link BorderColor}.
	 */
	public static WorldBorder createIslandWorldBorder(
		IslandView island,
		BorderColor color,
		long gameTime,
		int warningBlocks,
		int warningTime
	) {
		Objects.requireNonNull(island, "island");
		Objects.requireNonNull(color, "color");
		WorldBorder border = new WorldBorder();
		border.setCenter(island.centerX() + 0.5D, island.centerZ() + 0.5D);
		border.setWarningBlocks(Math.clamp(warningBlocks, 0, 32));
		border.setWarningTime(Math.clamp(warningTime, 0, 60));
		double diameter = island.halfSize() * 2.0D + 1.0D;
		switch (color) {
			case BLUE -> border.setSize(diameter);
			case GREEN -> border.lerpSizeBetween(diameter - COLOR_LERP_EPSILON, diameter, COLOR_LERP_TICKS, gameTime);
			case RED -> border.lerpSizeBetween(diameter, diameter - COLOR_LERP_EPSILON, COLOR_LERP_TICKS, gameTime);
		}
		return border;
	}

	private static boolean sendBorderPacket(ServerPlayer player, WorldBorder border) {
		if (player.connection == null || player.hasDisconnected()) {
			return false;
		}
		player.connection.send(new ClientboundInitializeBorderPacket(border));
		return true;
	}
}
