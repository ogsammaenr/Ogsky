package me.ogsammaenr.ogsky.core.internal.island;

import me.ogsammaenr.ogsky.core.api.blueprint.BlueprintBundleView;
import me.ogsammaenr.ogsky.core.api.db.DatabaseService;
import me.ogsammaenr.ogsky.core.api.db.ModuleDatabase;
import me.ogsammaenr.ogsky.core.api.flag.FlagType;
import me.ogsammaenr.ogsky.core.api.flag.IslandFlagDefinition;
import me.ogsammaenr.ogsky.core.api.island.IslandOperationResult;
import me.ogsammaenr.ogsky.core.api.island.IslandService;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import me.ogsammaenr.ogsky.core.internal.blueprint.BlueprintBundle;
import me.ogsammaenr.ogsky.core.internal.blueprint.CoreBlueprintService;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfig;
import me.ogsammaenr.ogsky.core.internal.flag.SimpleFlagRegistry;
import me.ogsammaenr.ogsky.core.internal.protection.ProtectionResolver;
import me.ogsammaenr.ogsky.core.internal.world.OgskyDimensions;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Server-thread RAM-first {@link IslandService} backed asynchronously by {@code core.db}.
 */
public final class CoreIslandService implements IslandService {
	private final CoreConfig config;
	private final SimpleFlagRegistry flagRegistry;
	private final DatabaseService databaseService;

	private IslandGrid grid;
	private boolean[] worldSettings = new boolean[0];
	private boolean cellSizeLocked;
	private @Nullable CoreBlueprintService blueprintService;
	private @Nullable IslandLifecycleService lifecycleService;
	private @Nullable TeamService teamService;
	private @Nullable ProtectionResolver protectionResolver;

	public CoreIslandService(CoreConfig config, SimpleFlagRegistry flagRegistry, DatabaseService databaseService) {
		this.config = Objects.requireNonNull(config, "config");
		this.flagRegistry = Objects.requireNonNull(flagRegistry, "flagRegistry");
		this.databaseService = Objects.requireNonNull(databaseService, "databaseService");
		this.grid = new IslandGrid(config.grid.cellSize, config.membership.maxCoop, config.membership.maxTrusted);
	}

	public void bindServices(
		CoreBlueprintService blueprintService,
		IslandLifecycleService lifecycleService,
		TeamService teamService
	) {
		this.blueprintService = Objects.requireNonNull(blueprintService, "blueprintService");
		this.lifecycleService = Objects.requireNonNull(lifecycleService, "lifecycleService");
		this.teamService = Objects.requireNonNull(teamService, "teamService");
	}

	public void bindProtectionResolver(ProtectionResolver protectionResolver) {
		this.protectionResolver = Objects.requireNonNull(protectionResolver, "protectionResolver");
	}

	public void initializeWorldSettingsFromDefaults() {
		flagRegistry.freeze();
		List<IslandFlagDefinition> worldDefs = flagRegistry.listByType(FlagType.WORLD_SETTING);
		boolean[] values = new boolean[worldDefs.size()];
		for (int i = 0; i < worldDefs.size(); i++) {
			IslandFlagDefinition def = worldDefs.get(i);
			values[i] = config.flags.resolveWorldSettingDefault(def.id().toString(), def.defaultBoolValue());
		}
		this.worldSettings = values;
	}

	public void loadFromDatabase() {
		flagRegistry.freeze();
		ModuleDatabase db = databaseService.require(CoreIslandRepository.MODULE_ID);
		CoreIslandRepository.LoadedWorldState loaded = CoreIslandRepository.loadWorldState(db, config, flagRegistry);

		IslandGrid freshGrid = new IslandGrid(
			loaded.cellSize(),
			config.membership.maxCoop,
			config.membership.maxTrusted
		);
		for (GridCleanupEntry cleanup : loaded.pendingCleanups()) {
			freshGrid.addCleanupEntry(cleanup);
		}
		for (Island island : loaded.islands()) {
			freshGrid.register(island);
		}

		List<IslandFlagDefinition> worldDefs = flagRegistry.listByType(FlagType.WORLD_SETTING);
		boolean[] values = new boolean[worldDefs.size()];
		for (int i = 0; i < worldDefs.size(); i++) {
			IslandFlagDefinition def = worldDefs.get(i);
			Boolean stored = loaded.worldSettings().get(def.id());
			values[i] = stored != null
				? stored
				: config.flags.resolveWorldSettingDefault(def.id().toString(), def.defaultBoolValue());
		}

		this.grid = freshGrid;
		this.worldSettings = values;
		this.cellSizeLocked = loaded.cellSizeLocked();
		if (!this.cellSizeLocked && !loaded.islands().isEmpty()) {
			markCellSizeLocked();
		}
	}

