package me.ogsammaenr.ogsky.core.internal.island;

import me.ogsammaenr.ogsky.core.api.db.DatabaseService;
import me.ogsammaenr.ogsky.core.api.event.IslandCreatedEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandDeletedEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandLockChangedEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandPreCreateEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandPreDeleteEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandProtectionFlagChangedEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandRangeChangeEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandResetEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandSettingFlagChangedEvent;
import me.ogsammaenr.ogsky.core.api.event.OgskyEventBus;
import me.ogsammaenr.ogsky.core.api.flag.FlagRegistry;
import me.ogsammaenr.ogsky.core.api.flag.FlagType;
import me.ogsammaenr.ogsky.core.api.flag.IslandFlagDefinition;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.api.island.RelativeSpawn;
import me.ogsammaenr.ogsky.core.internal.blueprint.BlueprintBundle;
import me.ogsammaenr.ogsky.core.internal.blueprint.CoreBlueprintService;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfig;
import me.ogsammaenr.ogsky.core.internal.flag.BuiltinFlags;
import me.ogsammaenr.ogsky.core.internal.world.OgskyDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Handles Skyblock island lifecycle transitions (create, reset, delete, home/sethome, resize, and flags),
 * enforcing cancellable pre-events, async SQLite persistence, and tick-budgeted blueprint/chunk workflows.
 */
public final class IslandLifecycleService {
	private final CoreConfig config;
	private final CoreIslandService islandService;
	private final CoreBlueprintService blueprintService;
	private final DatabaseService databaseService;
	private final FlagRegistry flagRegistry;
	private final OgskyEventBus eventBus;

	public IslandLifecycleService(
		CoreConfig config,
		CoreIslandService islandService,
		CoreBlueprintService blueprintService,
		DatabaseService databaseService,
		FlagRegistry flagRegistry,
		OgskyEventBus eventBus
	) {
		this.config = Objects.requireNonNull(config, "config");
		this.islandService = Objects.requireNonNull(islandService, "islandService");
		this.blueprintService = Objects.requireNonNull(blueprintService, "blueprintService");
		this.databaseService = Objects.requireNonNull(databaseService, "databaseService");
		this.flagRegistry = Objects.requireNonNull(flagRegistry, "flagRegistry");
		this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
	}

	public OperationResult<Island> createIsland(
		@Nullable MinecraftServer server,
		@Nullable ServerPlayer actor,
		UUID ownerId,
		@Nullable String requestedBundleId
	) {
		Objects.requireNonNull(ownerId, "ownerId");
		IslandGrid grid = this.islandService.grid();
		if (grid.findPrimaryIsland(ownerId).isPresent()) {
			return OperationResult.fail("already_has_island", "You already belong to a primary Skyblock island.");
		}

		Optional<BlueprintBundle> resolvedBundle = resolveUsableOverworldBundle(requestedBundleId);
		if (resolvedBundle.isEmpty()) {
			return missingBlueprintResult(requestedBundleId);
		}
		BlueprintBundle bundle = resolvedBundle.get();

		if (!this.eventBus.publishCancellable(new IslandPreCreateEvent(ownerId, bundle.id()))) {
			return OperationResult.fail("cancelled", "Island creation was cancelled.");
		}

		if (!this.islandService.isCellSizeLocked()) {
			this.islandService.markCellSizeLocked();
			this.databaseService.find(CoreIslandRepository.MODULE_ID)
				.ifPresent(db -> CoreIslandRepository.lockCellSizeAsync(db, grid.cellSize()));
		}

		long cell = grid.allocateNextCell();
		int gridX = PackedGridMap.unpackX(cell);
		int gridZ = PackedGridMap.unpackZ(cell);
		int centerX = grid.centerCoordinate(gridX);
		int centerZ = grid.centerCoordinate(gridZ);
		Island island = new Island(
			UUID.randomUUID(),
			ownerId,
			gridX,
			gridZ,
			centerX,
			centerZ,
			(short) this.config.island.initialHalfSize,
			bundle.id(),
			Instant.now(),
			this.flagRegistry,
			this.config
		);
		grid.register(island);

		if (server != null) {
			UUID actorUuid = actor != null ? actor.getUUID() : ownerId;
			boolean queued = this.blueprintService.enqueueOverworldPaste(server, island, bundle.id(), () -> {
				ServerPlayer target = server.getPlayerList().getPlayer(actorUuid);
				if (target != null && !target.hasDisconnected()) {
					teleportHome(server, target, island, OgskyDimensions.OVERWORLD);
				}
			});
			if (!queued) {
				grid.unregister(island.islandId());
				return OperationResult.fail("blueprint_load_failed", "Failed to paste overworld blueprint '" + bundle.id() + "'.");
			}
		} else {
			island.setStateBit(Island.STATE_OVERWORLD_READY, true);
		}

		persistIslandAndOwner(island);
		this.eventBus.publish(new IslandCreatedEvent(island.islandId(), ownerId));
		return OperationResult.ok("created", "Island created with blueprint '" + bundle.id() + "'.", island);
	}

