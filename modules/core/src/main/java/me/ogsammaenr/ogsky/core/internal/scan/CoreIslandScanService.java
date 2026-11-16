package me.ogsammaenr.ogsky.core.internal.scan;

import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.scan.BlockStateCountConsumer;
import me.ogsammaenr.ogsky.core.api.scan.EntityTypeCountConsumer;
import me.ogsammaenr.ogsky.core.api.scan.IslandScanOptions;
import me.ogsammaenr.ogsky.core.api.scan.IslandScanResult;
import me.ogsammaenr.ogsky.core.api.scan.IslandScanService;
import me.ogsammaenr.ogsky.core.api.world.OgskyDimensionKeys;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandService;
import me.ogsammaenr.ogsky.core.internal.island.Island;
import me.ogsammaenr.ogsky.core.mixin.ChunkMapAccessor;
import me.ogsammaenr.ogsky.core.mixin.EntityStorageAccessor;
import me.ogsammaenr.ogsky.core.mixin.PersistentEntityManagerAccessor;
import me.ogsammaenr.ogsky.core.mixin.ServerLevelEntityAccessor;
import net.minecraft.SharedConstants;
import net.minecraft.core.SectionPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Tick-budgeted Skyblock island block scanner shared across optional modules.
 * Uses in-memory {@link LevelChunkSection} fast paths for loaded chunks and non-blocking
 * {@link ChunkMap#read(ChunkPos)} disk reads for unloaded saved chunks without generating void chunks.
 */
public final class CoreIslandScanService implements IslandScanService {
	private final CoreIslandService islandService;
	private final List<ScanJob> activeJobs = new ArrayList<>();

	public CoreIslandScanService(CoreIslandService islandService) {
		this.islandService = Objects.requireNonNull(islandService, "islandService");
	}

	@Override
	public CompletableFuture<IslandScanResult> scanIsland(
		MinecraftServer server,
		UUID islandId,
		IslandScanOptions options,
		BlockStateCountConsumer consumer
	) {
		return scanIslandWithEntities(server, islandId, options, consumer, null);
	}

	@Override
	public CompletableFuture<IslandScanResult> scanIslandWithEntities(
		MinecraftServer server,
		UUID islandId,
		IslandScanOptions options,
		BlockStateCountConsumer consumer,
		@Nullable EntityTypeCountConsumer entityConsumer
	) {
		Objects.requireNonNull(server, "server");
		Objects.requireNonNull(islandId, "islandId");
		Objects.requireNonNull(options, "options");
		Objects.requireNonNull(consumer, "consumer");

		Optional<Island> maybeIsland = islandService.grid().findById(islandId);
		if (maybeIsland.isEmpty()) {
			return CompletableFuture.completedFuture(abortedResult(islandId, "Island does not exist."));
		}
		Island island = maybeIsland.get();
		if (island.isDeleting()) {
			return CompletableFuture.completedFuture(abortedResult(islandId, "Island is being deleted."));
		}
		if (island.isPasting()) {
			return CompletableFuture.completedFuture(abortedResult(islandId, "Island blueprint paste is in progress."));
		}

		List<ChunkTarget> targets = buildChunkTargets(server, island);
		if (targets.isEmpty()) {
			return CompletableFuture.completedFuture(
				new IslandScanResult(islandId, 0, 0, 0, 0, 0L, false, "")
			);
		}

		CompletableFuture<IslandScanResult> future = new CompletableFuture<>();
		ScanJob job = new ScanJob(
			islandId,
			island.minX(),
			island.maxX(),
			island.minZ(),
			island.maxZ(),
			options,
			consumer,
			entityConsumer,
			targets,
			future
		);
		activeJobs.add(job);
		return future;
	}

	@Override
	public void cancelScansForIsland(UUID islandId) {
		Objects.requireNonNull(islandId, "islandId");
		Iterator<ScanJob> it = activeJobs.iterator();
		while (it.hasNext()) {
			ScanJob job = it.next();
			if (job.islandId.equals(islandId)) {
				it.remove();
				job.completeAborted("Scan cancelled for island.");
			}
		}
	}

	public void cancelAll() {
		for (ScanJob job : activeJobs) {
			job.completeAborted("Server stopping.");
		}
		activeJobs.clear();
	}

	public int activeJobCount() {
		return activeJobs.size();
	}

	public void tick(MinecraftServer server) {
		if (activeJobs.isEmpty()) {
			return;
		}
		Iterator<ScanJob> it = activeJobs.iterator();
		while (it.hasNext()) {
			ScanJob job = it.next();
			if (tickSingleJob(server, job)) {
				it.remove();
			}
		}
	}

	private boolean tickSingleJob(MinecraftServer server, ScanJob job) {
		if (job.future.isCancelled()) {
			job.completeAborted("Scan cancelled.");
			return true;
		}
		Optional<Island> maybeIsland = islandService.grid().findById(job.islandId);
		if (maybeIsland.isEmpty()) {
			job.completeAborted("Island was deleted during scan.");
			return true;
		}
		Island island = maybeIsland.get();
		if (island.isDeleting()) {
			job.completeAborted("Island deletion started during scan.");
			return true;
		}
		if (island.isPasting()) {
			job.completeAborted("Island reset started during scan.");
			return true;
		}

		long deadlineNanos = System.nanoTime() + job.options.maxNanosPerTick();
		int chunksThisTick = 0;

		// 1. Drain completed async disk reads first so in-flight slots free up promptly.
		while (!job.inFlightReads.isEmpty()) {
			InFlightRead head = job.inFlightReads.peekFirst();
			if (head == null || !head.readFuture.isDone()) {
				// Scan any later completed future as well rather than head-of-line blocking on one slow region file.
				InFlightRead ready = pollAnyCompleted(job.inFlightReads);
				if (ready == null) {
					break;
				}
				processCompletedDiskRead(server, job, ready);
			} else {
				job.inFlightReads.pollFirst();
				processCompletedDiskRead(server, job, head);
			}
			chunksThisTick++;
			if (chunksThisTick >= job.options.chunksPerTick() || System.nanoTime() >= deadlineNanos) {
				return false;
			}
		}

		// 2. Dispatch new chunks up to the tick chunk/time budget.
		while (job.nextTargetIndex < job.targets.size()) {
			if (chunksThisTick >= job.options.chunksPerTick() || System.nanoTime() >= deadlineNanos) {
				break;
			}
			ChunkTarget target = job.targets.get(job.nextTargetIndex);
			ServerLevel level = server.getLevel(target.dimension);
			if (level == null) {
				job.nextTargetIndex++;
				job.skippedUnbornChunks++;
				continue;
			}

			LevelChunk loaded = findInMemoryChunk(level, target.chunkX, target.chunkZ);
			if (loaded != null) {
				job.nextTargetIndex++;
				job.nonAirBlocksCounted += scanLiveChunk(level, loaded, job);
				job.loadedChunksScanned++;
				chunksThisTick++;
				continue;
			}

			if (!job.options.includeUnloadedChunks()) {
				job.nextTargetIndex++;
				job.skippedUnbornChunks++;
				continue;
			}

			if (job.inFlightReads.size() >= job.options.maxInFlightDiskReads()) {
				break;
			}

			job.nextTargetIndex++;
			ChunkPos pos = new ChunkPos(target.chunkX, target.chunkZ);
			CompletableFuture<Optional<CompoundTag>> readFuture = level.getChunkSource().chunkMap.read(pos)
				.exceptionally(ex -> {
					OgskyCore.LOGGER.warn("Failed disk chunk read at {} ({}): {}", pos, target.dimension.identifier(), ex.toString());
					return Optional.empty();
				});
			job.inFlightReads.addLast(new InFlightRead(target.dimension, target.chunkX, target.chunkZ, readFuture));
		}

		// 3. Process entity chunks (live EntitySectionStorage + async unloaded entities/*.mca NBT reads).
		if (job.entityConsumer != null) {
			while (!job.inFlightEntityReads.isEmpty()) {
				InFlightRead head = job.inFlightEntityReads.peekFirst();
				InFlightRead ready = (head != null && head.readFuture.isDone())
					? job.inFlightEntityReads.pollFirst()
					: pollAnyCompleted(job.inFlightEntityReads);
				if (ready == null) {
					break;
				}
				processCompletedEntityDiskRead(server, job, ready);
				chunksThisTick++;
				if (chunksThisTick >= job.options.chunksPerTick() || System.nanoTime() >= deadlineNanos) {
					return false;
				}
			}

			while (job.nextEntityTargetIndex < job.targets.size()) {
				if (chunksThisTick >= job.options.chunksPerTick() || System.nanoTime() >= deadlineNanos) {
					break;
				}
				ChunkTarget target = job.targets.get(job.nextEntityTargetIndex);
				ServerLevel level = server.getLevel(target.dimension);
				if (level == null) {
					job.nextEntityTargetIndex++;
					continue;
				}

				PersistentEntitySectionManager<Entity> entityManager = ((ServerLevelEntityAccessor) level).ogsky$getEntityManager();
				long chunkKey = ChunkPos.pack(target.chunkX, target.chunkZ);
				if (entityManager.areEntitiesLoaded(chunkKey)) {
					job.nextEntityTargetIndex++;
					scanLiveEntitySections(entityManager, chunkKey, job, null);
					chunksThisTick++;
					continue;
				}

				if (!job.options.includeUnloadedChunks()) {
					job.nextEntityTargetIndex++;
					scanLiveEntitySections(entityManager, chunkKey, job, null);
					chunksThisTick++;
					continue;
				}

				@SuppressWarnings("unchecked")
				SimpleRegionStorage regionStorage = entityRegionStorage(entityManager);
				if (regionStorage == null) {
					job.nextEntityTargetIndex++;
					scanLiveEntitySections(entityManager, chunkKey, job, null);
					chunksThisTick++;
					continue;
				}

				if (job.inFlightEntityReads.size() >= job.options.maxInFlightDiskReads()) {
					break;
				}

				job.nextEntityTargetIndex++;
				ChunkPos pos = new ChunkPos(target.chunkX, target.chunkZ);
				CompletableFuture<Optional<CompoundTag>> entityFuture = regionStorage.read(pos)
					.exceptionally(ex -> {
						OgskyCore.LOGGER.warn("Failed disk entity read at {} ({}): {}", pos, target.dimension.identifier(), ex.toString());
						return Optional.empty();
					});
				job.inFlightEntityReads.addLast(new InFlightRead(target.dimension, target.chunkX, target.chunkZ, entityFuture));
			}
		}

		boolean blocksDone = job.nextTargetIndex >= job.targets.size() && job.inFlightReads.isEmpty();
		boolean entitiesDone = job.entityConsumer == null
			|| (job.nextEntityTargetIndex >= job.targets.size() && job.inFlightEntityReads.isEmpty());
		if (blocksDone && entitiesDone) {
			job.completeSuccess();
			return true;
		}
		return false;
	}

	private static @Nullable SimpleRegionStorage entityRegionStorage(PersistentEntitySectionManager<Entity> entityManager) {
		var permanentStorage = ((PersistentEntityManagerAccessor<?>) entityManager).ogsky$getPermanentStorage();
		if (permanentStorage instanceof EntityStorage entityStorage) {
			return ((EntityStorageAccessor) entityStorage).ogsky$getSimpleRegionStorage();
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private static void scanLiveEntitySections(
		PersistentEntitySectionManager<Entity> entityManager,
		long chunkKey,
		ScanJob job,
		@Nullable Set<UUID> seenUuids
	) {
		if (job.entityConsumer == null) {
			return;
		}
		EntitySectionStorage<Entity> sectionStorage = ((PersistentEntityManagerAccessor<Entity>) entityManager).ogsky$getSectionStorage();
		sectionStorage.getExistingSectionsInChunk(chunkKey).forEach(section ->
			section.getEntities().forEach(entity -> {
				if (entity.isRemoved()) {
					return;
				}
				if (seenUuids != null) {
					seenUuids.add(entity.getUUID());
				}
				int bx = Mth.floor(entity.getX());
				int bz = Mth.floor(entity.getZ());
				if (bx >= job.minX && bx <= job.maxX && bz >= job.minZ && bz <= job.maxZ) {
					job.entityConsumer.accept(entity.getType(), 1);
				}
			})
		);
	}

	private static void processCompletedEntityDiskRead(MinecraftServer server, ScanJob job, InFlightRead completed) {
		if (job.entityConsumer == null) {
			return;
		}
		ServerLevel level = server.getLevel(completed.dimension);
		if (level == null) {
			return;
		}
		PersistentEntitySectionManager<Entity> entityManager = ((ServerLevelEntityAccessor) level).ogsky$getEntityManager();
		long chunkKey = ChunkPos.pack(completed.chunkX, completed.chunkZ);
		if (entityManager.areEntitiesLoaded(chunkKey)) {
			scanLiveEntitySections(entityManager, chunkKey, job, null);
			return;
		}

		// Collect any entities present in memory for this chunk before merging disk NBT to avoid double-counting.
		Set<UUID> seenLiveUuids = new HashSet<>(0);
		scanLiveEntitySections(entityManager, chunkKey, job, seenLiveUuids);

		Optional<CompoundTag> maybeTag = completed.readFuture.getNow(Optional.empty());
		if (maybeTag.isEmpty()) {
			return;
		}

		SimpleRegionStorage regionStorage = entityRegionStorage(entityManager);
		if (regionStorage == null) {
			return;
		}
		try {
			CompoundTag upgraded = regionStorage.upgradeChunkTag(maybeTag.get(), -1);
			ListTag entities = upgraded.getListOrEmpty("Entities");
			countNbtEntities(entities, Double.NaN, Double.NaN, seenLiveUuids, job);
		} catch (Exception ex) {
			OgskyCore.LOGGER.warn(
				"Failed parsing serialized entities ({}, {}) in {} during island scan {}: {}",
				completed.chunkX,
				completed.chunkZ,
				completed.dimension.identifier(),
				job.islandId,
				ex.toString()
			);
		}
	}

	private static void countNbtEntities(
		ListTag entities,
		double fallbackX,
		double fallbackZ,
		Set<UUID> seenLiveUuids,
		ScanJob job
	) {
		if (job.entityConsumer == null || entities.isEmpty()) {
			return;
		}
		for (int i = 0; i < entities.size(); i++) {
			CompoundTag entityTag = entities.getCompoundOrEmpty(i);
			if (entityTag.isEmpty()) {
				continue;
			}
			ListTag posTag = entityTag.getListOrEmpty("Pos");
			double x = posTag.size() >= 3 ? posTag.getDoubleOr(0, fallbackX) : fallbackX;
			double z = posTag.size() >= 3 ? posTag.getDoubleOr(2, fallbackZ) : fallbackZ;

			boolean skipSelf = !seenLiveUuids.isEmpty()
				&& entityTag.read("UUID", UUIDUtil.CODEC).map(seenLiveUuids::contains).orElse(false);

			if (!skipSelf && !Double.isNaN(x) && !Double.isNaN(z)) {
				int bx = Mth.floor(x);
				int bz = Mth.floor(z);
				if (bx >= job.minX && bx <= job.maxX && bz >= job.minZ && bz <= job.maxZ) {
					String rawId = entityTag.getStringOr("id", "");
					Identifier typeId = Identifier.tryParse(rawId);
					if (typeId != null) {
						BuiltInRegistries.ENTITY_TYPE.getOptional(typeId)
							.ifPresent(type -> job.entityConsumer.accept(type, 1));
					}
				}
			}

			ListTag passengers = entityTag.getListOrEmpty("Passengers");
			if (!passengers.isEmpty()) {
				countNbtEntities(passengers, x, z, seenLiveUuids, job);
			}
		}
	}

	private static @Nullable InFlightRead pollAnyCompleted(ArrayDeque<InFlightRead> queue) {
		Iterator<InFlightRead> it = queue.iterator();
		while (it.hasNext()) {
			InFlightRead candidate = it.next();
			if (candidate.readFuture.isDone()) {
				it.remove();
				return candidate;
			}
		}
		return null;
	}

	private static void processCompletedDiskRead(MinecraftServer server, ScanJob job, InFlightRead completed) {
		ServerLevel level = server.getLevel(completed.dimension);
		if (level == null) {
			job.skippedUnbornChunks++;
			return;
		}
		// If the chunk is currently in memory (active, demoted, or pending unload), scan live memory instead of disk snapshot.
		LevelChunk nowLoaded = findInMemoryChunk(level, completed.chunkX, completed.chunkZ);
		if (nowLoaded != null) {
			job.nonAirBlocksCounted += scanLiveChunk(level, nowLoaded, job);
			job.loadedChunksScanned++;
			return;
		}

		Optional<CompoundTag> maybeTag = completed.readFuture.getNow(Optional.empty());
		if (maybeTag.isEmpty()) {
			job.skippedUnbornChunks++;
			return;
		}

		try {
			ChunkMap chunkMap = level.getChunkSource().chunkMap;
			CompoundTag upgraded = chunkMap.upgradeChunkTag(
				maybeTag.get(),
				-1,
				ChunkMap.getChunkDataFixContextTag(
					level.dimension(),
					level.getChunkSource().getGenerator().getTypeNameForDataFixer()
				),
				SharedConstants.getCurrentVersion().dataVersion().version()
			);
			SerializableChunkData parsed = SerializableChunkData.parse(
				level,
				level.palettedContainerFactory(),
				upgraded
			);
			if (parsed == null) {
				job.skippedUnbornChunks++;
				return;
			}
			long counted = 0L;
			for (SerializableChunkData.SectionData sectionData : parsed.sectionData()) {
				counted += scanSection(
					level,
					sectionData.chunkSection(),
					sectionData.y(),
					completed.chunkX,
					completed.chunkZ,
					job.minX,
					job.maxX,
					job.minZ,
					job.maxZ,
					job.consumer
				);
			}
			job.nonAirBlocksCounted += counted;
			job.diskChunksScanned++;
		} catch (Exception ex) {
			OgskyCore.LOGGER.warn(
				"Failed parsing serialized chunk ({}, {}) in {} during island scan {}: {}",
				completed.chunkX,
				completed.chunkZ,
				completed.dimension.identifier(),
				job.islandId,
				ex.toString()
			);
			job.skippedUnbornChunks++;
		}
	}

	private static long scanLiveChunk(ServerLevel level, LevelChunk chunk, ScanJob job) {
		LevelChunkSection[] sections = chunk.getSections();
		ChunkPos pos = chunk.getPos();
		long counted = 0L;
		for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
			int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
			counted += scanSection(
				level,
				sections[sectionIndex],
				sectionY,
				pos.x(),
				pos.z(),
				job.minX,
				job.maxX,
				job.minZ,
				job.maxZ,
				job.consumer
			);
		}
		return counted;
	}

	/**
	 * Scans a single 16x16x16 chunk section clipped against the island's bounds.
	 * Uses palette-frequency counting on interior sections to avoid per-block lookups.
	 */
	static long scanSection(
		ServerLevel level,
		@Nullable LevelChunkSection section,
		int sectionY,
		int chunkX,
		int chunkZ,
		int islandMinX,
		int islandMaxX,
		int islandMinZ,
		int islandMaxZ,
		BlockStateCountConsumer consumer
	) {
		if (section == null || section.hasOnlyAir()) {
			return 0L;
		}
		int baseY = SectionPos.sectionToBlockCoord(sectionY);
		int maxSectionY = baseY + 15;
		int worldMinY = level.getMinY();
		int worldMaxY = level.getMaxY();
		if (maxSectionY < worldMinY || baseY > worldMaxY) {
			return 0L;
		}

		int chunkMinX = SectionPos.sectionToBlockCoord(chunkX);
		int chunkMaxX = chunkMinX + 15;
		int chunkMinZ = SectionPos.sectionToBlockCoord(chunkZ);
		int chunkMaxZ = chunkMinZ + 15;
		if (chunkMaxX < islandMinX || chunkMinX > islandMaxX || chunkMaxZ < islandMinZ || chunkMinZ > islandMaxZ) {
			return 0L;
		}

		boolean fullyInsideHorizontally =
			chunkMinX >= islandMinX && chunkMaxX <= islandMaxX
				&& chunkMinZ >= islandMinZ && chunkMaxZ <= islandMaxZ;
		boolean fullyInsideVertically = baseY >= worldMinY && maxSectionY <= worldMaxY;

		if (fullyInsideHorizontally && fullyInsideVertically) {
			long[] nonAir = { 0L };
			section.getStates().count((state, count) -> {
				if (!state.isAir() && count > 0) {
					nonAir[0] += count;
					consumer.accept(state, count);
				}
			});
			return nonAir[0];
		}

		int minLocalX = Math.max(0, islandMinX - chunkMinX);
		int maxLocalX = Math.min(15, islandMaxX - chunkMinX);
		int minLocalZ = Math.max(0, islandMinZ - chunkMinZ);
		int maxLocalZ = Math.min(15, islandMaxZ - chunkMinZ);
		int minLocalY = Math.max(0, worldMinY - baseY);
		int maxLocalY = Math.min(15, worldMaxY - baseY);

		long nonAir = 0L;
		for (int localY = minLocalY; localY <= maxLocalY; localY++) {
			for (int localX = minLocalX; localX <= maxLocalX; localX++) {
				for (int localZ = minLocalZ; localZ <= maxLocalZ; localZ++) {
					BlockState state = section.getBlockState(localX, localY, localZ);
					if (!state.isAir()) {
						nonAir++;
						consumer.accept(state, 1);
					}
				}
			}
		}
		return nonAir;
	}

	private static @Nullable LevelChunk findInMemoryChunk(ServerLevel level, int chunkX, int chunkZ) {
		LevelChunk live = level.getChunkSource().getChunkNow(chunkX, chunkZ);
		if (live != null) {
			return live;
		}
		ChunkMap chunkMap = level.getChunkSource().chunkMap;
		long key = ChunkPos.pack(chunkX, chunkZ);
		// Include demoted and pending-unload holders still in RAM so recent edits are never shadowed by stale disk NBT.
		ChunkHolder holder = chunkMap.getUpdatingChunkIfPresent(key);
		if (holder == null) {
			holder = ((ChunkMapAccessor) chunkMap).ogsky$getPendingUnloads().get(key);
		}
		if (holder != null && holder.getChunkIfPresentUnchecked(ChunkStatus.FULL) instanceof LevelChunk levelChunk) {
			return levelChunk;
		}
		return null;
	}

	private static List<ChunkTarget> buildChunkTargets(MinecraftServer server, Island island) {
		int minChunkX = SectionPos.blockToSectionCoord(island.minX());
		int maxChunkX = SectionPos.blockToSectionCoord(island.maxX());
		int minChunkZ = SectionPos.blockToSectionCoord(island.minZ());
		int maxChunkZ = SectionPos.blockToSectionCoord(island.maxZ());

		List<ResourceKey<Level>> activeDims = new ArrayList<>(3);
		if (server.getLevel(OgskyDimensionKeys.OVERWORLD) != null) {
			activeDims.add(OgskyDimensionKeys.OVERWORLD);
		}
		if (island.isNetherReady() && server.getLevel(OgskyDimensionKeys.NETHER) != null) {
			activeDims.add(OgskyDimensionKeys.NETHER);
		}
		if (island.isEndReady() && server.getLevel(OgskyDimensionKeys.END) != null) {
			activeDims.add(OgskyDimensionKeys.END);
		}

		int chunksPerDim = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
		List<ChunkTarget> targets = new ArrayList<>(activeDims.size() * chunksPerDim);
		for (ResourceKey<Level> dim : activeDims) {
			for (int cx = minChunkX; cx <= maxChunkX; cx++) {
				for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
					targets.add(new ChunkTarget(dim, cx, cz));
				}
			}
		}
		return targets;
	}

	private static IslandScanResult abortedResult(UUID islandId, String reason) {
		return new IslandScanResult(islandId, 0, 0, 0, 0, 0L, true, reason);
	}

	private record ChunkTarget(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
	}

	private record InFlightRead(
		ResourceKey<Level> dimension,
		int chunkX,
		int chunkZ,
		CompletableFuture<Optional<CompoundTag>> readFuture
	) {
	}

	private static final class ScanJob {
		private final UUID islandId;
		private final int minX;
		private final int maxX;
		private final int minZ;
		private final int maxZ;
		private final IslandScanOptions options;
		private final BlockStateCountConsumer consumer;
		private final @Nullable EntityTypeCountConsumer entityConsumer;
		private final List<ChunkTarget> targets;
		private final ArrayDeque<InFlightRead> inFlightReads = new ArrayDeque<>();
		private final ArrayDeque<InFlightRead> inFlightEntityReads = new ArrayDeque<>();
		private final CompletableFuture<IslandScanResult> future;

		private int nextTargetIndex;
		private int nextEntityTargetIndex;
		private int loadedChunksScanned;
		private int diskChunksScanned;
		private int skippedUnbornChunks;
		private long nonAirBlocksCounted;

		private ScanJob(
			UUID islandId,
			int minX,
			int maxX,
			int minZ,
			int maxZ,
			IslandScanOptions options,
			BlockStateCountConsumer consumer,
			@Nullable EntityTypeCountConsumer entityConsumer,
			List<ChunkTarget> targets,
			CompletableFuture<IslandScanResult> future
		) {
			this.islandId = islandId;
			this.minX = minX;
			this.maxX = maxX;
			this.minZ = minZ;
			this.maxZ = maxZ;
			this.options = options;
			this.consumer = consumer;
			this.entityConsumer = entityConsumer;
			this.targets = targets;
			this.future = future;
		}

		private void completeSuccess() {
			future.complete(new IslandScanResult(
				islandId,
				targets.size(),
				loadedChunksScanned,
				diskChunksScanned,
				skippedUnbornChunks,
				nonAirBlocksCounted,
				false,
				""
			));
		}

		private void completeAborted(String reason) {
			future.complete(new IslandScanResult(
				islandId,
				targets.size(),
				loadedChunksScanned,
				diskChunksScanned,
				skippedUnbornChunks,
				nonAirBlocksCounted,
				true,
				reason
			));
		}
	}
}