	public void clear() {
		this.grid.clear();
		this.cellSizeLocked = false;
	}

	public IslandGrid grid() {
		return grid;
	}

	public CoreConfig config() {
		return config;
	}

	public boolean isCellSizeLocked() {
		return cellSizeLocked;
	}

	public void markCellSizeLocked() {
		if (!cellSizeLocked) {
			cellSizeLocked = true;
			databaseService.find(CoreIslandRepository.MODULE_ID)
				.ifPresent(db -> CoreIslandRepository.lockCellSizeAsync(db, grid.cellSize()));
		}
	}

	public void applyWorldSetting(Identifier flagId, boolean enabled) {
		ensureFlagsReady();
		IslandFlagDefinition def = flagRegistry.require(flagId);
		if (def.type() != FlagType.WORLD_SETTING) {
			throw new IllegalArgumentException("Expected WORLD_SETTING flag, got: " + flagId + " (" + def.type() + ")");
		}
		int slot = flagRegistry.slotOf(flagId);
		worldSettings[slot] = enabled;
		databaseService.find(CoreIslandRepository.MODULE_ID)
			.ifPresent(db -> CoreIslandRepository.saveWorldSettingAsync(db, flagId, enabled));
	}

	@Override
	public Optional<IslandView> findById(UUID islandId) {
		return grid.findById(islandId).map(IslandView.class::cast);
	}

	@Override
	public Optional<IslandView> findPrimaryIsland(UUID playerUuid) {
		return grid.findPrimaryIsland(playerUuid).map(IslandView.class::cast);
	}

	@Override
	public List<IslandView> findSecondaryIslands(UUID playerUuid) {
		return grid.findSecondaryIslands(playerUuid).stream().map(IslandView.class::cast).toList();
	}

	@Override
	public Optional<IslandView> findAt(ResourceKey<Level> dimension, int x, int z) {
		if (!OgskyDimensions.isSkyblockDimension(dimension)) {
			return Optional.empty();
		}
		return Optional.ofNullable(grid.findAt(x, z));
	}

	@Override
	public Optional<IslandView> findByGridCell(int gridX, int gridZ) {
		return grid.findByGridCell(gridX, gridZ).map(IslandView.class::cast);
	}

	@Override
	public int islandCount() {
		return grid.size();
	}

	@Override
	public List<IslandView> allIslands() {
		return grid.allIslands().stream()
			.map(IslandView.class::cast)
			.toList();
	}

	@Override
	public int lockedCellSize() {
		return grid.cellSize();
	}

	@Override
	public int maxCoop() {
		return config.membership.maxCoop;
	}

	@Override
	public int maxTrusted() {
		return config.membership.maxTrusted;
	}

	@Override
	public String defaultBlueprintId() {
		return blueprintService != null ? blueprintService.registry().defaultBundleId() : config.blueprints.defaultBundle;
	}

	@Override
	public List<BlueprintBundleView> availableBlueprints() {
		if (blueprintService == null) {
			return List.of();
		}
		return blueprintService.registry().listAll().stream()
			.map(BlueprintBundle::toView)
			.toList();
	}

	@Override
	public boolean isWorldSettingEnabled(Identifier flagId) {
		ensureFlagsReady();
		IslandFlagDefinition def = flagRegistry.require(flagId);
		if (def.type() != FlagType.WORLD_SETTING) {
			throw new IllegalArgumentException("Expected WORLD_SETTING flag, got: " + flagId + " (" + def.type() + ")");
		}
		return worldSettings[flagRegistry.slotOf(flagId)];
	}

	@Override
	public boolean isProtectionAllowed(
		ResourceKey<Level> dimension,
		int x,
		int z,
		UUID playerUuid,
		Identifier flagId,
		boolean adminBypass
	) {
		Objects.requireNonNull(dimension, "dimension");
		Objects.requireNonNull(playerUuid, "playerUuid");
		Objects.requireNonNull(flagId, "flagId");
		if (adminBypass) {
			return true;
		}
		ensureFlagsReady();
		IslandFlagDefinition def = flagRegistry.require(flagId);
		if (def.type() != FlagType.PROTECTION) {
			throw new IllegalArgumentException("Expected PROTECTION flag, got: " + flagId + " (" + def.type() + ")");
		}
		if (!OgskyDimensions.isSkyblockDimension(dimension)) {
			return true;
		}
		Island island = grid.findAt(x, z);
		if (island == null) {
			return config.flags.resolveWildernessAllowed(flagId.toString(), def.wildernessAllowed());
		}
		if (island.isBanned(playerUuid)) {
			return false;
		}
		byte playerRole = island.roleOf(playerUuid);
		byte requiredRank = island.protectionRankBySlot(flagRegistry.slotOf(flagId));
		return playerRole >= requiredRank;
	}

