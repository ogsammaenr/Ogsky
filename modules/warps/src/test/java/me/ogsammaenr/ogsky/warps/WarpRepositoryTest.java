package me.ogsammaenr.ogsky.warps;

import me.ogsammaenr.ogsky.core.internal.OgskyCoreApiImpl;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarpRepositoryTest {
	@TempDir
	Path tempDir;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void persistsWarpsVisitsCachedLimitsAndReconcilesOrphans() {
		OgskyCoreApiImpl core = new OgskyCoreApiImpl();
		core.databases().registerModule(WarpRepository.MODULE_ID, WarpRepository.MIGRATIONS);
		core.sqliteDatabases().openAllForRoot(tempDir);

		try {
			WarpRepository repo = new WarpRepository(core.databases());
			UUID liveIsland = UUID.randomUUID();
			UUID orphanIsland = UUID.randomUUID();
			UUID creator = UUID.randomUUID();

			IslandWarp home = new IslandWarp(
				liveIsland,
				"home",
				"Home",
				Identifier.fromNamespaceAndPath("ogsky", "overworld"),
				1.5D, 65.5D, -2.25D,
				90.0F, 15.0F,
				"general",
				null,
				"Main entrance",
				true,
				true,
				0L,
				creator,
				100L,
				100L
			);
			IslandWarp shop = new IslandWarp(
				liveIsland,
				"shop",
				"Shop",
				Identifier.fromNamespaceAndPath("ogsky", "nether"),
				-4.0D, 70.0D, 8.0D,
				180.0F, 0.0F,
				"shop",
				"minecraft:emerald",
				"Buy items",
				true,
				false,
				2L,
				creator,
				200L,
				250L
			);
			IslandWarp orphan = new IslandWarp(
				orphanIsland,
				"old",
				"Old",
				Identifier.fromNamespaceAndPath("ogsky", "overworld"),
				0.0D, 64.0D, 0.0D,
				0.0F, 0.0F,
				"general",
				null,
				"",
				true,
				true,
				99L,
				creator,
				50L,
				50L
			);

			repo.upsertWarpAsync(home).join();
			repo.upsertWarpAsync(shop).join();
			repo.upsertWarpAsync(orphan).join();
			repo.incrementVisitCountAsync(liveIsland, "shop", 15L).join();
			repo.upsertCachedMaxWarpsAsync(liveIsland, 10).join();
			repo.upsertCachedMaxWarpsAsync(orphanIsland, 5).join();

			WarpRepository.LoadedState state = repo.loadAllBlocking();
			assertEquals(3, state.warps().size());
			assertEquals(2, state.cachedMaxWarps().size());

			repo.deleteIslandsAsync(Set.of(orphanIsland)).join();
			state = repo.loadAllBlocking();
			assertEquals(2, state.warps().size());
			assertEquals(Integer.valueOf(10), state.cachedMaxWarps().get(liveIsland));

			IslandWarp loadedHome = state.warps().stream().filter(w -> w.slug().equals("home")).findFirst().orElseThrow();
			IslandWarp loadedShop = state.warps().stream().filter(w -> w.slug().equals("shop")).findFirst().orElseThrow();
			assertNull(loadedHome.iconItemId());
			assertEquals(65.5D, loadedHome.y());
			assertEquals("minecraft:emerald", loadedShop.iconItemId());
			assertEquals(15L, loadedShop.visitCount());

			repo.deleteWarpAsync(liveIsland, "home").join();
			assertEquals(List.of("shop"), repo.loadAllBlocking().warps().stream().map(IslandWarp::slug).toList());

			repo.deleteIslandAsync(liveIsland).join();
			state = repo.loadAllBlocking();
			assertTrue(state.warps().isEmpty());
			assertTrue(state.cachedMaxWarps().isEmpty());
		} finally {
			core.sqliteDatabases().closeAll();
		}
	}
}
