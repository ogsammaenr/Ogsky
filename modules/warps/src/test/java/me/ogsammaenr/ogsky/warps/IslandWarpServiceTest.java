package me.ogsammaenr.ogsky.warps;

import me.ogsammaenr.ogsky.core.api.event.IslandDeletedEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandPreDeleteEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandResetEvent;
import me.ogsammaenr.ogsky.core.api.event.TeamOwnerChangeEvent;
import me.ogsammaenr.ogsky.core.api.island.IslandOperationResult;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import me.ogsammaenr.ogsky.core.internal.OgskyCoreApiImpl;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfig;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandRepository;
import me.ogsammaenr.ogsky.core.internal.island.Island;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IslandWarpServiceTest {
	@TempDir
	Path tempDir;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void enforcesWarpFlagsVisitAntiSpamSortingAndLifecycleCleanup() throws Exception {
		Path blueprintsDir = createDummyBlueprintBundle(tempDir.resolve("blueprints"));
		Path configPath = tempDir.resolve("config").resolve("ogsky").resolve("warps.json");
		WarpConfig cfg = WarpConfig.defaults();
		cfg.defaultMaxWarpsPerIsland = 2;
		cfg.visitCountCooldownSeconds = 3600;
		cfg.countIslandMembersInVisits = false;
		WarpConfigLoader.save(configPath, cfg);

		OgskyCoreApiImpl core = new OgskyCoreApiImpl(CoreConfig.defaults(), blueprintsDir);
		core.databases().registerModule(CoreIslandRepository.MODULE_ID, CoreIslandRepository.MIGRATIONS);
		core.databases().registerModule(WarpRepository.MODULE_ID, WarpRepository.MIGRATIONS);

		WarpRepository repository = new WarpRepository(core.databases());
		IslandWarpService service = new IslandWarpService(core, configPath, repository);
		core.flags().register(service.createManageWarpsFlagDefinition());
		core.flags().register(service.createUseWarpFlagDefinition());
		core.events().subscribe(IslandDeletedEvent.class, e -> service.onIslandDeleted(e.islandId()));
		core.events().subscribe(IslandResetEvent.class, e -> {
			if (!e.isCancelled()) {
				service.onIslandReset(e.islandId());
			}
		});
		core.events().subscribe(TeamOwnerChangeEvent.class, e -> {
			if (!e.isCancelled()) {
				service.onTeamOwnerChanged(null, e.islandId(), e.newOwnerId());
			}
		});
		core.freezeRegistries();

		core.sqliteDatabases().openAllForRoot(tempDir.resolve("db"));
		core.coreIslands().loadFromDatabase();
		core.blueprints().onServerStarting();

		try {
			UUID owner = UUID.randomUUID();
			UUID member = UUID.randomUUID();
			UUID visitor = UUID.randomUUID();
			IslandOperationResult<IslandView> createdRes = core.islands().createIsland(null, null, owner, null);
			assertTrue(createdRes.success(), createdRes.message());
			UUID islandId = createdRes.value().islandId();
			Island created = core.coreIslands().grid().findById(islandId).orElseThrow();
			assertTrue(core.team().inviteMember(created, owner, member).success());
			assertTrue(core.team().acceptInvite(member).success());

			assertEquals(2, service.resolveMaxWarps(null, created));

			// Default flag ranks: manage_warps=ADMIN, use_warp=VISITOR
			assertTrue(service.canManageWarps(created, owner, false));
			assertFalse(service.canManageWarps(created, member, false));
			assertTrue(service.canUseWarp(created, visitor, false));

			// Configurable per island via standard IslandService flag API
			assertTrue(core.islands().setProtectionRank(owner, islandId, IslandWarpService.MANAGE_WARPS_FLAG, IslandRole.MEMBER, false).success());
			assertTrue(service.canManageWarps(created, member, false));

			assertTrue(core.islands().setProtectionRank(owner, islandId, IslandWarpService.USE_WARP_FLAG, IslandRole.MEMBER, false).success());
			assertFalse(service.canUseWarp(created, visitor, false));
			assertTrue(service.canUseWarp(created, visitor, true));

			// Ban overrides even if use_warp is VISITOR
			assertTrue(core.islands().setProtectionRank(owner, islandId, IslandWarpService.USE_WARP_FLAG, IslandRole.VISITOR, false).success());
			assertTrue(core.islands().banPlayer(null, islandId, owner, visitor, false).success());
			assertFalse(service.canUseWarp(created, visitor, false));
			assertFalse(service.canManageWarps(created, visitor, false));

			// Seed orphan and live warps directly in DB, reload, verify orphan cleanup + default promotion
			UUID deadIsland = UUID.randomUUID();
			IslandWarp liveWarp = new IslandWarp(
				islandId, "shop", "Shop",
				Identifier.fromNamespaceAndPath("ogsky", "overworld"),
				2.0D, 65.0D, 3.0D, 0.0F, 0.0F,
				"shop", "minecraft:emerald", "Open shop",
				true, false, 0L, owner, 100L, 100L
			);
			IslandWarp orphanWarp = new IslandWarp(
				deadIsland, "ghost", "Ghost",
				Identifier.fromNamespaceAndPath("ogsky", "overworld"),
				0.0D, 65.0D, 0.0D, 0.0F, 0.0F,
				"general", null, "", true, true, 5L, owner, 50L, 50L
			);
			repository.upsertWarpAsync(liveWarp).join();
			repository.upsertWarpAsync(orphanWarp).join();

			service.loadFromDatabaseAndReconcile();
			core.databases().require(WarpRepository.MODULE_ID).flush().join();

			List<IslandWarp> liveWarps = service.getIslandWarps(islandId);
			assertEquals(1, liveWarps.size());
			assertTrue(liveWarps.getFirst().isDefault(), "Single surviving warp should auto-promote to default");
			assertEquals(1, repository.loadAllBlocking().warps().size());

			// Popularity counter anti-abuse: owner & member do NOT increment; same visitor within cooldown increments once
			assertFalse(service.recordSuccessfulVisit(created, liveWarps.getFirst(), owner));
			assertFalse(service.recordSuccessfulVisit(created, liveWarps.getFirst(), member));
			assertTrue(service.recordSuccessfulVisit(created, liveWarps.getFirst(), visitor));
			assertFalse(service.recordSuccessfulVisit(created, liveWarps.getFirst(), visitor));
			assertEquals(1L, service.findWarp(islandId, "shop").orElseThrow().visitCount());

			// Showcase sort comparators (recent / popular / level / alpha)
			IslandWarp wAlpha = new IslandWarp(
				islandId, "arena", "Arena",
				Identifier.fromNamespaceAndPath("ogsky", "overworld"),
				0, 65, 0, 0, 0, "arena", null, "", true, false, 10L, owner, 100L, 500L
			);
			IslandWarp wBeta = new IslandWarp(
				islandId, "zoo", "Zoo",
				Identifier.fromNamespaceAndPath("ogsky", "overworld"),
				0, 65, 0, 0, 0, "general", null, "", true, false, 50L, owner, 200L, 200L
			);
			List<WarpHelper.ShowcaseEntry> entries = new ArrayList<>(List.of(
				new WarpHelper.ShowcaseEntry(created, wBeta, "PlayerZ", 10L),
				new WarpHelper.ShowcaseEntry(created, wAlpha, "PlayerA", 200L)
			));
			entries.sort(WarpHelper.showcaseComparator("recent"));
			assertEquals("Arena", entries.getFirst().warp().displayName());
			entries.sort(WarpHelper.showcaseComparator("popular"));
			assertEquals("Zoo", entries.getFirst().warp().displayName());
			entries.sort(WarpHelper.showcaseComparator("level"));
			assertEquals("Arena", entries.getFirst().warp().displayName());
			entries.sort(WarpHelper.showcaseComparator("alpha"));
			assertEquals("Arena", entries.getFirst().warp().displayName());

			// Validation helpers
			assertNull(WarpHelper.validateDisplayName("all", 24));
			assertNull(WarpHelper.validateDisplayName("off", 24));
			assertNull(WarpHelper.validateDisplayName("bad space", 24));
			assertEquals("Shop_1", WarpHelper.validateDisplayName("Shop_1", 24));
			assertTrue(WarpHelper.validateIconItemId("emerald", cfg).isPresent());
			assertFalse(WarpHelper.validateIconItemId("barrier", cfg).isPresent());
			assertFalse(WarpHelper.validateIconItemId("air", cfg).isPresent());

			// Island reset clears warps
			assertTrue(core.events().publishCancellable(new IslandResetEvent(islandId, owner, "default")));
			assertTrue(service.getIslandWarps(islandId).isEmpty());

			// Island deletion cleans up DB rows
			repository.upsertWarpAsync(liveWarp).join();
			service.loadFromDatabaseAndReconcile();
			assertTrue(core.lifecycle().deleteIsland(null, islandId, owner, IslandPreDeleteEvent.DeleteReason.PLAYER_DELETE).success());
			core.databases().require(WarpRepository.MODULE_ID).flush().join();
			assertTrue(service.getIslandWarps(islandId).isEmpty());
			assertTrue(repository.loadAllBlocking().warps().isEmpty());
		} finally {
			core.sqliteDatabases().closeAll();
		}
	}

	private static Path createDummyBlueprintBundle(Path blueprintsRoot) throws Exception {
		Path bundleDir = blueprintsRoot.resolve("default");
		Files.createDirectories(bundleDir);
		CompoundTag root = new CompoundTag();
		ListTag size = new ListTag();
		size.add(IntTag.valueOf(1));
		size.add(IntTag.valueOf(1));
		size.add(IntTag.valueOf(1));
		root.put("size", size);
		root.put("palette", new ListTag());
		root.put("blocks", new ListTag());
		root.put("entities", new ListTag());
		NbtIo.writeCompressed(root, bundleDir.resolve("overworld.nbt"));
		Files.writeString(bundleDir.resolve("bundle.json"), """
			{
			  "id": "default",
			  "displayName": "Default",
			  "overworld": { "file": "overworld.nbt" }
			}
			""");
		return blueprintsRoot;
	}
}