	@Override
	public boolean isSettingEnabledAt(ResourceKey<Level> dimension, int x, int z, Identifier flagId) {
		Objects.requireNonNull(dimension, "dimension");
		Objects.requireNonNull(flagId, "flagId");
		ensureFlagsReady();
		IslandFlagDefinition def = flagRegistry.require(flagId);
		if (def.type() != FlagType.SETTING) {
			throw new IllegalArgumentException("Expected SETTING flag, got: " + flagId + " (" + def.type() + ")");
		}
		if (!OgskyDimensions.isSkyblockDimension(dimension)) {
			return config.flags.resolveSettingDefault(flagId.toString(), def.defaultBoolValue());
		}
		Island island = grid.findAt(x, z);
		if (island == null) {
			return config.flags.resolveSettingDefault(flagId.toString(), def.defaultBoolValue());
		}
		return island.isSettingEnabledBySlot(flagRegistry.slotOf(flagId));
	}

	@Override
	public String roleDisplayName(byte role) {
		return config.roleDisplayName(role);
	}

	@Override
	public IslandOperationResult<Byte> setProtectionRank(
		@Nullable UUID actorId,
		UUID islandId,
		Identifier flagId,
		byte newMinRank,
		boolean bypassRole
	) {
		return withIsland(islandId, island ->
			requireLifecycle().setProtectionRank(actorId, island, flagId, newMinRank, bypassRole).toApiResult()
		);
	}

	@Override
	public IslandOperationResult<Boolean> setSettingFlag(
		@Nullable MinecraftServer server,
		@Nullable UUID actorId,
		UUID islandId,
		Identifier flagId,
		boolean enabled,
		boolean bypassRole
	) {
		return withIsland(islandId, island ->
			requireLifecycle().setSettingFlag(server, actorId, island, flagId, enabled, bypassRole).toApiResult()
		);
	}

	@Override
	public IslandOperationResult<Boolean> setWorldSetting(Identifier flagId, boolean enabled) {
		return requireLifecycle().setWorldSetting(flagId, enabled).toApiResult();
	}

	@Override
	public IslandOperationResult<Void> kickMember(
		@Nullable MinecraftServer server,
		UUID islandId,
		UUID actorId,
		UUID targetId,
		boolean bypassRole
	) {
		return withIsland(islandId, island ->
			requireTeam().kickMember(server, island, actorId, targetId, bypassRole).toApiResult()
		);
	}

	@Override
	public IslandOperationResult<Byte> promoteMember(
		UUID islandId,
		UUID actorId,
		UUID targetId,
		boolean bypassOwnership
	) {
		return withIsland(islandId, island ->
			requireTeam().promoteMember(island, actorId, targetId, bypassOwnership).toApiResult()
		);
	}

	@Override
	public IslandOperationResult<Byte> demoteMember(
		UUID islandId,
		UUID actorId,
		UUID targetId,
		boolean bypassOwnership
	) {
		return withIsland(islandId, island ->
			requireTeam().demoteMember(island, actorId, targetId, bypassOwnership).toApiResult()
		);
	}

	@Override
	public IslandOperationResult<Void> transferOwnership(
		UUID islandId,
		UUID actorId,
		UUID newOwnerId,
		boolean bypassOwnership
	) {
		return withIsland(islandId, island ->
			requireTeam().transferOwnership(island, actorId, newOwnerId, bypassOwnership).toApiResult()
		);
	}

	@Override
	public IslandOperationResult<Void> removeCoop(
		@Nullable MinecraftServer server,
		UUID islandId,
		UUID actorId,
		UUID targetId,
		boolean bypassRole
	) {
		return withIsland(islandId, island ->
			requireTeam().removeCoop(server, island, actorId, targetId, bypassRole).toApiResult()
		);
	}

	@Override
	public IslandOperationResult<Void> removeTrust(
		@Nullable MinecraftServer server,
		UUID islandId,
		UUID actorId,
		UUID targetId,
		boolean bypassRole
	) {
		return withIsland(islandId, island ->
			requireTeam().removeTrust(server, island, actorId, targetId, bypassRole).toApiResult()
		);
	}

	@Override
	public IslandOperationResult<Void> banPlayer(
		@Nullable MinecraftServer server,
		UUID islandId,
		UUID actorId,
		UUID targetId,
		boolean bypassRole
	) {
		return withIsland(islandId, island ->
			requireTeam().banPlayer(server, island, actorId, targetId, bypassRole).toApiResult()
		);
	}

