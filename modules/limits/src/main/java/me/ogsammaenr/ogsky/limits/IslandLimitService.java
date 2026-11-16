package me.ogsammaenr.ogsky.limits;

import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.OgskyCoreApi;
import me.ogsammaenr.ogsky.core.api.flag.FlagMode;
import me.ogsammaenr.ogsky.core.api.flag.IslandFlagDefinition;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import me.ogsammaenr.ogsky.core.api.level.IslandLevelProvider;
import me.ogsammaenr.ogsky.core.api.level.IslandLevelSnapshot;
import me.ogsammaenr.ogsky.core.api.mutation.BlockChangeObserver;
import me.ogsammaenr.ogsky.core.api.mutation.BlockMutationDecision;
import me.ogsammaenr.ogsky.core.api.mutation.BlockPlacementGuard;
import me.ogsammaenr.ogsky.core.api.mutation.BlockPlacementSource;
import me.ogsammaenr.ogsky.core.api.mutation.EntityLifecycleObserver;
import me.ogsammaenr.ogsky.core.api.mutation.EntitySpawnGuard;
import me.ogsammaenr.ogsky.core.api.mutation.EntitySpawnSource;
import me.ogsammaenr.ogsky.core.api.scan.IslandScanOptions;
import me.ogsammaenr.ogsky.core.api.scan.IslandScanResult;
import me.ogsammaenr.ogsky.core.api.world.OgskyDimensionKeys;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manages per-island physical block & entity limit counters in RAM, syncs with {@link LimitRepository},
 * and enforces live placement and entity pre-spawn guards via {@code core.mutations()}.
 */
public final class IslandLimitService implements BlockPlacementGuard, BlockChangeObserver, EntitySpawnGuard, EntityLifecycleObserver {
	public static final Identifier VIEW_LIMITS_FLAG = Identifier.fromNamespaceAndPath("ogsky_limits", "view_limits");

	public record RuleStatus(
		LimitRuleTable.LimitRule rule,
		int used,
		int effectiveLimit,
		boolean scanning,
		boolean synchronizedFromWorld
	) {
		public int remaining() {
			return Math.max(0, effectiveLimit - used);
		}

		public double usageRatio() {
			if (effectiveLimit <= 0) {
				return used > 0 ? Double.MAX_VALUE : 0.0D;
			}
			return (double) used / (double) effectiveLimit;
		}
	}

	private record TrackedEntity(UUID islandId, int[] ruleIndices) {
	}

	private final OgskyCoreApi core;
	private final Path configPath;
	private LimitConfig config;
	private LimitRuleTable ruleTable;
	private final @Nullable LimitRepository repository;
	private final Map<UUID, IslandLimitState> islandStates = new HashMap<>();
	private final Map<UUID, TrackedEntity> trackedEntities = new HashMap<>();
	private final Map<UUID, Long> notifyCooldowns = new HashMap<>();
	private final Map<UUID, CompletableFuture<IslandScanResult>> inFlightScans = new HashMap<>();
	private int tickCounter;
	private boolean serverTagsReady;

	public IslandLimitService(OgskyCoreApi core, Path configPath, @Nullable LimitRepository repository) {
		this.core = Objects.requireNonNull(core, "core");
		this.configPath = Objects.requireNonNull(configPath, "configPath");
		this.repository = repository;
		this.config = LimitConfigLoader.loadOrCreate(configPath);
		// Block and entity tags are only bound after server startup; defer tag resolution until onServerStarted.
		this.ruleTable = LimitRuleTable.compile(this.config, false);
	}

	/**
	 * Test constructor accepting an explicit config and rule table without disk config I/O.
	 */
	public IslandLimitService(OgskyCoreApi core, LimitConfig config, LimitRuleTable ruleTable, @Nullable LimitRepository repository) {
		this.core = Objects.requireNonNull(core, "core");
		this.configPath = LimitConfigLoader.DEFAULT_PATH;
		this.repository = repository;
		this.config = Objects.requireNonNull(config, "config");
		this.config.ensureDefaultsAndValidate();
		this.ruleTable = Objects.requireNonNull(ruleTable, "ruleTable");
	}

	public IslandFlagDefinition createViewLimitsFlagDefinition() {
		return IslandFlagDefinition.protection(
			VIEW_LIMITS_FLAG,
			FlagMode.BASIC,
			"minecraft:hopper",
			180,
			config.parseDefaultViewLimitsRole(),
			false
		);
	}

