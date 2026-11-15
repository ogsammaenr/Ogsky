package me.ogsammaenr.ogsky.core.internal.blueprint;

import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.db.DatabaseService;
import me.ogsammaenr.ogsky.core.api.event.IslandDimensionInitEvent;
import me.ogsammaenr.ogsky.core.api.event.OgskyEventBus;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import me.ogsammaenr.ogsky.core.api.island.RelativeSpawn;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfig;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandRepository;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandService;
import me.ogsammaenr.ogsky.core.internal.island.Island;
import me.ogsammaenr.ogsky.core.internal.world.OgskyDimensions;
import me.ogsammaenr.ogsky.core.internal.world.PortalRouter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Coordinates blueprint bundle loading, tick-budgeted {@code .nbt} placement, lazy Nether/End initialization,
 * and tick-budgeted chunk cleanup across {@code ogsky:*}.
 */
public final class CoreBlueprintService implements PortalRouter.LazyDimensionHandler {
	private final CoreConfig config;
	private final CoreIslandService islandService;
	private final DatabaseService databaseService;
	private final OgskyEventBus eventBus;
	private final BlueprintBundleRegistry registry;
	private final BlueprintPaster paster;
	private volatile @Nullable ChunkCleanupQueue cleanupQueue;

	public CoreBlueprintService(
		CoreConfig config,
		Path blueprintsDir,
		CoreIslandService islandService,
		DatabaseService databaseService,
		OgskyEventBus eventBus
	) {
		this.config = Objects.requireNonNull(config, "config");
		this.islandService = Objects.requireNonNull(islandService, "islandService");
		this.databaseService = Objects.requireNonNull(databaseService, "databaseService");
		this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
		this.registry = new BlueprintBundleRegistry(blueprintsDir, config.blueprints.defaultBundle);
		this.paster = new BlueprintPaster(config, islandService, databaseService, eventBus);
	}

	public BlueprintBundleRegistry registry() {
		return this.registry;
	}

	public BlueprintPaster paster() {
		return this.paster;
	}

	public ChunkCleanupQueue cleanupQueue() {
		ChunkCleanupQueue queue = this.cleanupQueue;
		if (queue == null) {
			queue = new ChunkCleanupQueue(this.config, this.islandService.grid(), this.databaseService);
			this.cleanupQueue = queue;
		}
		return queue;
	}

	public void onServerStarting() {
		onServerStarting(null);
	}

	public void onServerStarting(@Nullable MinecraftServer server) {
		this.registry.reload(server != null ? server.getFixerUpper() : null);
		this.paster.clear();
		this.cleanupQueue = new ChunkCleanupQueue(this.config, this.islandService.grid(), this.databaseService);
	}

	public void onServerStopping() {
		this.paster.clear();
		ChunkCleanupQueue queue = this.cleanupQueue;
		if (queue != null) {
			queue.flushProgress();
		}
	}

	public void tick(MinecraftServer server) {
		this.paster.tick(server);
		cleanupQueue().tick(server);
	}

	public void applyInitialOverworldSpawn(Island island, BlueprintBundle bundle, int minLevelY, int maxLevelY) {
		Objects.requireNonNull(island, "island");
		Objects.requireNonNull(bundle, "bundle");
		CompressedBlueprint overworldBp = bundle.overworld();
		if (overworldBp == null) {
			return;
		}
		BlockPos origin = bundle.computePasteOrigin(
			island,
			OgskyDimensions.OVERWORLD,
			overworldBp,
			this.config.island.defaultSpawnY,
			minLevelY,
			maxLevelY
		);
		RelativeSpawn spawn = bundle.computeSpawn(
			island,
			OgskyDimensions.OVERWORLD,
			overworldBp,
			origin,
			this.config.island.defaultSpawnY
		);
		island.setOverworldSpawn(spawn);
	}

	public boolean enqueueOverworldPaste(
		MinecraftServer server,
		Island island,
		String bundleId,
		@Nullable Runnable onComplete
	) {
		return enqueueOverworldPaste(server, island, bundleId, null, onComplete);
	}

	public boolean enqueueOverworldPaste(
		MinecraftServer server,
		Island island,
		String bundleId,
		@Nullable String ownerNameHint,
		@Nullable Runnable onComplete
	) {
		ServerLevel overworld = server.getLevel(OgskyDimensions.OVERWORLD);
		if (overworld == null) {
			return false;
		}
		Optional<BlueprintBundle> resolved = this.registry.resolveWithFallback(bundleId);
		if (resolved.isEmpty()) {
			return false;
		}
		BlueprintBundle bundle = resolved.get();
		CompressedBlueprint overworldBp = bundle.overworld();
		if (overworldBp == null) {
			return false;
		}
		try {
			StructureTemplate template = this.registry.decompressTemplate(overworld.holderLookup(Registries.BLOCK), overworldBp);
			BlockPos origin = bundle.computePasteOrigin(
				island,
				OgskyDimensions.OVERWORLD,
				overworldBp,
				this.config.island.defaultSpawnY,
				overworld.getMinY(),
				overworld.getMaxY()
			);
			RelativeSpawn computedSpawn = bundle.computeSpawn(
				island,
				OgskyDimensions.OVERWORLD,
				overworldBp,
				origin,
				this.config.island.defaultSpawnY
			);
			island.setOverworldSpawn(computedSpawn);
			BlueprintSignSupport.WelcomeContext welcomeContext = buildWelcomeContext(server, island, bundle, ownerNameHint);
			this.paster.enqueuePaste(island, OgskyDimensions.OVERWORLD, overworld, template, origin, bundle.id(), welcomeContext, onComplete);
			return true;
		} catch (IOException ex) {
			OgskyCore.LOGGER.error("Failed to decompress overworld blueprint '{}' from RAM", bundle.id(), ex);
			return false;
		}
	}

