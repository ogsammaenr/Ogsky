package me.ogsammaenr.ogsky.core;

import me.ogsammaenr.ogsky.core.api.island.IslandOperationResult;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import me.ogsammaenr.ogsky.core.api.scan.IslandScanOptions;
import me.ogsammaenr.ogsky.core.api.world.OgskyDimensionKeys;
import me.ogsammaenr.ogsky.core.internal.OgskyCoreApiImpl;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfig;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandRepository;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreIslandScanOptionsTest {
	@TempDir
	Path tempDir;

	@Test
	void exposesDimensionKeysAndValidatesScanOptions() {
		assertEquals(3, OgskyDimensionKeys.ALL.size());
		assertTrue(OgskyDimensionKeys.isSkyblockDimension(OgskyDimensionKeys.OVERWORLD));
		assertTrue(OgskyDimensionKeys.isSkyblockDimension(OgskyDimensionKeys.NETHER));
		assertTrue(OgskyDimensionKeys.isSkyblockDimension(OgskyDimensionKeys.END));
		assertEquals("overworld", OgskyDimensionKeys.dimensionName(OgskyDimensionKeys.OVERWORLD));

		IslandScanOptions defaults = IslandScanOptions.defaults();
		assertTrue(defaults.chunksPerTick() >= 1);
		assertTrue(defaults.includeUnloadedChunks());

		assertThrows(IllegalArgumentException.class, () -> new IslandScanOptions(0, 4_000_000L, true, 8));
		assertThrows(IllegalArgumentException.class, () -> new IslandScanOptions(8, 1_000L, true, 8));
		assertThrows(IllegalArgumentException.class, () -> new IslandScanOptions(8, 4_000_000L, true, 0));
	}

	@Test
	void islandServiceAllIslandsTracksLiveRegistry() throws Exception {
		Path blueprintsDir = tempDir.resolve("blueprints");
		Path defaultBundle = blueprintsDir.resolve("default");
		Files.createDirectories(defaultBundle);
		NbtIo.writeCompressed(minimalStructureTag(), defaultBundle.resolve("overworld.nbt"));

		OgskyCoreApiImpl core = new OgskyCoreApiImpl(CoreConfig.defaults(), blueprintsDir);
		core.sqliteDatabases().registerModule(CoreIslandRepository.MODULE_ID, CoreIslandRepository.MIGRATIONS);
		core.freezeRegistries();
		core.sqliteDatabases().openAllForRoot(tempDir.resolve("data"));
		core.coreIslands().loadFromDatabase();
		core.blueprints().onServerStarting();

		try {
			assertTrue(core.islands().allIslands().isEmpty());

			IslandOperationResult<IslandView> res = core.islands().createIsland(null, null, UUID.randomUUID(), null);
			assertTrue(res.success(), res.message());

			List<IslandView> all = core.islands().allIslands();
			assertEquals(1, all.size());
			assertEquals(res.value().islandId(), all.getFirst().islandId());
			assertEquals(0, core.scanService().activeJobCount());
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
