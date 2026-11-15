package me.ogsammaenr.ogsky.core.internal.blueprint;

import me.ogsammaenr.ogsky.core.api.db.DatabaseService;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfig;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandRepository;
import me.ogsammaenr.ogsky.core.internal.island.GridCleanupEntry;
import me.ogsammaenr.ogsky.core.internal.island.IslandGrid;
import me.ogsammaenr.ogsky.core.internal.island.PackedGridMap;
import me.ogsammaenr.ogsky.core.internal.world.OgskyDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Tick-budgeted multi-dimension island cleaner. Clears blocks and non-player entities in bounded chunk batches
 * across {@code ogsky:overworld}, {@code ogsky:nether}, and {@code ogsky:end} before freeing a grid cell for reuse.
 */
public final class ChunkCleanupQueue {
	public static final int CLEAR_BLOCK_FLAGS =
		Block.UPDATE_CLIENTS
			| Block.UPDATE_KNOWN_SHAPE
			| Block.UPDATE_SUPPRESS_DROPS
			| Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS
			| Block.UPDATE_SKIP_ON_PLACE;

	private final CoreConfig config;
	private final IslandGrid grid;
	private final DatabaseService databaseService;
	private final Map<Long, Runnable> completionCallbacks = new HashMap<>();

	public ChunkCleanupQueue(CoreConfig config, IslandGrid grid, DatabaseService databaseService) {
		this.config = Objects.requireNonNull(config, "config");
		this.grid = Objects.requireNonNull(grid, "grid");
		this.databaseService = Objects.requireNonNull(databaseService, "databaseService");
	}

	public GridCleanupEntry enqueue(int gridX, int gridZ, int minX, int maxX, int minZ, int maxZ) {
		return enqueue(gridX, gridZ, minX, maxX, minZ, maxZ, null);
	}

	public GridCleanupEntry enqueue(
		int gridX,
		int gridZ,
		int minX,
		int maxX,
		int minZ,
		int maxZ,
		@Nullable Runnable onComplete
	) {
		int startChunkX = Math.floorDiv(minX, 16);
		int startChunkZ = Math.floorDiv(minZ, 16);
		GridCleanupEntry entry = new GridCleanupEntry(
			gridX,
			gridZ,
			minX,
			maxX,
			minZ,
			maxZ,
			startChunkX,
			startChunkZ,
			Instant.now()
		);
		this.grid.addCleanupEntry(entry);
		long packedCell = PackedGridMap.pack(gridX, gridZ);
		if (onComplete != null) {
			this.completionCallbacks.put(packedCell, onComplete);
		} else {
			this.completionCallbacks.remove(packedCell);
		}
		this.databaseService
			.find(CoreIslandRepository.MODULE_ID)
			.ifPresent(db -> CoreIslandRepository.upsertCleanupEntryAsync(db, entry));
		return entry;
	}

	public void tick(MinecraftServer server) {
		Collection<GridCleanupEntry> pending = this.grid.pendingCleanups();
		if (pending.isEmpty()) {
			return;
		}
		int budgetChunks = this.config.blueprints.cleanupChunksPerTick;
		int processed = 0;

		for (GridCleanupEntry initial : pending) {
			if (processed >= budgetChunks) {
				break;
			}
			int minChunkX = Math.floorDiv(initial.minX(), 16);
			int maxChunkX = Math.floorDiv(initial.maxX(), 16);
			int minChunkZ = Math.floorDiv(initial.minZ(), 16);
			int maxChunkZ = Math.floorDiv(initial.maxZ(), 16);

			int cursorX = Math.max(minChunkX, initial.cursorChunkX());
			int cursorZ = Math.max(minChunkZ, initial.cursorChunkZ());
			boolean completed = false;

			while (processed < budgetChunks) {
				if (cursorZ > maxChunkZ) {
					completed = true;
					break;
				}
				clearChunkColumnAcrossDimensions(server, initial, cursorX, cursorZ);
				processed++;

				cursorX++;
				if (cursorX > maxChunkX) {
					cursorX = minChunkX;
					cursorZ++;
					if (cursorZ > maxChunkZ) {
						completed = true;
						break;
					}
				}
			}

			if (completed) {
				this.grid.completeCleanupCell(initial.gridX(), initial.gridZ());
				this.databaseService
					.find(CoreIslandRepository.MODULE_ID)
					.ifPresent(db -> CoreIslandRepository.deleteCleanupEntryAsync(db, initial.gridX(), initial.gridZ()));
				Runnable callback = this.completionCallbacks.remove(PackedGridMap.pack(initial.gridX(), initial.gridZ()));
				if (callback != null) {
					callback.run();
				}
			} else {
				GridCleanupEntry updated = new GridCleanupEntry(
					initial.gridX(),
					initial.gridZ(),
					initial.minX(),
					initial.maxX(),
					initial.minZ(),
					initial.maxZ(),
					cursorX,
					cursorZ,
					initial.createdAt()
				);
				this.grid.addCleanupEntry(updated);
				if ((processed & 15) == 0 || cursorX == minChunkX) {
					this.databaseService
						.find(CoreIslandRepository.MODULE_ID)
						.ifPresent(db -> CoreIslandRepository.upsertCleanupEntryAsync(db, updated));
				}
			}
		}
	}