	public LimitConfig config() {
		return config;
	}

	public LimitRuleTable ruleTable() {
		return ruleTable;
	}

	public boolean isScanInProgress(UUID islandId) {
		CompletableFuture<IslandScanResult> existing = inFlightScans.get(islandId);
		return existing != null && !existing.isDone();
	}

	public boolean isSynchronized(UUID islandId) {
		IslandLimitState state = islandStates.get(islandId);
		return state != null && state.synchronizedFromWorld;
	}

	public int rescanAll(MinecraftServer server) {
		int queued = 0;
		for (IslandView island : core.islands().allIslands()) {
			if (!island.isPasting() && !island.isDeleting()) {
				requestRescan(server, island.islandId(), true);
				queued++;
			}
		}
		return queued;
	}

	public boolean hasBypass(ServerPlayer player) {
		if (core.permissions().isBypassActive(player)) {
			return true;
		}
		return core.permissions().hasPermission(player, config.bypassPermission, 2);
	}

	public boolean canViewLimits(IslandView island, ServerPlayer viewer) {
		return hasBypass(viewer) || canViewLimits(island, viewer.getUUID(), false);
	}

	public boolean canViewLimits(IslandView island, UUID viewerUuid, boolean adminBypass) {
		if (adminBypass) {
			return true;
		}
		if (island.isBanned(viewerUuid)) {
			return false;
		}
		return island.roleOf(viewerUuid) >= island.protectionRank(VIEW_LIMITS_FLAG);
	}

	public int effectiveLimit(UUID islandId, LimitRuleTable.LimitRule rule) {
		int base = rule.maxLimit();
		if (base <= 0 || !config.levelScaling.enabled) {
			return base;
		}
		Optional<IslandLevelProvider> maybeLevel = core.services().find(IslandLevelProvider.class);
		if (maybeLevel.isEmpty()) {
			return base;
		}
		Optional<IslandLevelSnapshot> snap = maybeLevel.get().findSnapshot(islandId);
		if (snap.isEmpty()) {
			return base;
		}
		long level = Math.max(0L, snap.get().levelValue());
		double bonus = 1.0D + (level / 100.0D) * config.levelScaling.bonusPer100Levels;
		double multiplier = Math.min(config.levelScaling.maxBonusMultiplier, Math.max(1.0D, bonus));
		return Math.max(base, (int) Math.floor(base * multiplier));
	}

	public void onServerStarted(MinecraftServer server) {
		this.serverTagsReady = true;
		// Recompile rule table after MC datapacks & tags are bound.
		LimitRuleTable oldTable = this.ruleTable;
		LimitRuleTable compiled = LimitRuleTable.compile(this.config, true);
		this.ruleTable = compiled;

		islandStates.clear();
		trackedEntities.clear();
		notifyCooldowns.clear();
		inFlightScans.clear();
		tickCounter = 0;

		if (repository != null) {
			List<LimitRepository.PersistedIslandRecord> rows = repository.loadAllBlocking();
			for (LimitRepository.PersistedIslandRecord record : rows) {
				if (core.islands().findById(record.islandId()).isEmpty()) {
					continue;
				}
				IslandLimitState state = getOrCreateState(record.islandId());
				for (LimitRepository.PersistedCount count : record.counts()) {
					if ("BLOCK".equals(count.kind())) {
						Integer idx = compiled.findBlockRuleIndex(count.ruleKey());
						if (idx != null) {
							state.blockCounts[idx] = count.count();
						}
					} else if ("ENTITY".equals(count.kind())) {
						Integer idx = compiled.findEntityRuleIndex(count.ruleKey());
						if (idx != null) {
							state.entityCounts[idx] = count.count();
						}
					}
				}
				boolean hashMatches = compiled.rulesHash().equals(record.rulesHash()) && record.syncedAt() > 0L;
				state.syncedRulesHash = hashMatches ? record.rulesHash() : "";
				state.lastSyncedAtMillis = hashMatches ? record.syncedAt() : 0L;
				state.synchronizedFromWorld = hashMatches;
				state.dirty = false;
			}
		}

		if (oldTable != compiled) {
			for (IslandLimitState state : islandStates.values()) {
				if (!compiled.rulesHash().equals(state.syncedRulesHash)) {
					state.synchronizedFromWorld = false;
				}
			}
		}

		Set<UUID> liveIds = new HashSet<>();
		for (IslandView island : core.islands().allIslands()) {
			liveIds.add(island.islandId());
		}
		if (repository != null) {
			repository.reconcileOrphansAsync(liveIds);
		}
	}

