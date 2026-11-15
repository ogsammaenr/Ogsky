package me.ogsammaenr.ogsky.core.internal.island;

import me.ogsammaenr.ogsky.core.api.db.DatabaseService;
import me.ogsammaenr.ogsky.core.api.db.ModuleDatabase;
import me.ogsammaenr.ogsky.core.api.flag.FlagType;
import me.ogsammaenr.ogsky.core.api.flag.IslandFlagDefinition;
import me.ogsammaenr.ogsky.core.api.island.IslandService;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfig;
import me.ogsammaenr.ogsky.core.internal.flag.SimpleFlagRegistry;
import me.ogsammaenr.ogsky.core.internal.world.OgskyDimensions;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

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

	public CoreIslandService(CoreConfig config, SimpleFlagRegistry flagRegistry, DatabaseService databaseService) {
		this.config = Objects.requireNonNull(config, "config");
		this.flagRegistry = Objects.requireNonNull(flagRegistry, "flagRegistry");
		this.databaseService = Objects.requireNonNull(databaseService, "databaseService");
		this.grid = new IslandGrid(config.grid.cellSize, config.membership.maxCoop, config.membership.maxTrusted);
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

	public void setWorldSetting(Identifier flagId, boolean enabled) {
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
	public int lockedCellSize() {
		return grid.cellSize();
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