	@Override
	public void prepareDimension(
		MinecraftServer server,
		IslandView islandView,
		ResourceKey<Level> targetDimension,
		ServerLevel targetLevel,
		BlockPos targetOrigin
	) {
		if (!targetDimension.equals(OgskyDimensions.NETHER) && !targetDimension.equals(OgskyDimensions.END)) {
			return;
		}
		Optional<Island> maybeIsland = this.islandService.grid().findById(islandView.islandId());
		if (maybeIsland.isEmpty()) {
			return;
		}
		Island island = maybeIsland.get();
		if (targetDimension.equals(OgskyDimensions.NETHER) && island.isNetherReady()) {
			return;
		}
		if (targetDimension.equals(OgskyDimensions.END) && island.isEndReady()) {
			return;
		}

		String usedBundleId = island.bundleId();
		Optional<BlueprintBundle> maybeBundle = this.registry.resolveWithFallback(island.bundleId());
		if (maybeBundle.isPresent()) {
			BlueprintBundle bundle = maybeBundle.get();
			CompressedBlueprint compressed = bundle.blueprintFor(targetDimension);
			if (compressed != null) {
				try {
					StructureTemplate template = this.registry.decompressTemplate(targetLevel.holderLookup(Registries.BLOCK), compressed);
					int baseSpawnY = Math.clamp(this.config.island.defaultSpawnY, targetLevel.getMinY() + 2, targetLevel.getMaxY() - 10);
					BlockPos origin = bundle.computePasteOrigin(
						island,
						targetDimension,
						compressed,
						baseSpawnY,
						targetLevel.getMinY(),
						targetLevel.getMaxY()
					);
					RelativeSpawn spawn = bundle.computeSpawn(island, targetDimension, compressed, origin, baseSpawnY);
					if (targetDimension.equals(OgskyDimensions.NETHER)) {
						island.setNetherSpawn(spawn);
					} else {
						island.setEndSpawn(spawn);
					}
					BlueprintSignSupport.WelcomeContext welcomeContext = buildWelcomeContext(server, island, bundle, null);
					this.paster.pasteImmediately(island, targetDimension, targetLevel, template, origin, bundle.id(), welcomeContext);
					usedBundleId = bundle.id();
				} catch (IOException ex) {
					OgskyCore.LOGGER.error(
						"Failed to decompress lazy blueprint '{}' ({}) from RAM",
						bundle.id(),
						targetDimension.identifier(),
						ex
					);
				}
			}
		}

		if (targetDimension.equals(OgskyDimensions.NETHER)) {
			island.setStateBit(Island.STATE_NETHER_READY, true);
		} else {
			island.setStateBit(Island.STATE_END_READY, true);
		}

		this.databaseService
			.find(CoreIslandRepository.MODULE_ID)
			.ifPresent(db -> CoreIslandRepository.upsertIslandAsync(db, island));
		this.eventBus.publish(new IslandDimensionInitEvent(
			island.islandId(),
			targetDimension.identifier(),
			usedBundleId
		));
	}

	private static BlueprintSignSupport.WelcomeContext buildWelcomeContext(
		MinecraftServer server,
		Island island,
		BlueprintBundle bundle,
		@Nullable String ownerNameHint
	) {
		String ownerName = resolveOwnerName(server, island.ownerId(), ownerNameHint);
		return new BlueprintSignSupport.WelcomeContext(
			ownerName,
			bundle.id(),
			bundle.metadata().displayName,
			island.islandId(),
			island.gridX(),
			island.gridZ()
		);
	}

	private static String resolveOwnerName(MinecraftServer server, java.util.UUID ownerId, @Nullable String ownerNameHint) {
		ServerPlayer online = server.getPlayerList().getPlayer(ownerId);
		if (online != null) {
			return online.getScoreboardName();
		}
		Optional<String> cached = server.services().nameToIdCache().get(ownerId).map(NameAndId::name);
		if (cached.isPresent() && !cached.get().isBlank()) {
			return cached.get();
		}
		if (ownerNameHint != null && !ownerNameHint.isBlank()) {
			return ownerNameHint;
		}
		return ownerId.toString().substring(0, 8);
	}
}