	public void onServerStopping() {
		inFlightScans.clear();
		flushDirtyBlocking();
	}

	public void clearRuntimeState() {
		islandStates.clear();
		trackedEntities.clear();
		notifyCooldowns.clear();
		inFlightScans.clear();
		tickCounter = 0;
	}

	public void tick(MinecraftServer server) {
		tickCounter++;
		if (tickCounter >= config.dbFlushIntervalTicks) {
			tickCounter = 0;
			flushDirtyAsync();
		}
	}

	public void reload(@Nullable MinecraftServer server) {
		LimitConfig freshConfig = LimitConfigLoader.loadOrCreate(configPath);
		LimitRuleTable oldTable = this.ruleTable;
		LimitRuleTable newTable = LimitRuleTable.compile(freshConfig, serverTagsReady || server != null);
		this.config = freshConfig;
		this.ruleTable = newTable;

		trackedEntities.clear();
		for (IslandLimitState state : islandStates.values()) {
			state.remapFrom(oldTable, newTable);
		}
		inFlightScans.clear();

		if (server != null && !oldTable.rulesHash().equals(newTable.rulesHash())) {
			for (IslandView island : core.islands().allIslands()) {
				if (!island.isPasting() && !island.isDeleting()) {
					requestRescan(server, island.islandId(), true);
				}
			}
		}
	}

	public void onBlueprintPasteCompleted(@Nullable MinecraftServer server, UUID islandId) {
		if (!config.autoScanOnBlueprintComplete || server == null) {
			return;
		}
		requestRescan(server, islandId, true);
	}

	public void onIslandReset(UUID islandId) {
		IslandLimitState state = islandStates.get(islandId);
		if (state != null) {
			state.resetAllCounts();
		}
		inFlightScans.remove(islandId);
		trackedEntities.entrySet().removeIf(entry -> entry.getValue().islandId().equals(islandId));
		if (repository != null) {
			repository.deleteIslandAsync(islandId);
		}
	}

	public void onIslandDeleted(UUID islandId) {
		IslandLimitState state = islandStates.remove(islandId);
		if (state != null) {
			state.cancelScan();
		}
		inFlightScans.remove(islandId);
		trackedEntities.entrySet().removeIf(entry -> entry.getValue().islandId().equals(islandId));
		if (repository != null) {
			repository.deleteIslandAsync(islandId);
		}
	}

	public void onIslandRangeChanged(@Nullable MinecraftServer server, UUID islandId) {
		IslandLimitState state = islandStates.get(islandId);
		if (state != null) {
			state.synchronizedFromWorld = false;
			state.syncedRulesHash = "";
		}
		if (server != null) {
			requestRescan(server, islandId, true);
		}
	}

	public List<RuleStatus> getRuleStatuses(MinecraftServer server, UUID islandId) {
		IslandLimitState state = getOrCreateState(islandId);
		if (!state.synchronizedFromWorld && !state.scanning && config.countExistingOnFirstAccess && server != null) {
			Optional<IslandView> island = core.islands().findById(islandId);
			if (island.isPresent() && !island.get().isPasting() && !island.get().isDeleting()) {
				requestRescan(server, islandId, false);
			}
		}
		return getRuleStatusesSnapshot(islandId);
	}

	public List<RuleStatus> getRuleStatusesSnapshot(UUID islandId) {
		IslandLimitState state = getOrCreateState(islandId);
		LimitRuleTable table = this.ruleTable;
		List<RuleStatus> list = new ArrayList<>(table.allRules().size());
		for (LimitRuleTable.LimitRule rule : table.blockRules()) {
			int used = rule.index() < state.blockCounts.length ? state.blockCounts[rule.index()] : 0;
			int max = effectiveLimit(islandId, rule);
			list.add(new RuleStatus(rule, used, max, state.scanning, state.synchronizedFromWorld));
		}
		for (LimitRuleTable.LimitRule rule : table.entityRules()) {
			int used = rule.index() < state.entityCounts.length ? state.entityCounts[rule.index()] : 0;
			int max = effectiveLimit(islandId, rule);
			list.add(new RuleStatus(rule, used, max, state.scanning, state.synchronizedFromWorld));
		}
		return list;
	}