	public OperationResult<Island> resetIsland(
		@Nullable MinecraftServer server,
		UUID actorId,
		UUID islandId,
		@Nullable String requestedBundleId,
		boolean bypassOwnership
	) {
		Objects.requireNonNull(actorId, "actorId");
		Objects.requireNonNull(islandId, "islandId");

		Optional<Island> maybeIsland = this.islandService.grid().findById(islandId);
		if (maybeIsland.isEmpty()) {
			return OperationResult.fail("no_island", "Island does not exist.");
		}
		Island island = maybeIsland.get();
		if (!bypassOwnership && !island.ownerId().equals(actorId)) {
			return OperationResult.fail("not_owner", "Only the island owner can reset this island.");
		}
		if (island.isPasting() || island.isDeleting()) {
			return OperationResult.fail("busy", "Island is already processing a paste or cleanup operation.");
		}

		Optional<BlueprintBundle> resolvedBundle = resolveUsableOverworldBundle(requestedBundleId);
		if (resolvedBundle.isEmpty()) {
			return missingBlueprintResult(requestedBundleId);
		}
		BlueprintBundle bundle = resolvedBundle.get();

		if (!this.eventBus.publishCancellable(new IslandResetEvent(island.islandId(), island.ownerId(), bundle.id()))) {
			return OperationResult.fail("cancelled", "Island reset was cancelled.");
		}

		island.setBundleId(bundle.id());
		island.setStateBit(Island.STATE_OVERWORLD_READY, false);
		island.setStateBit(Island.STATE_NETHER_READY, false);
		island.setStateBit(Island.STATE_END_READY, false);
		island.setStateBit(Island.STATE_PASTING, true);
		persistIsland(island);

		if (server != null) {
			evictPlayersInside(server, island, player -> true);
			this.blueprintService.cleanupQueue().enqueue(
				island.gridX(),
				island.gridZ(),
				island.minX(),
				island.maxX(),
				island.minZ(),
				island.maxZ(),
				() -> {
					Optional<Island> stillPresent = this.islandService.grid().findById(island.islandId());
					if (stillPresent.isEmpty() || stillPresent.get().isDeleting()) {
						return;
					}
					Island targetIsland = stillPresent.get();
					boolean started = this.blueprintService.enqueueOverworldPaste(server, targetIsland, bundle.id(), () -> {
						ServerPlayer onlineActor = server.getPlayerList().getPlayer(actorId);
						if (onlineActor != null && !onlineActor.hasDisconnected()) {
							teleportHome(server, onlineActor, targetIsland, OgskyDimensions.OVERWORLD);
						}
					});
					if (!started) {
						targetIsland.setStateBit(Island.STATE_PASTING, false);
						persistIsland(targetIsland);
					}
				}
			);
		} else {
			island.setStateBit(Island.STATE_PASTING, false);
			island.setStateBit(Island.STATE_OVERWORLD_READY, true);
			persistIsland(island);
		}

		return OperationResult.ok("reset", "Island reset scheduled with blueprint '" + bundle.id() + "'.", island);
	}

