package me.ogsammaenr.ogsky.level;

import me.ogsammaenr.ogsky.core.api.event.IslandDeletedEvent;
import me.ogsammaenr.ogsky.core.api.island.IslandOperationResult;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import me.ogsammaenr.ogsky.core.api.level.BlockPointBreakdown;
import me.ogsammaenr.ogsky.core.internal.OgskyCoreApiImpl;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfig;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandRepository;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IslandLevelServiceTest {
	@TempDir
	Path tempDir;

	@Test
	void savesQueriesAndCleansUpOnIslandDeletedEvent() {
		OgskyCoreApiImpl core = new OgskyCoreApiImpl();
		core.databases().registerModule(IslandLevelService.MODULE_DB_ID, IslandLevelService.MIGRATIONS);
		core.sqliteDatabases().openAllForRoot(tempDir);

		try {
			IslandLevelService service = new IslandLevelService(core);
			core.events().subscribe(
				IslandDeletedEvent.class,
				event -> service.deleteIslandLevel(event.islandId()).join()
			);

			UUID islandId = UUID.randomUUID();
			service.saveLevel(islandId, 15L, 1500L).join();
			assertEquals(Optional.of(15L), service.findLevel(islandId).join());

			core.events().publish(new IslandDeletedEvent(islandId));
			assertTrue(service.findLevel(islandId).join().isEmpty());
		} finally {
			core.sqliteDatabases().closeAll();
		}
	}

	@Test
	void reconcilesOrphansRanksLiveIslandsAndResetsCleanly() throws Exception {
		Path blueprintsDir = tempDir.resolve("blueprints");
		Path defaultBundle = blueprintsDir.resolve("default");
		Files.createDirectories(defaultBundle);
		NbtIo.writeCompressed(minimalStructureTag(), defaultBundle.resolve("overworld.nbt"));

		OgskyCoreApiImpl core = new OgskyCoreApiImpl(CoreConfig.defaults(), blueprintsDir);
		core.sqliteDatabases().registerModule(CoreIslandRepository.MODULE_ID, CoreIslandRepository.MIGRATIONS);
		core.databases().registerModule(IslandLevelService.MODULE_DB_ID, IslandLevelService.MIGRATIONS);
		core.freezeRegistries();
		core.sqliteDatabases().openAllForRoot(tempDir.resolve("data"));
		core.coreIslands().loadFromDatabase();
		core.blueprints().onServerStarting();

		try {
			IslandLevelService service = new IslandLevelService(core);
			IslandOperationResult<IslandView> first = core.islands().createIsland(null, null, UUID.randomUUID(), null);
			IslandOperationResult<IslandView> second = core.islands().createIsland(null, null, UUID.randomUUID(), null);
			assertTrue(first.success(), first.message());
			assertTrue(second.success(), second.message());

			UUID firstId = first.value().islandId();
			UUID secondId = second.value().islandId();
			UUID orphanId = UUID.randomUUID();

			// Persist an orphan record to DB to prove reconcileLoadedRows prunes it.
			service.saveLevel(orphanId, 99L, 9900L).join();
			assertEquals(Optional.of(99L), service.findLevel(orphanId).join());

			List<BlockPointBreakdown> breakdown = List.of(
				new BlockPointBreakdown(Identifier.parse("minecraft:diamond_block"), 3, 3, 300L)
			);
			int pruned = service.reconcileLoadedRows(List.of(
				new IslandLevelService.StoredLevelRow(firstId, 3L, 300L, Instant.now(), breakdown),
				new IslandLevelService.StoredLevelRow(secondId, 5L, 520L, Instant.now(), breakdown),
				new IslandLevelService.StoredLevelRow(orphanId, 99L, 9900L, Instant.now(), breakdown)
			));

			assertEquals(1, pruned);
			assertEquals(2, service.rankedIslandCount());
			assertEquals(OptionalInt.of(1), service.rankOf(secondId));
			assertEquals(OptionalInt.of(2), service.rankOf(firstId));
			assertEquals(1, service.findSnapshot(firstId).orElseThrow().topBlocks().size());

			core.databases().require(IslandLevelService.MODULE_DB_ID).flush().join();
			assertTrue(service.findLevel(orphanId).join().isEmpty());

			service.onIslandReset(secondId);
			assertTrue(service.findSnapshot(secondId).isEmpty());
			assertEquals(OptionalInt.of(1), service.rankOf(firstId));
		} finally {
			core.sqliteDatabases().closeAll();
		}
	}

	private static CompoundTag minimalStructureTag() {
		CompoundTag tag = new CompoundTag();
		ListTag size = new ListTag();
		size.add(IntTag.valueOf(1));
		size.add(IntTag.valueOf(1));
		size.add(IntTag.valueOf(1));
		tag.put("size", size);
		tag.put("palette", new ListTag());
		tag.put("blocks", new ListTag());
		tag.put("entities", new ListTag());
		return tag;
	}
}