	public CompletableFuture<IslandScanResult> requestRescan(MinecraftServer server, UUID islandId, boolean force) {
		CompletableFuture<IslandScanResult> existing = inFlightScans.get(islandId);
		if (!force) {
			if (existing != null && !existing.isDone()) {
				return existing;
			}
		} else if (existing != null && !existing.isDone()) {
			existing.cancel(false);
			inFlightScans.remove(islandId, existing);
		}

		LimitRuleTable capturedTable = this.ruleTable;
		IslandLimitState state = getOrCreateState(islandId);
		long token = state.beginScan();
		int[] scannedBlocks = new int[capturedTable.blockRuleCount()];
		int[] scannedEntities = new int[capturedTable.entityRuleCount()];

		IslandScanOptions scanOptions = new IslandScanOptions(
			config.scanChunksPerTick,
			TimeUnit.MILLISECONDS.toNanos(config.scanMaxMillisPerTick),
			config.scanIncludeUnloadedChunks,
			8
		);

		CompletableFuture<IslandScanResult> future = core.scans().scanIslandWithEntities(
			server,
			islandId,
			scanOptions,
			(blockState, count) -> {
				int[] indices = capturedTable.ruleIndicesFor(blockState);
				for (int idx : indices) {
					if (idx >= 0 && idx < scannedBlocks.length) {
						scannedBlocks[idx] += count;
					}
				}
			},
			(entityType, count) -> {
				if (entityType == EntityTypes.PLAYER) {
					return;
				}
				int[] indices = capturedTable.ruleIndicesFor(entityType);
				for (int idx : indices) {
					if (idx >= 0 && idx < scannedEntities.length) {
						scannedEntities[idx] += count;
					}
				}
			}
		);
		inFlightScans.put(islandId, future);
		future.whenComplete((result, error) -> {
			inFlightScans.remove(islandId, future);
			if (error != null || result == null || result.aborted()) {
				if (state.scanToken == token) {
					state.cancelScan();
				}
				return;
			}
			if (this.ruleTable != capturedTable) {
				state.cancelScan();
				requestRescan(server, islandId, false);
				return;
			}
			state.finishScan(token, scannedBlocks, scannedEntities, capturedTable.rulesHash(), System.currentTimeMillis());
			if (repository != null) {
				repository.saveBatchAsync(List.of(toRecord(state, capturedTable)));
				state.dirty = false;
			}
			core.guis().refreshIslandScreens(islandId);
		});

		return future;
	}

	@Override
	public BlockMutationDecision checkPlacement(
		ServerLevel level,
		BlockPos pos,
		BlockState newState,
		BlockState previousState,
		@Nullable ServerPlayer actor,
		BlockPlacementSource source
	) {
		if (!config.enforce || !OgskyDimensionKeys.isSkyblockDimension(level.dimension())) {
			return BlockMutationDecision.allow();
		}
		LimitRuleTable table = this.ruleTable;
		int[] newIndices = table.ruleIndicesFor(newState);
		if (newIndices.length == 0) {
			return BlockMutationDecision.allow();
		}
		Optional<IslandView> maybeIsland = core.islands().findAt(level.dimension(), pos.getX(), pos.getZ());
		if (maybeIsland.isEmpty()) {
			return BlockMutationDecision.allow();
		}
		IslandView island = maybeIsland.get();
		if (island.isPasting() || island.isDeleting()) {
			return BlockMutationDecision.allow();
		}
		if (actor != null && hasBypass(actor)) {
			return BlockMutationDecision.allow();
		}

		IslandLimitState state = getOrCreateState(island.islandId());
		maybeLazyRescan(level.getServer(), island, state);

		int[] oldIndices = table.ruleIndicesFor(previousState);
		LimitRuleTable.LimitRule violated = findViolatedBlockRule(island.islandId(), state, table, newIndices, oldIndices);
		if (violated == null) {
			return BlockMutationDecision.allow();
		}

		if (source == BlockPlacementSource.LIQUID_GENERATOR) {
			BlockMutationDecision liquidDecision = resolveLiquidFallback(level.getServer(), island.islandId(), state, table, newState, oldIndices, actor, violated);
			if (liquidDecision != null) {
				return liquidDecision;
			}
		}

		if (actor != null) {
			int used = violated.index() < state.blockCounts.length ? state.blockCounts[violated.index()] : 0;
			int max = effectiveLimit(island.islandId(), violated);
			notifyBlocked(actor, violated.displayName(), used, max);
		}
		return BlockMutationDecision.deny();
	}