	public OperationResult<Void> deleteIsland(
		@Nullable MinecraftServer server,
		UUID islandId,
		@Nullable UUID actorId,
		IslandPreDeleteEvent.DeleteReason reason
	) {
		Objects.requireNonNull(islandId, "islandId");
		Objects.requireNonNull(reason, "reason");

		IslandGrid grid = this.islandService.grid();
		Optional<Island> maybeIsland = grid.findById(islandId);
		if (maybeIsland.isEmpty()) {
			return OperationResult.fail("no_island", "Island does not exist.");
		}
		Island island = maybeIsland.get();

		if (!this.eventBus.publishCancellable(new IslandPreDeleteEvent(islandId, actorId, reason))) {
			return OperationResult.fail("cancelled", "Island deletion was cancelled.");
		}

		island.setStateBit(Island.STATE_DELETING, true);
		if (server != null) {
			evictPlayersInside(server, island, player -> true);
			this.blueprintService.cleanupQueue().enqueue(
				island.gridX(),
				island.gridZ(),
				island.minX(),
				island.maxX(),
				island.minZ(),
				island.maxZ()
			);
		}
		grid.unregister(islandId);
		this.databaseService.find(CoreIslandRepository.MODULE_ID)
			.ifPresent(db -> CoreIslandRepository.deleteIslandAsync(db, islandId));
		this.eventBus.publish(new IslandDeletedEvent(islandId));
		return OperationResult.ok("deleted", "Island deleted and scheduled for chunk cleanup.");
	}

	public OperationResult<Void> teleportHome(
		MinecraftServer server,
		ServerPlayer player,
		Island island,
		@Nullable ResourceKey<Level> targetDimension
	) {
		Objects.requireNonNull(server, "server");
		Objects.requireNonNull(player, "player");
		Objects.requireNonNull(island, "island");
		if (island.isPasting() || island.isDeleting()) {
			return OperationResult.fail("busy", "Island is still being prepared.");
		}
		ResourceKey<Level> dimension = targetDimension == null ? OgskyDimensions.OVERWORLD : targetDimension;
		if (!OgskyDimensions.isSkyblockDimension(dimension)) {
			return OperationResult.fail("invalid_dimension", "Target must be an ogsky dimension.");
		}
		ServerLevel level = server.getLevel(dimension);
		if (level == null) {
			return OperationResult.fail("missing_dimension", "Skyblock dimension is not loaded: " + dimension.identifier());
		}

		RelativeSpawn spawn = spawnFor(island, dimension);
		if (dimension.equals(OgskyDimensions.NETHER) && !island.isNetherReady()) {
			this.blueprintService.prepareDimension(server, island, dimension, level, spawnToBlockPos(island, spawn));
			spawn = island.netherSpawn();
		} else if (dimension.equals(OgskyDimensions.END) && !island.isEndReady()) {
			this.blueprintService.prepareDimension(server, island, dimension, level, spawnToBlockPos(island, spawn));
			spawn = island.endSpawn();
		}

		double targetX = spawn.worldX(island.centerX());
		double targetY = findSafeLandingY(level, (int) Math.floor(targetX), spawn.blockY(), (int) Math.floor(spawn.worldZ(island.centerZ())), spawn.worldY());
		double targetZ = spawn.worldZ(island.centerZ());
		player.teleport(new TeleportTransition(
			level,
			new Vec3(targetX, targetY, targetZ),
			Vec3.ZERO,
			spawn.yaw(),
			spawn.pitch(),
			TeleportTransition.DO_NOTHING
		));
		return OperationResult.ok("teleported", "Teleported to island home.");
	}

