package me.ogsammaenr.ogsky.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import me.ogsammaenr.ogsky.core.api.event.IslandPreDeleteEvent;
import me.ogsammaenr.ogsky.core.internal.OgskyCoreApiImpl;
import me.ogsammaenr.ogsky.core.internal.blueprint.ChunkCleanupQueue;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandRepository;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandService;
import me.ogsammaenr.ogsky.core.internal.world.OgskyDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Shared mock-player, blueprint synthesis, cleanup, and packet inspection helpers for headless Ogsky GameTests.
 */
final class GameTestSupport {
	private GameTestSupport() {
	}

	@FunctionalInterface
	interface ThrowingRunnable {
		void run() throws Exception;
	}

	static void runWithCleanup(Runnable cleanup, ThrowingRunnable body) {
		try {
			body.run();
		} catch (Throwable t) {
			cleanup.run();
			if (t instanceof RuntimeException re) {
				throw re;
			}
			if (t instanceof Error err) {
				throw err;
			}
			throw new RuntimeException(t);
		}
	}

	static Runnable guarded(Runnable cleanup, Runnable body) {
		return () -> {
			try {
				body.run();
			} catch (Throwable t) {
				cleanup.run();
				throw t;
			}
		};
	}

	static int findContentSlotByName(ChestMenu menu, String expectedName) {
		int[] contentSlots = {
			10, 11, 12, 13, 14, 15, 16,
			19, 20, 21, 22, 23, 24, 25,
			28, 29, 30, 31, 32, 33, 34,
			37, 38, 39, 40, 41, 42, 43
		};
		for (int slot : contentSlots) {
			ItemStack stack = menu.getSlot(slot).getItem();
			if (stack.isEmpty()) {
				continue;
			}
			var customName = stack.get(DataComponents.CUSTOM_NAME);
			if (customName != null && expectedName.equals(customName.getString())) {
				return slot;
			}
		}
		return -1;
	}

	static void synthesizeSimpleBlueprintBundle(
		ServerLevel scratchLevel,
		Path bundleDir,
		String id,
		String displayName,
		int order
	) throws Exception {
		Files.createDirectories(bundleDir);
		BlockPos origin = new BlockPos(99_000 + order * 16, 120, 99_000);
		scratchLevel.setBlock(origin, Blocks.STONE.defaultBlockState(), 18);
		try {
			StructureTemplate template = new StructureTemplate();
			template.fillFromWorld(scratchLevel, origin, new Vec3i(1, 1, 1), false, List.of());
			NbtIo.writeCompressed(template.save(new CompoundTag()), bundleDir.resolve("overworld.nbt"));
		} finally {
			scratchLevel.setBlock(origin, Blocks.AIR.defaultBlockState(), 18);
		}
		Files.writeString(bundleDir.resolve("bundle.json"), """
			{
			  "id": "%s",
			  "displayName": "%s",
			  "description": "GameTest panel starter",
			  "icon": "minecraft:grass_block",
			  "order": %d,
			  "overworld": { "file": "overworld.nbt", "offsetY": 0 }
			}
			""".formatted(id, displayName, order));
	}

	static void clearBlockPosIfLoaded(ServerLevel level, BlockPos center) {
		for (int dx = -2; dx <= 2; dx++) {
			for (int dy = -1; dy <= 2; dy++) {
				for (int dz = -2; dz <= 2; dz++) {
					level.setBlock(center.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 818);
				}
			}
		}
	}

	static ServerPlayer createOnlineMockPlayer(
		MinecraftServer server,
		ServerLevel level,
		UUID uuid,
		String username,
		GameType mode
	) {
		return createOnlineMockPlayer(server, level, uuid, username, mode, null);
	}