	@Override
	public void onBlockChanged(
		ServerLevel level,
		BlockPos pos,
		BlockState oldState,
		BlockState newState,
		int updateFlags
	) {
		if (!OgskyDimensionKeys.isSkyblockDimension(level.dimension())) {
			return;
		}
		LimitRuleTable table = this.ruleTable;
		if (table.blockRuleCount() == 0) {
			return;
		}
		int[] oldIndices = table.ruleIndicesFor(oldState);
		int[] newIndices = table.ruleIndicesFor(newState);
		if (oldIndices.length == 0 && newIndices.length == 0) {
			return;
		}
		Optional<IslandView> maybeIsland = core.islands().findAt(level.dimension(), pos.getX(), pos.getZ());
		if (maybeIsland.isEmpty()) {
			return;
		}
		IslandView island = maybeIsland.get();
		if (island.isPasting() || island.isDeleting()) {
			return;
		}
		IslandLimitState state = getOrCreateState(island.islandId());
		maybeLazyRescan(level.getServer(), island, state);

		if (oldIndices.length > 0) {
			state.addBlockDelta(oldIndices, -1);
		}
		if (newIndices.length > 0) {
			state.addBlockDelta(newIndices, 1);
		}
	}

	@Override
	public boolean allowEntitySpawn(
		ServerLevel level,
		EntityType<?> entityType,
		double x,
		double y,
		double z,
		@Nullable ServerPlayer actor,
		EntitySpawnSource source
	) {
		if (!config.enforce || entityType == EntityTypes.PLAYER || !OgskyDimensionKeys.isSkyblockDimension(level.dimension())) {
			return true;
		}
		LimitRuleTable table = this.ruleTable;
		int[] indices = table.ruleIndicesFor(entityType);
		if (indices.length == 0) {
			return true;
		}
		Optional<IslandView> maybeIsland = core.islands().findAt(level.dimension(), Mth.floor(x), Mth.floor(z));
		if (maybeIsland.isEmpty()) {
			return true;
		}
		IslandView island = maybeIsland.get();
		if (island.isPasting() || island.isDeleting()) {
			return true;
		}
		if (actor != null && hasBypass(actor)) {
			return true;
		}

		IslandLimitState state = getOrCreateState(island.islandId());
		maybeLazyRescan(level.getServer(), island, state);

		for (int idx : indices) {
			if (idx < 0 || idx >= table.entityRuleCount()) {
				continue;
			}
			LimitRuleTable.LimitRule rule = table.entityRule(idx);
			int max = effectiveLimit(island.islandId(), rule);
			int used = idx < state.entityCounts.length ? state.entityCounts[idx] : 0;
			if (used + 1 > max) {
				if (actor != null && source != EntitySpawnSource.NATURAL) {
					notifyBlocked(actor, rule.displayName(), used, max);
				}
				return false;
			}
		}
		return true;
	}

	@Override
	public void onEntityAdded(ServerLevel level, Entity entity) {
		if (entity instanceof ServerPlayer || !OgskyDimensionKeys.isSkyblockDimension(level.dimension())) {
			return;
		}
		LimitRuleTable table = this.ruleTable;
		int[] indices = table.ruleIndicesFor(entity.getType());
		if (indices.length == 0) {
			return;
		}
		if (trackedEntities.containsKey(entity.getUUID())) {
			return;
		}
		Optional<IslandView> maybeIsland = core.islands().findAt(level.dimension(), Mth.floor(entity.getX()), Mth.floor(entity.getZ()));
		if (maybeIsland.isEmpty()) {
			return;
		}
		IslandView island = maybeIsland.get();
		if (island.isPasting() || island.isDeleting()) {
			return;
		}
		IslandLimitState state = getOrCreateState(island.islandId());
		maybeLazyRescan(level.getServer(), island, state);
		state.addEntityDelta(indices, 1);
		trackedEntities.put(entity.getUUID(), new TrackedEntity(island.islandId(), indices));
	}

