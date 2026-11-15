package me.ogsammaenr.ogsky.core.internal.blueprint;

import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.db.DatabaseService;
import me.ogsammaenr.ogsky.core.api.event.BlueprintPasteCompletedEvent;
import me.ogsammaenr.ogsky.core.api.event.OgskyEventBus;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfig;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandRepository;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandService;
import me.ogsammaenr.ogsky.core.internal.island.Island;
import me.ogsammaenr.ogsky.core.mixin.StructureTemplateAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.TagValueInput;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Tick-budgeted {@link StructureTemplate} paster that places {@code .nbt} blueprints over multiple server ticks
 * while bounding blocks and entities strictly inside the owning island's protection box.
 */
public final class BlueprintPaster {
	public static final int PASTE_BLOCK_FLAGS =
		Block.UPDATE_CLIENTS
			| Block.UPDATE_KNOWN_SHAPE
			| Block.UPDATE_SUPPRESS_DROPS
			| Block.UPDATE_SKIP_ON_PLACE;

	private final CoreConfig config;
	private final CoreIslandService islandService;
	private final DatabaseService databaseService;
	private final OgskyEventBus eventBus;
	private final Map<PasteJobKey, PasteJob> activeJobs = new LinkedHashMap<>();

	public BlueprintPaster(
		CoreConfig config,
		CoreIslandService islandService,
		DatabaseService databaseService,
		OgskyEventBus eventBus
	) {
		this.config = Objects.requireNonNull(config, "config");
		this.islandService = Objects.requireNonNull(islandService, "islandService");
		this.databaseService = Objects.requireNonNull(databaseService, "databaseService");
		this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
	}

	public int activeJobCount() {
		return this.activeJobs.size();
	}

	public boolean isPasting(UUID islandId) {
		for (PasteJobKey key : this.activeJobs.keySet()) {
			if (key.islandId().equals(islandId)) {
				return true;
			}
		}
		return false;
	}

	public void cancelForIsland(UUID islandId) {
		Iterator<PasteJobKey> it = this.activeJobs.keySet().iterator();
		while (it.hasNext()) {
			if (it.next().islandId().equals(islandId)) {
				it.remove();
			}
		}
	}

	public void clear() {
		this.activeJobs.clear();
	}

	public void enqueuePaste(
		Island island,
		ResourceKey<Level> dimension,
		ServerLevel level,
		StructureTemplate template,
		BlockPos origin,
		String bundleId,
		@Nullable Runnable onComplete
	) {
		enqueuePaste(island, dimension, level, template, origin, bundleId, null, onComplete);
	}

	public void enqueuePaste(
		Island island,
		ResourceKey<Level> dimension,
		ServerLevel level,
		StructureTemplate template,
		BlockPos origin,
		String bundleId,
		BlueprintSignSupport.@Nullable WelcomeContext welcomeContext,
		@Nullable Runnable onComplete
	) {
		Objects.requireNonNull(island, "island");
		Objects.requireNonNull(dimension, "dimension");
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(template, "template");
		Objects.requireNonNull(origin, "origin");
		Objects.requireNonNull(bundleId, "bundleId");

		PasteJob job = buildJob(island, dimension, level, template, origin, bundleId, welcomeContext, onComplete);
		island.setStateBit(Island.STATE_PASTING, true);
		if (job.blocks().isEmpty()) {
			finalizeJob(job);
			return;
		}
		this.activeJobs.put(new PasteJobKey(island.islandId(), dimension.identifier().toString()), job);
	}

	public void pasteImmediately(
		Island island,
		ResourceKey<Level> dimension,
		ServerLevel level,
		StructureTemplate template,
		BlockPos origin,
		String bundleId
	) {
		pasteImmediately(island, dimension, level, template, origin, bundleId, null);
	}