	@Override
	public IslandOperationResult<Void> unbanPlayer(
		UUID islandId,
		UUID actorId,
		UUID targetId,
		boolean bypassRole
	) {
		return withIsland(islandId, island ->
			requireTeam().unbanPlayer(island, actorId, targetId, bypassRole).toApiResult()
		);
	}

	@Override
	public IslandOperationResult<Void> leaveIsland(
		@Nullable MinecraftServer server,
		UUID playerId,
		UUID islandId
	) {
		return withIsland(islandId, island ->
			requireTeam().leaveIsland(server, playerId, island).toApiResult()
		);
	}

	@Override
	public IslandOperationResult<IslandView> createIsland(
		@Nullable MinecraftServer server,
		@Nullable ServerPlayer actor,
		UUID ownerId,
		@Nullable String requestedBundleId
	) {
		return requireLifecycle().createIsland(server, actor, ownerId, requestedBundleId).toApiResult(IslandView.class::cast);
	}

	@Override
	public IslandOperationResult<IslandView> resetIsland(
		@Nullable MinecraftServer server,
		UUID actorId,
		UUID islandId,
		@Nullable String requestedBundleId,
		boolean bypassOwnership
	) {
		return requireLifecycle().resetIsland(server, actorId, islandId, requestedBundleId, bypassOwnership).toApiResult(IslandView.class::cast);
	}

	@Override
	public IslandOperationResult<Void> teleportHome(
		MinecraftServer server,
		ServerPlayer player,
		UUID islandId,
		@Nullable ResourceKey<Level> targetDimension
	) {
		return withIsland(islandId, island ->
			requireLifecycle().teleportHome(server, player, island, targetDimension).toApiResult()
		);
	}

	@Override
	public boolean canEnterIsland(UUID islandId, ServerPlayer player) {
		Objects.requireNonNull(islandId, "islandId");
		Objects.requireNonNull(player, "player");
		Optional<Island> island = grid.findById(islandId);
		if (island.isEmpty()) {
			return false;
		}
		if (protectionResolver != null) {
			return protectionResolver.canEnterIsland(island.get(), player);
		}
		return !island.get().isBanned(player.getUUID());
	}

	@Override
	public boolean isLocationSafe(ServerLevel level, double x, double y, double z) {
		Objects.requireNonNull(level, "level");
		return SafeLocationCheck.isLocationSafe(level, x, y, z);
	}

	@Override
	public IslandOperationResult<Void> teleportToLocation(
		MinecraftServer server,
		ServerPlayer player,
		UUID islandId,
		ResourceKey<Level> targetDimension,
		double x,
		double y,
		double z,
		float yaw,
		float pitch,
		boolean enforceEntryAccess
	) {
		return withIsland(islandId, island -> {
			if (enforceEntryAccess && protectionResolver != null && !protectionResolver.canEnterIsland(island, player)) {
				String code = island.isBanned(player.getUUID()) ? "banned" : "locked";
				String msg = island.isBanned(player.getUUID())
					? "You are banned from that island."
					: "That island is currently locked.";
				return IslandOperationResult.fail(code, msg);
			}
			return requireLifecycle()
				.teleportToLocation(server, player, island, targetDimension, x, y, z, yaw, pitch)
				.toApiResult();
		});
	}

	@Override
	public IslandOperationResult<Void> setHome(
		ServerPlayer player,
		UUID islandId,
		boolean bypassRole
	) {
		return withIsland(islandId, island ->
			requireLifecycle().setHome(player, island, bypassRole).toApiResult()
		);
	}

	private <T> IslandOperationResult<T> withIsland(UUID islandId, Function<Island, IslandOperationResult<T>> action) {
		Objects.requireNonNull(islandId, "islandId");
		Optional<Island> island = grid.findById(islandId);
		if (island.isEmpty()) {
			return IslandOperationResult.fail("no_island", "Island does not exist.");
		}
		return action.apply(island.get());
	}

	private IslandLifecycleService requireLifecycle() {
		if (lifecycleService == null) {
			throw new IllegalStateException("IslandLifecycleService is not bound to CoreIslandService.");
		}
		return lifecycleService;
	}

	private TeamService requireTeam() {
		if (teamService == null) {
			throw new IllegalStateException("TeamService is not bound to CoreIslandService.");
		}
		return teamService;
	}

	public int flagSlot(Identifier flagId) {
		ensureFlagsReady();
		return flagRegistry.slotOf(flagId);
	}

	private void ensureFlagsReady() {
		if (!flagRegistry.isFrozen() || worldSettings.length != flagRegistry.count(FlagType.WORLD_SETTING)) {
			initializeWorldSettingsFromDefaults();
		}
	}
}