	@Override
	public void onEntityRemoved(ServerLevel level, Entity entity) {
		if (entity instanceof ServerPlayer) {
			return;
		}
		TrackedEntity tracked = trackedEntities.remove(entity.getUUID());
		if (tracked != null) {
			IslandLimitState state = islandStates.get(tracked.islandId());
			if (state != null) {
				state.addEntityDelta(tracked.ruleIndices(), -1);
			}
			return;
		}
		if (!OgskyDimensionKeys.isSkyblockDimension(level.dimension())) {
			return;
		}
		LimitRuleTable table = this.ruleTable;
		int[] indices = table.ruleIndicesFor(entity.getType());
		if (indices.length == 0) {
			return;
		}
		Optional<IslandView> maybeIsland = core.islands().findAt(level.dimension(), Mth.floor(entity.getX()), Mth.floor(entity.getZ()));
		if (maybeIsland.isEmpty()) {
			return;
		}
		IslandLimitState state = islandStates.get(maybeIsland.get().islandId());
		if (state != null) {
			state.addEntityDelta(indices, -1);
		}
	}

	@Override
	public boolean onEntityCrossedBlockColumn(
		ServerLevel level,
		Entity entity,
		int fromBlockX,
		int fromBlockZ,
		int toBlockX,
		int toBlockZ
	) {
		if (entity instanceof ServerPlayer || !OgskyDimensionKeys.isSkyblockDimension(level.dimension())) {
			return true;
		}
		LimitRuleTable table = this.ruleTable;
		int[] indices = table.ruleIndicesFor(entity.getType());
		if (indices.length == 0) {
			return true;
		}

		UUID fromIslandId = core.islands().findAt(level.dimension(), fromBlockX, fromBlockZ)
			.map(IslandView::islandId)
			.orElse(null);
		UUID toIslandId = core.islands().findAt(level.dimension(), toBlockX, toBlockZ)
			.map(IslandView::islandId)
			.orElse(null);
		if (Objects.equals(fromIslandId, toIslandId)) {
			return true;
		}

		if (toIslandId != null && !allowEntitySpawn(level, entity.getType(), toBlockX + 0.5, entity.getY(), toBlockZ + 0.5, null, EntitySpawnSource.CROSS_ISLAND_MOVE)) {
			return false;
		}

		TrackedEntity existingTracked = trackedEntities.get(entity.getUUID());
		UUID effectiveFrom = existingTracked != null ? existingTracked.islandId() : fromIslandId;
		int[] effectiveIndices = existingTracked != null ? existingTracked.ruleIndices() : indices;
		if (effectiveFrom != null) {
			IslandLimitState oldState = islandStates.get(effectiveFrom);
			if (oldState != null) {
				oldState.addEntityDelta(effectiveIndices, -1);
			}
		}
		if (toIslandId != null) {
			IslandLimitState newState = getOrCreateState(toIslandId);
			newState.addEntityDelta(indices, 1);
			trackedEntities.put(entity.getUUID(), new TrackedEntity(toIslandId, indices));
		} else {
			trackedEntities.remove(entity.getUUID());
		}
		return true;
	}

	private @Nullable BlockMutationDecision resolveLiquidFallback(
		@Nullable MinecraftServer server,
		UUID islandId,
		IslandLimitState state,
		LimitRuleTable table,
		BlockState originalState,
		int[] previousIndices,
		@Nullable ServerPlayer actor,
		LimitRuleTable.LimitRule violated
	) {
		return switch (config.liquidGeneratorPolicy) {
			case CANCEL -> null;
			case FALLBACK_TO_STONE -> {
				if (originalState.getBlock() != Blocks.STONE) {
					BlockState stone = Blocks.STONE.defaultBlockState();
					int[] stoneIndices = table.ruleIndicesFor(stone);
					if (stoneIndices.length == 0
						|| findViolatedBlockRule(islandId, state, table, stoneIndices, previousIndices) == null) {
						yield BlockMutationDecision.replace(stone);
					}
				}
				yield null;
			}
			case FULL_FALLBACK_WITH_AIR -> {
				if (originalState.getBlock() != Blocks.STONE) {
					BlockState stone = Blocks.STONE.defaultBlockState();
					int[] stoneIndices = table.ruleIndicesFor(stone);
					if (stoneIndices.length == 0
						|| findViolatedBlockRule(islandId, state, table, stoneIndices, previousIndices) == null) {
						yield BlockMutationDecision.replace(stone);
					}
				}
				yield BlockMutationDecision.replace(Blocks.AIR.defaultBlockState());
			}
		};
	}