	public void pasteImmediately(
		Island island,
		ResourceKey<Level> dimension,
		ServerLevel level,
		StructureTemplate template,
		BlockPos origin,
		String bundleId,
		BlueprintSignSupport.@Nullable WelcomeContext welcomeContext
	) {
		Objects.requireNonNull(island, "island");
		Objects.requireNonNull(dimension, "dimension");
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(template, "template");
		Objects.requireNonNull(origin, "origin");
		Objects.requireNonNull(bundleId, "bundleId");

		PasteJob job = buildJob(island, dimension, level, template, origin, bundleId, welcomeContext, null);
		try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(OgskyCore.LOGGER)) {
			HolderLookup.Provider registries = level.registryAccess();
			List<StructureTemplate.StructureBlockInfo> blocks = job.blocks();
			while (job.nextIndex < blocks.size()) {
				placeSingleBlock(level, registries, reporter, blocks.get(job.nextIndex));
				job.nextIndex++;
			}
		}
		finalizeJob(job);
	}

	public void tick(MinecraftServer server) {
		if (this.activeJobs.isEmpty()) {
			return;
		}
		int maxBlocks = this.config.blueprints.blocksPerTick;
		long maxNanos = this.config.blueprints.maxNanosPerTick;
		long startNanos = System.nanoTime();
		int placedThisTick = 0;

		Iterator<Map.Entry<PasteJobKey, PasteJob>> iterator = this.activeJobs.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<PasteJobKey, PasteJob> mapEntry = iterator.next();
			PasteJob job = mapEntry.getValue();

			// Abort queued paste cleanly if the island was deleted or vanished mid-paste.
			Optional<Island> liveIsland = this.islandService.grid().findById(job.islandId);
			if (liveIsland.isEmpty() || liveIsland.get().isDeleting()) {
				iterator.remove();
				continue;
			}

			int scannedThisJob = 0;
			int maxScanPerJob = Math.max(maxBlocks * 8, 64);
			try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(OgskyCore.LOGGER)) {
				HolderLookup.Provider registries = job.level.registryAccess();
				List<StructureTemplate.StructureBlockInfo> blocks = job.blocks();
				while (job.nextIndex < blocks.size()) {
					if (placedThisTick >= maxBlocks && placedThisTick > 0) {
						break;
					}
					if (placedThisTick > 0 && (System.nanoTime() - startNanos) >= maxNanos) {
						break;
					}
					if (scannedThisJob >= maxScanPerJob) {
						break;
					}

					StructureTemplate.StructureBlockInfo info = blocks.get(job.nextIndex++);
					scannedThisJob++;
					if (placeSingleBlock(job.level, registries, reporter, info)) {
						placedThisTick++;
					}
				}
			}

			if (job.nextIndex >= job.blocks().size()) {
				iterator.remove();
				finalizeJob(job);
			} else if (placedThisTick >= maxBlocks || (placedThisTick > 0 && (System.nanoTime() - startNanos) >= maxNanos)) {
				break;
			}
		}
	}

	private PasteJob buildJob(
		Island island,
		ResourceKey<Level> dimension,
		ServerLevel level,
		StructureTemplate template,
		BlockPos origin,
		String bundleId,
		BlueprintSignSupport.@Nullable WelcomeContext welcomeContext,
		@Nullable Runnable onComplete
	) {
		BoundingBox clipBounds = new BoundingBox(
			island.minX(),
			level.getMinY(),
			island.minZ(),
			island.maxX(),
			level.getMaxY(),
			island.maxZ()
		);
		StructurePlaceSettings settings = new StructurePlaceSettings()
			.setMirror(Mirror.NONE)
			.setRotation(Rotation.NONE)
			.setIgnoreEntities(false)
			.setKnownShape(true)
			.setBoundingBox(clipBounds);

		List<StructureTemplate.Palette> palettes = ((StructureTemplateAccessor) template).ogsky$getPalettes();
		List<StructureTemplate.StructureBlockInfo> processedBlocks = palettes.isEmpty()
			? List.of()
			: StructureTemplate.processBlockInfos(
				level,
				origin,
				origin,
				settings,
				settings.getRandomPalette(palettes, origin).blocks()
			);

		BlueprintSignSupport.WelcomeContext effectiveWelcome = welcomeContext != null
			? welcomeContext
			: new BlueprintSignSupport.WelcomeContext(
				island.ownerId().toString().substring(0, 8),
				bundleId,
				bundleId,
				island.islandId(),
				island.gridX(),
				island.gridZ()
			);
		List<StructureTemplate.StructureBlockInfo> preparedBlocks = prepareSignBlocks(processedBlocks, effectiveWelcome);

		return new PasteJob(
			island.islandId(),
			dimension,
			level,
			template,
			origin,
			clipBounds,
			bundleId,
			preparedBlocks,
			onComplete
		);
	}

	private List<StructureTemplate.StructureBlockInfo> prepareSignBlocks(
		List<StructureTemplate.StructureBlockInfo> processedBlocks,
		BlueprintSignSupport.WelcomeContext welcomeContext
	) {
		if (processedBlocks.isEmpty()) {
			return processedBlocks;
		}
		List<StructureTemplate.StructureBlockInfo> prepared = new ArrayList<>(processedBlocks.size());
		for (StructureTemplate.StructureBlockInfo info : processedBlocks) {
			if (info.state().getBlock() instanceof SignBlock && info.nbt() != null) {
				if (BlueprintSignSupport.isSpawnSign(info.nbt())) {
					// [SPAWN] signs define spawnpoint + yaw only and must not be pasted into the world.
					continue;
				}
				if (BlueprintSignSupport.hasWelcomeMarker(info.nbt())) {
					BlueprintSignSupport.rewriteWelcomeSignNbt(
						info.nbt(),
						this.config.blueprints.welcomeSignLines,
						welcomeContext
					);
				}
			}
			prepared.add(info);
		}
		return prepared;
	}

	private static boolean placeSingleBlock(
		ServerLevel level,
		HolderLookup.Provider registries,
		ProblemReporter.ScopedCollector reporter,
		StructureTemplate.StructureBlockInfo info
	) {
		BlockPos pos = info.pos();
		BlockState targetState = info.state();
		BlockState currentState = level.getBlockState(pos);

		if (info.nbt() == null && currentState.equals(targetState)) {
			return false;
		}
		if (info.nbt() != null) {
			level.setBlock(pos, Blocks.BARRIER.defaultBlockState(), Block.UPDATE_SKIP_ALL_SIDEEFFECTS | Block.UPDATE_INVISIBLE);
		}
		if (level.setBlock(pos, targetState, PASTE_BLOCK_FLAGS) && info.nbt() != null) {
			BlockEntity blockEntity = level.getBlockEntity(pos);
			if (blockEntity != null) {
				if (blockEntity instanceof RandomizableContainer) {
					info.nbt().putLong("LootTableSeed", level.getRandom().nextLong());
				}
				blockEntity.loadWithComponents(TagValueInput.create(reporter.forChild(blockEntity.problemPath()), registries, info.nbt()));
				blockEntity.setChanged();
				level.sendBlockUpdated(pos, targetState, targetState, Block.UPDATE_ALL);
			}
		}
		return true;
	}

	private void finalizeJob(PasteJob job) {
		try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(OgskyCore.LOGGER)) {
			((StructureTemplateAccessor) job.template).ogsky$placeEntities(
				job.level,
				job.origin,
				Mirror.NONE,
				Rotation.NONE,
				BlockPos.ZERO,
				job.clipBounds,
				true,
				reporter
			);
		}

		this.islandService.grid().findById(job.islandId).ifPresent(island -> {
			island.setStateBit(readyBitFor(job.dimension), true);
			if (!isPasting(job.islandId)) {
				island.setStateBit(Island.STATE_PASTING, false);
			}
			this.databaseService
				.find(CoreIslandRepository.MODULE_ID)
				.ifPresent(db -> CoreIslandRepository.upsertIslandAsync(db, island));
		});

		this.eventBus.publish(new BlueprintPasteCompletedEvent(
			job.islandId,
			job.dimension.identifier(),
			job.bundleId
		));
		if (job.onComplete != null) {
			job.onComplete.run();
		}
	}

	private static byte readyBitFor(ResourceKey<Level> dimension) {
		if (dimension.identifier().getPath().equals("overworld")) {
			return Island.STATE_OVERWORLD_READY;
		}
		if (dimension.identifier().getPath().equals("nether")) {
			return Island.STATE_NETHER_READY;
		}
		return Island.STATE_END_READY;
	}

	private record PasteJobKey(UUID islandId, String dimensionId) {
	}

	private static final class PasteJob {
		final UUID islandId;
		final ResourceKey<Level> dimension;
		final ServerLevel level;
		final StructureTemplate template;
		final BlockPos origin;
		final BoundingBox clipBounds;
		final String bundleId;
		final List<StructureTemplate.StructureBlockInfo> blocks;
		final @Nullable Runnable onComplete;
		int nextIndex;

		private PasteJob(
			UUID islandId,
			ResourceKey<Level> dimension,
			ServerLevel level,
			StructureTemplate template,
			BlockPos origin,
			BoundingBox clipBounds,
			String bundleId,
			List<StructureTemplate.StructureBlockInfo> blocks,
			@Nullable Runnable onComplete
		) {
			this.islandId = islandId;
			this.dimension = dimension;
			this.level = level;
			this.template = template;
			this.origin = origin;
			this.clipBounds = clipBounds;
			this.bundleId = bundleId;
			this.blocks = blocks;
			this.onComplete = onComplete;
		}

		List<StructureTemplate.StructureBlockInfo> blocks() {
			return this.blocks;
		}
	}
}