	public OperationResult<Void> setHome(ServerPlayer player, Island island, boolean bypassRole) {
		Objects.requireNonNull(player, "player");
		Objects.requireNonNull(island, "island");
		if (!bypassRole && island.roleOf(player.getUUID()) < IslandRole.ADMIN) {
			return OperationResult.fail("insufficient_rank", "Only island admins or the owner can move island home.");
		}
		ResourceKey<Level> dim = player.level().dimension();
		if (!OgskyDimensions.isSkyblockDimension(dim)) {
			return OperationResult.fail("wrong_dimension", "You must be inside a Skyblock dimension to set home.");
		}
		BlockPos pos = player.blockPosition();
		if (!island.contains(pos.getX(), pos.getZ())) {
			return OperationResult.fail("outside_island", "Home position must be inside your island bounds.");
		}
		RelativeSpawn relative = RelativeSpawn.fromAbsolute(
			island.centerX(),
			island.centerZ(),
			player.getX(),
			player.getY(),
			player.getZ(),
			player.getYRot(),
			player.getXRot()
		);
		if (dim.equals(OgskyDimensions.OVERWORLD)) {
			island.setOverworldSpawn(relative);
		} else if (dim.equals(OgskyDimensions.NETHER)) {
			island.setNetherSpawn(relative);
		} else {
			island.setEndSpawn(relative);
		}
		persistIsland(island);
		return OperationResult.ok("home_updated", "Island home updated for " + OgskyDimensions.dimensionName(dim) + ".");
	}

	public OperationResult<Short> resizeIsland(UUID islandId, int newHalfSize) {
		Objects.requireNonNull(islandId, "islandId");
		Optional<Island> maybeIsland = this.islandService.grid().findById(islandId);
		if (maybeIsland.isEmpty()) {
			return OperationResult.fail("no_island", "Island does not exist.");
		}
		int maxAllowed = Math.min(this.config.island.maxHalfSize, (this.islandService.grid().cellSize() / 2) - 16);
		if (newHalfSize < 1 || newHalfSize > maxAllowed) {
			return OperationResult.fail(
				"invalid_range",
				"Half-size must be between 1 and " + maxAllowed + "."
			);
		}
		Island island = maybeIsland.get();
		short oldHalfSize = island.halfSize();
		short clamped = (short) newHalfSize;
		if (oldHalfSize == clamped) {
			return OperationResult.ok("unchanged", "Island half-size is already " + clamped + ".", clamped);
		}
		island.setHalfSize(clamped);
		persistIsland(island);
		this.eventBus.publish(new IslandRangeChangeEvent(islandId, oldHalfSize, clamped));
		return OperationResult.ok("resized", "Island border half-size updated to " + clamped + ".", clamped);
	}

	public OperationResult<Boolean> setLock(
		@Nullable MinecraftServer server,
		@Nullable UUID actorId,
		Island island,
		boolean locked,
		boolean bypassRole
	) {
		return setSettingFlag(server, actorId, island, BuiltinFlags.ISLAND_LOCK, locked, bypassRole);
	}

	public OperationResult<Byte> setProtectionRank(
		@Nullable UUID actorId,
		Island island,
		Identifier flagId,
		byte newMinRank,
		boolean bypassRole
	) {
		Objects.requireNonNull(island, "island");
		Objects.requireNonNull(flagId, "flagId");
		if (!bypassRole && (actorId == null || island.roleOf(actorId) < IslandRole.ADMIN)) {
			return OperationResult.fail("insufficient_rank", "Only island admins or the owner can change protection flags.");
		}
		Optional<IslandFlagDefinition> def = this.flagRegistry.find(flagId);
		if (def.isEmpty() || def.get().type() != FlagType.PROTECTION) {
			return OperationResult.fail("unknown_flag", "Unknown PROTECTION flag: " + flagId);
		}
		if (!IslandRole.isValidThreshold(newMinRank)) {
			return OperationResult.fail("invalid_rank", "Invalid protection rank threshold: " + newMinRank);
		}
		int slot = this.flagRegistry.slotOf(flagId);
		byte oldRank = island.protectionRankBySlot(slot);
		if (oldRank == newMinRank) {
			return OperationResult.ok("unchanged", "Protection flag " + flagId + " already set.", newMinRank);
		}
		island.setProtectionRankBySlot(slot, newMinRank);
		this.databaseService.find(CoreIslandRepository.MODULE_ID)
			.ifPresent(db -> CoreIslandRepository.saveProtectionFlagAsync(db, island.islandId(), flagId, newMinRank));
		this.eventBus.publish(new IslandProtectionFlagChangedEvent(island.islandId(), actorId, flagId, oldRank, newMinRank));
		return OperationResult.ok("updated", "Updated protection flag " + flagId + ".", newMinRank);
	}