	private LimitRuleTable.@Nullable LimitRule findViolatedBlockRule(
		UUID islandId,
		IslandLimitState state,
		LimitRuleTable table,
		int[] newIndices,
		int[] oldIndices
	) {
		for (int idx : newIndices) {
			if (idx < 0 || idx >= table.blockRuleCount()) {
				continue;
			}
			if (containsInt(oldIndices, idx)) {
				continue;
			}
			LimitRuleTable.LimitRule rule = table.blockRule(idx);
			int max = effectiveLimit(islandId, rule);
			int used = idx < state.blockCounts.length ? state.blockCounts[idx] : 0;
			if (used + 1 > max) {
				return rule;
			}
		}
		return null;
	}

	private static boolean containsInt(int[] arr, int target) {
		for (int value : arr) {
			if (value == target) {
				return true;
			}
		}
		return false;
	}

	private IslandLimitState getOrCreateState(UUID islandId) {
		return islandStates.computeIfAbsent(
			islandId,
			id -> new IslandLimitState(id, ruleTable.blockRuleCount(), ruleTable.entityRuleCount())
		);
	}

	private void maybeLazyRescan(@Nullable MinecraftServer server, IslandView island, IslandLimitState state) {
		if (!state.synchronizedFromWorld
			&& !state.scanning
			&& config.countExistingOnFirstAccess
			&& server != null
			&& !island.isPasting()
			&& !island.isDeleting()) {
			requestRescan(server, island.islandId(), false);
		}
	}

	private void notifyBlocked(ServerPlayer player, String ruleName, int used, int max) {
		long now = System.currentTimeMillis();
		if (config.notifyCooldownMillis > 0) {
			Long last = notifyCooldowns.get(player.getUUID());
			if (last != null && now - last < config.notifyCooldownMillis) {
				return;
			}
			notifyCooldowns.put(player.getUUID(), now);
		}
		player.sendOverlayMessage(
			Component.literal("Limit reached for " + ruleName + ": " + used + "/" + max)
				.withStyle(ChatFormatting.RED)
		);
	}

	void flushDirtyAsync() {
		if (repository == null) {
			return;
		}
		List<LimitRepository.PersistedIslandRecord> dirtyBatch = collectDirtyRecords();
		if (!dirtyBatch.isEmpty()) {
			repository.saveBatchAsync(dirtyBatch);
		}
	}

	void flushDirtyBlocking() {
		if (repository == null) {
			return;
		}
		flushDirtyAsync();
		try {
			repository.flush().get(5, TimeUnit.SECONDS);
		} catch (Exception ex) {
			OgskyCore.LOGGER.warn("Timed out flushing limits.db on shutdown: {}", ex.toString());
		}
	}

	private List<LimitRepository.PersistedIslandRecord> collectDirtyRecords() {
		LimitRuleTable table = this.ruleTable;
		List<LimitRepository.PersistedIslandRecord> batch = new ArrayList<>();
		for (IslandLimitState state : islandStates.values()) {
			if (!state.dirty) {
				continue;
			}
			batch.add(toRecord(state, table));
			state.dirty = false;
		}
		return batch;
	}

	private static LimitRepository.PersistedIslandRecord toRecord(IslandLimitState state, LimitRuleTable table) {
		List<LimitRepository.PersistedCount> counts = new ArrayList<>();
		for (LimitRuleTable.LimitRule rule : table.blockRules()) {
			int value = rule.index() < state.blockCounts.length ? state.blockCounts[rule.index()] : 0;
			if (value > 0) {
				counts.add(new LimitRepository.PersistedCount("BLOCK", rule.key(), value));
			}
		}
		for (LimitRuleTable.LimitRule rule : table.entityRules()) {
			int value = rule.index() < state.entityCounts.length ? state.entityCounts[rule.index()] : 0;
			if (value > 0) {
				counts.add(new LimitRepository.PersistedCount("ENTITY", rule.key(), value));
			}
		}
		return new LimitRepository.PersistedIslandRecord(
			state.islandId,
			state.syncedRulesHash,
			state.lastSyncedAtMillis,
			counts
		);
	}
}