	static ServerPlayer createOnlineMockPlayer(
		MinecraftServer server,
		ServerLevel level,
		UUID uuid,
		String username,
		GameType mode,
		AtomicReference<EmbeddedChannel> outChannel
	) {
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(uuid, username), false);
		ServerPlayer player = new ServerPlayer(server, level, cookie.gameProfile(), cookie.clientInformation()) {
			@Override
			public GameType gameMode() {
				return mode;
			}

			@Override
			public boolean isClientAuthoritative() {
				return false;
			}
		};
		mode.updatePlayerAbilities(player.getAbilities());
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		EmbeddedChannel channel = new EmbeddedChannel(connection);
		if (outChannel != null) {
			outChannel.set(channel);
		}
		server.getPlayerList().placeNewPlayer(connection, player, cookie);
		return player;
	}

	static List<Packet<?>> drainOutboundPackets(EmbeddedChannel channel) {
		channel.flush();
		channel.runPendingTasks();
		List<Packet<?>> drained = new ArrayList<>();
		Object msg;
		while ((msg = channel.readOutbound()) != null) {
			if (msg instanceof Packet<?> packet) {
				drained.add(packet);
			}
		}
		return drained;
	}

	static void closeContainersQuietly(ServerPlayer... players) {
		for (ServerPlayer player : players) {
			if (player == null) {
				continue;
			}
			try {
				player.closeContainer();
			} catch (Exception ignored) {
			}
		}
	}

	static void deleteAndCleanupIsland(
		MinecraftServer server,
		OgskyCoreApiImpl coreApi,
		CoreIslandService islands,
		UUID islandId,
		UUID actorId
	) {
		if (islandId == null) {
			return;
		}
		islands.grid().findById(islandId).ifPresent(found -> {
			int gx = found.gridX();
			int gz = found.gridZ();
			int minX = found.minX();
			int maxX = found.maxX();
			int minZ = found.minZ();
			int maxZ = found.maxZ();
			// Pass null server so teardown fires IslandDeletedEvent/DB cleanup without generating void chunks across 3 dimensions.
			coreApi.lifecycle().deleteIsland(null, islandId, actorId, IslandPreDeleteEvent.DeleteReason.ADMIN);
			clearLoadedBounds(server, minX, maxX, minZ, maxZ);
			cleanupTestIsland(coreApi, islands, islandId, gx, gz);
		});
	}

	/**
	 * Clears only already-loaded RAM chunks in an island's bounds so cell reuse never inherits stale blocks
	 * without generating or disk-loading unvisited Nether/End chunks.
	 */
	static void clearLoadedBounds(MinecraftServer server, int minX, int maxX, int minZ, int maxZ) {
		int minChunkX = SectionPos.blockToSectionCoord(minX);
		int maxChunkX = SectionPos.blockToSectionCoord(maxX);
		int minChunkZ = SectionPos.blockToSectionCoord(minZ);
		int maxChunkZ = SectionPos.blockToSectionCoord(maxZ);
		BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
		for (var dimension : OgskyDimensions.ALL) {
			ServerLevel level = server.getLevel(dimension);
			if (level == null) {
				continue;
			}
			for (int cx = minChunkX; cx <= maxChunkX; cx++) {
				for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
					LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
					if (chunk == null) {
						continue;
					}
					int chunkMinX = SectionPos.sectionToBlockCoord(cx);
					int chunkMinZ = SectionPos.sectionToBlockCoord(cz);
					int boxMinX = Math.max(minX, chunkMinX);
					int boxMaxX = Math.min(maxX, chunkMinX + 15);
					int boxMinZ = Math.max(minZ, chunkMinZ);
					int boxMaxZ = Math.min(maxZ, chunkMinZ + 15);
					AABB entityBox = new AABB(
						boxMinX, level.getMinY(), boxMinZ,
						boxMaxX + 1.0D, level.getMaxY() + 1.0D, boxMaxZ + 1.0D
					);
					for (Entity entity : level.getEntitiesOfClass(Entity.class, entityBox, candidate -> !(candidate instanceof Player))) {
						entity.discard();
					}
					LevelChunkSection[] sections = chunk.getSections();
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
							for (int worldX = boxMinX; worldX <= boxMaxX; worldX++) {
								int localX = worldX - chunkMinX;
								for (int worldZ = boxMinZ; worldZ <= boxMaxZ; worldZ++) {
									int localZ = worldZ - chunkMinZ;
									if (!section.getBlockState(localX, localY, localZ).isAir()) {
										mutablePos.set(worldX, worldY, worldZ);
										level.removeBlockEntity(mutablePos);
										level.setBlock(mutablePos, Blocks.AIR.defaultBlockState(), ChunkCleanupQueue.CLEAR_BLOCK_FLAGS);
									}
								}
							}
						}
					}
				}
			}
		}
	}

	static void cleanupTestIsland(
		OgskyCoreApiImpl coreApi,
		CoreIslandService islands,
		UUID islandId,
		int gridX,
		int gridZ
	) {
		islands.grid().completeCleanupCell(gridX, gridZ);
		islands.grid().unregister(islandId);
		coreApi.databases().find(CoreIslandRepository.MODULE_ID).ifPresent(db -> {
			CoreIslandRepository.deleteIslandAsync(db, islandId).join();
			CoreIslandRepository.deleteCleanupEntryAsync(db, gridX, gridZ).join();
		});
	}

	static void deleteDirectoryRecursively(Path root) {
		if (!Files.exists(root)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(root)) {
			walk.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (Exception ignored) {
				}
			});
		} catch (Exception ignored) {
		}
	}
}