	public void flushProgress() {
		this.completionCallbacks.clear();
		this.databaseService.find(CoreIslandRepository.MODULE_ID).ifPresent(db -> {
			for (GridCleanupEntry entry : this.grid.pendingCleanups()) {
				CoreIslandRepository.upsertCleanupEntryAsync(db, entry);
			}
		});
	}

	private static void clearChunkColumnAcrossDimensions(
		MinecraftServer server,
		GridCleanupEntry entry,
		int chunkX,
		int chunkZ
	) {
		for (ResourceKey<Level> dimension : OgskyDimensions.ALL) {
			ServerLevel level = server.getLevel(dimension);
			if (level != null) {
				clearChunkWithinBounds(level, entry, chunkX, chunkZ);
			}
		}
	}

	private static void clearChunkWithinBounds(
		ServerLevel level,
		GridCleanupEntry entry,
		int chunkX,
		int chunkZ
	) {
		int chunkMinX = SectionPos.sectionToBlockCoord(chunkX);
		int chunkMinZ = SectionPos.sectionToBlockCoord(chunkZ);
		int boxMinX = Math.max(entry.minX(), chunkMinX);
		int boxMaxX = Math.min(entry.maxX(), chunkMinX + 15);
		int boxMinZ = Math.max(entry.minZ(), chunkMinZ);
		int boxMaxZ = Math.min(entry.maxZ(), chunkMinZ + 15);
		if (boxMinX > boxMaxX || boxMinZ > boxMaxZ) {
			return;
		}

		AABB entityBox = new AABB(
			boxMinX,
			level.getMinY(),
			boxMinZ,
			boxMaxX + 1.0D,
			level.getMaxY() + 1.0D,
			boxMaxZ + 1.0D
		);
		for (Entity entity : level.getEntitiesOfClass(Entity.class, entityBox, candidate -> !(candidate instanceof Player))) {
			entity.discard();
		}

		LevelChunk chunk = level.getChunk(chunkX, chunkZ);
		LevelChunkSection[] sections = chunk.getSections();
		BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
		int minLocalX = boxMinX - chunkMinX;
		int maxLocalX = boxMaxX - chunkMinX;
		int minLocalZ = boxMinZ - chunkMinZ;
		int maxLocalZ = boxMaxZ - chunkMinZ;

		for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
			LevelChunkSection section = sections[sectionIndex];
			if (section == null || section.hasOnlyAir()) {
				continue;
			}
			int baseY = chunk.getSectionYFromSectionIndex(sectionIndex) * 16;
			for (int localY = 0; localY < 16; localY++) {
				int worldY = baseY + localY;
				if (worldY < level.getMinY() || worldY > level.getMaxY()) {
					continue;
				}
				for (int localX = minLocalX; localX <= maxLocalX; localX++) {
					int worldX = chunkMinX + localX;
					for (int localZ = minLocalZ; localZ <= maxLocalZ; localZ++) {
						BlockState state = section.getBlockState(localX, localY, localZ);
						if (!state.isAir()) {
							int worldZ = chunkMinZ + localZ;
							mutablePos.set(worldX, worldY, worldZ);
							level.removeBlockEntity(mutablePos);
							level.setBlock(mutablePos, Blocks.AIR.defaultBlockState(), CLEAR_BLOCK_FLAGS);
						}
					}
				}
			}
		}
	}
}