	public OperationResult<Boolean> setSettingFlag(
		@Nullable MinecraftServer server,
		@Nullable UUID actorId,
		Island island,
		Identifier flagId,
		boolean enabled,
		boolean bypassRole
	) {
		Objects.requireNonNull(island, "island");
		Objects.requireNonNull(flagId, "flagId");
		if (!bypassRole && (actorId == null || island.roleOf(actorId) < IslandRole.ADMIN)) {
			return OperationResult.fail("insufficient_rank", "Only island admins or the owner can change island settings.");
		}
		Optional<IslandFlagDefinition> def = this.flagRegistry.find(flagId);
		if (def.isEmpty() || def.get().type() != FlagType.SETTING) {
			return OperationResult.fail("unknown_flag", "Unknown SETTING flag: " + flagId);
		}
		int slot = this.flagRegistry.slotOf(flagId);
		boolean oldValue = island.isSettingEnabledBySlot(slot);
		if (oldValue == enabled) {
			return OperationResult.ok("unchanged", "Setting flag " + flagId + " already set.", enabled);
		}
		island.setSettingEnabledBySlot(slot, enabled);
		this.databaseService.find(CoreIslandRepository.MODULE_ID)
			.ifPresent(db -> CoreIslandRepository.saveSettingFlagAsync(db, island.islandId(), flagId, enabled));
		this.eventBus.publish(new IslandSettingFlagChangedEvent(island.islandId(), actorId, flagId, oldValue, enabled));
		if (flagId.equals(BuiltinFlags.ISLAND_LOCK)) {
			this.eventBus.publish(new IslandLockChangedEvent(island.islandId(), actorId, enabled));
			if (enabled && server != null) {
				evictPlayersInside(server, island, player -> island.roleOf(player.getUUID()) == IslandRole.VISITOR);
			}
		}
		return OperationResult.ok("updated", "Updated setting flag " + flagId + ".", enabled);
	}

	public OperationResult<Boolean> setWorldSetting(Identifier flagId, boolean enabled) {
		Objects.requireNonNull(flagId, "flagId");
		Optional<IslandFlagDefinition> def = this.flagRegistry.find(flagId);
		if (def.isEmpty() || def.get().type() != FlagType.WORLD_SETTING) {
			return OperationResult.fail("unknown_flag", "Unknown WORLD_SETTING flag: " + flagId);
		}
		this.islandService.applyWorldSetting(flagId, enabled);
		return OperationResult.ok("updated", "Updated world setting " + flagId + " to " + enabled + ".", enabled);
	}

	public static int evictPlayersInside(
		MinecraftServer server,
		Island island,
		Predicate<ServerPlayer> shouldEvict
	) {
		int evicted = 0;
		for (ResourceKey<Level> dimension : OgskyDimensions.ALL) {
			ServerLevel level = server.getLevel(dimension);
			if (level == null) {
				continue;
			}
			for (ServerPlayer player : List.copyOf(level.players())) {
				BlockPos pos = player.blockPosition();
				if (island.contains(pos.getX(), pos.getZ()) && shouldEvict.test(player)) {
					player.teleport(TeleportTransition.createDefault(player, TeleportTransition.DO_NOTHING));
					evicted++;
				}
			}
		}
		return evicted;
	}

