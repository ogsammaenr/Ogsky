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
		this.registry.reload();
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

	public boolean enqueueOverworldPaste(
		MinecraftServer server,
		Island island,
		String bundleId,
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
		Path overworldNbt = bundle.overworldFile();
		if (overworldNbt == null) {
			return false;
		}
		try {
			StructureTemplate template = this.registry.loadTemplate(overworld.holderLookup(Registries.BLOCK), overworldNbt);
			BlockPos origin = bundle.computePasteOrigin(
				island,
				OgskyDimensions.OVERWORLD,
				template.getSize(),
				this.config.island.defaultSpawnY,
				overworld.getMinY(),
				overworld.getMaxY()
			);
			RelativeSpawn computedSpawn = bundle.computeSpawn(island, OgskyDimensions.OVERWORLD, this.config.island.defaultSpawnY);
			island.setOverworldSpawn(computedSpawn);
			this.paster.enqueuePaste(island, OgskyDimensions.OVERWORLD, overworld, template, origin, bundle.id(), onComplete);
			return true;
		} catch (IOException ex) {
			OgskyCore.LOGGER.error("Failed to load overworld blueprint '{}' from {}", bundle.id(), overworldNbt, ex);
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
			Path nbtFile = bundle.fileFor(targetDimension);
			if (nbtFile != null) {
				try {
					StructureTemplate template = this.registry.loadTemplate(targetLevel.holderLookup(Registries.BLOCK), nbtFile);
					int baseSpawnY = Math.clamp(this.config.island.defaultSpawnY, targetLevel.getMinY() + 2, targetLevel.getMaxY() - 10);
					BlockPos origin = bundle.computePasteOrigin(
						island,
						targetDimension,
						template.getSize(),
						baseSpawnY,
						targetLevel.getMinY(),
						targetLevel.getMaxY()
					);
					RelativeSpawn spawn = bundle.computeSpawn(island, targetDimension, baseSpawnY);
					if (targetDimension.equals(OgskyDimensions.NETHER)) {
						island.setNetherSpawn(spawn);
					} else {
						island.setEndSpawn(spawn);
					}
					this.paster.pasteImmediately(island, targetDimension, targetLevel, template, origin, bundle.id());
					usedBundleId = bundle.id();
				} catch (IOException ex) {
					OgskyCore.LOGGER.error(
						"Failed to load lazy blueprint '{}' ({}) from {}",
						bundle.id(),
						targetDimension.identifier(),
						nbtFile,
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
}