	private Optional<BlueprintBundle> resolveUsableOverworldBundle(@Nullable String requestedBundleId) {
		BlueprintBundle candidate;
		if (requestedBundleId != null && !requestedBundleId.isBlank()) {
			candidate = this.blueprintService.registry().find(requestedBundleId).orElse(null);
		} else {
			candidate = this.blueprintService.registry().find(this.config.blueprints.defaultBundle).orElse(null);
		}
		if (candidate == null || !candidate.hasOverworld()) {
			return Optional.empty();
		}
		return Optional.of(candidate);
	}

	private OperationResult<Island> missingBlueprintResult(@Nullable String requestedBundleId) {
		String targetId = (requestedBundleId == null || requestedBundleId.isBlank())
			? this.config.blueprints.defaultBundle
			: requestedBundleId;
		if (this.blueprintService.registry().find(targetId).isEmpty() && targetId.equals(this.config.blueprints.defaultBundle)) {
			return OperationResult.fail(
				"missing_blueprint",
				"Starter blueprint '" + targetId + "' has no overworld.nbt under "
					+ this.blueprintService.registry().blueprintsDir().resolve(targetId)
					+ ". Add your .nbt files and run /isadmin reload."
			);
		}
		return OperationResult.fail("missing_blueprint", "Blueprint bundle '" + targetId + "' was not found or is missing overworld.nbt.");
	}

	private void persistIslandAndOwner(Island island) {
		this.databaseService.find(CoreIslandRepository.MODULE_ID).ifPresent(db -> {
			CoreIslandRepository.upsertIslandAsync(db, island);
			CoreIslandRepository.upsertMemberAsync(db, island.islandId(), island.ownerId(), IslandRole.OWNER);
		});
	}

	private void persistIsland(Island island) {
		this.databaseService.find(CoreIslandRepository.MODULE_ID)
			.ifPresent(db -> CoreIslandRepository.upsertIslandAsync(db, island));
	}

	private static RelativeSpawn spawnFor(Island island, ResourceKey<Level> dimension) {
		if (dimension.equals(OgskyDimensions.NETHER)) {
			return island.netherSpawn();
		}
		if (dimension.equals(OgskyDimensions.END)) {
			return island.endSpawn();
		}
		return island.overworldSpawn();
	}

	private static BlockPos spawnToBlockPos(Island island, RelativeSpawn spawn) {
		return new BlockPos(
			spawn.blockX(island.centerX()),
			spawn.blockY(),
			spawn.blockZ(island.centerZ())
		);
	}

	private static double findSafeLandingY(ServerLevel level, int x, int baseY, int z, double exactY) {
		BlockPos feetPos = new BlockPos(x, baseY, z);
		BlockPos belowPos = feetPos.below();
		if (!level.getBlockState(belowPos).isAir() && level.getBlockState(feetPos).isAir() && level.getBlockState(feetPos.above()).isAir()) {
			return exactY;
		}
		for (int offset = 0; offset <= 16; offset++) {
			int probeUp = baseY + offset;
			if (probeUp < level.getMaxY() - 1) {
				BlockPos candidate = new BlockPos(x, probeUp, z);
				if (!level.getBlockState(candidate.below()).isAir()
					&& level.getBlockState(candidate).isAir()
					&& level.getBlockState(candidate.above()).isAir()) {
					return probeUp;
				}
			}
			int probeDown = baseY - offset;
			if (probeDown > level.getMinY() + 1) {
				BlockPos candidate = new BlockPos(x, probeDown, z);
				if (!level.getBlockState(candidate.below()).isAir()
					&& level.getBlockState(candidate).isAir()
					&& level.getBlockState(candidate.above()).isAir()) {
					return probeDown;
				}
			}
		}
		return exactY;
	}
}
