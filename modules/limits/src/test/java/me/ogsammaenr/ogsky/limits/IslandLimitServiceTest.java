package me.ogsammaenr.ogsky.limits;

import me.ogsammaenr.ogsky.core.api.event.IslandDeletedEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandPreDeleteEvent;
import me.ogsammaenr.ogsky.core.api.island.IslandOperationResult;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import me.ogsammaenr.ogsky.core.api.level.IslandLevelProvider;
import me.ogsammaenr.ogsky.core.api.level.IslandLevelSnapshot;
import me.ogsammaenr.ogsky.core.internal.OgskyCoreApiImpl;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfig;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandRepository;
import me.ogsammaenr.ogsky.core.internal.island.Island;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IslandLimitServiceTest {
	@TempDir
	Path tempDir;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void persistsLimitsReconcilesOrphansAndDeletesOnIslandDelete() {
		OgskyCoreApiImpl core = new OgskyCoreApiImpl();
		core.databases().registerModule(LimitRepository.MODULE_ID, LimitRepository.MIGRATIONS);
		core.sqliteDatabases().openAllForRoot(tempDir);

		try {
			LimitRepository repo = new LimitRepository(core.databases());
			UUID liveIsland = UUID.randomUUID();
			UUID orphanIsland = UUID.randomUUID();

			repo.saveBatchAsync(List.of(
				new LimitRepository.PersistedIslandRecord(
					liveIsland,
					"hash123",
					1000L,
					List.of(
						new LimitRepository.PersistedCount("BLOCK", "minecraft:hopper", 12),
						new LimitRepository.PersistedCount("ENTITY", "group:farm_animals", 7)
					)
				),
				new LimitRepository.PersistedIslandRecord(
					orphanIsland,
					"hash123",
					1000L,
					List.of(new LimitRepository.PersistedCount("BLOCK", "minecraft:hopper", 99))
				)
			)).join();

			List<LimitRepository.PersistedIslandRecord> loaded = repo.loadAllBlocking();
			assertEquals(2, loaded.size());

			repo.reconcileOrphansAsync(Set.of(liveIsland));
			repo.flush().join();

			loaded = repo.loadAllBlocking();
			assertEquals(1, loaded.size());
			assertEquals(liveIsland, loaded.get(0).islandId());
			assertEquals(2, loaded.get(0).counts().size());

			repo.deleteIslandAsync(liveIsland).join();
			assertTrue(repo.loadAllBlocking().isEmpty());
		} finally {
			core.sqliteDatabases().closeAll();
		}
	}

	@Test
	void mergesConcurrentScanDeltasAndRemapsOnReload() {
		LimitConfig initialCfg = LimitConfig.defaults();
		LimitRuleTable oldTable = LimitRuleTable.compile(initialCfg, tag -> List.of(), tag -> List.of());

		IslandLimitState state = new IslandLimitState(UUID.randomUUID(), oldTable.blockRuleCount(), oldTable.entityRuleCount());
		int[] hopperIndices = oldTable.ruleIndicesFor(Blocks.HOPPER.defaultBlockState());
		int[] cowIndices = oldTable.ruleIndicesFor(EntityTypes.COW);

		long token = state.beginScan();
		// Concurrent placement and animal spawn while scan is running:
		state.addBlockDelta(hopperIndices, 3);
		state.addEntityDelta(cowIndices, 2);
		state.addEntityDelta(cowIndices, -1);

		int[] scannedBlocks = new int[oldTable.blockRuleCount()];
		scannedBlocks[hopperIndices[0]] = 10;
		int[] scannedEntities = new int[oldTable.entityRuleCount()];
		scannedEntities[cowIndices[0]] = 5;

		// Stale token must be ignored.
		state.finishScan(token + 99, scannedBlocks, scannedEntities, oldTable.rulesHash(), 1234L);
		assertTrue(state.scanning);

		state.finishScan(token, scannedBlocks, scannedEntities, oldTable.rulesHash(), 5000L);
		assertFalse(state.scanning);
		assertTrue(state.synchronizedFromWorld);
		assertEquals(13, state.blockCounts[hopperIndices[0]]);
		assertEquals(6, state.entityCounts[cowIndices[0]]);

		// Reload removing one unrelated rule must preserve hopper & cow counts and invalidate sync due to rulesHash change.
		LimitConfig reloadedCfg = LimitConfig.defaults();
		reloadedCfg.blockLimits.remove("minecraft:conduit");
		LimitRuleTable newTable = LimitRuleTable.compile(reloadedCfg, tag -> List.of(), tag -> List.of());
		state.remapFrom(oldTable, newTable);

		int[] newHopperIndices = newTable.ruleIndicesFor(Blocks.HOPPER.defaultBlockState());
		int[] newCowIndices = newTable.ruleIndicesFor(EntityTypes.COW);
		assertEquals(13, state.blockCounts[newHopperIndices[0]]);
		assertEquals(6, state.entityCounts[newCowIndices[0]]);
		assertFalse(state.synchronizedFromWorld);
	}

	@Test
	void enforcesViewLimitsFlagAndOptionalLevelScalingWithoutCoreDependency() throws Exception {
		Path blueprintsDir = tempDir.resolve("blueprints");
		Path defaultBundle = blueprintsDir.resolve("default");
		Files.createDirectories(defaultBundle);
		NbtIo.writeCompressed(minimalStructureTag(), defaultBundle.resolve("overworld.nbt"));

		OgskyCoreApiImpl core = new OgskyCoreApiImpl(CoreConfig.defaults(), blueprintsDir);
		core.sqliteDatabases().registerModule(CoreIslandRepository.MODULE_ID, CoreIslandRepository.MIGRATIONS);
		core.databases().registerModule(LimitRepository.MODULE_ID, LimitRepository.MIGRATIONS);

		LimitConfig config = LimitConfig.defaults();
		config.levelScaling.enabled = true;
		config.levelScaling.bonusPer100Levels = 0.5D;
		config.levelScaling.maxBonusMultiplier = 2.0D;
		LimitRuleTable table = LimitRuleTable.compile(config, tag -> List.of(), tag -> List.of());
		IslandLimitService service = new IslandLimitService(core, config, table, new LimitRepository(core.databases()));

		core.flags().register(service.createViewLimitsFlagDefinition());
		core.freezeRegistries();
		core.sqliteDatabases().openAllForRoot(tempDir.resolve("data"));
		core.coreIslands().loadFromDatabase();
		core.blueprints().onServerStarting();

		core.events().subscribe(IslandDeletedEvent.class, evt -> service.onIslandDeleted(evt.islandId()));

		try {
			UUID owner = UUID.randomUUID();
			UUID member = UUID.randomUUID();
			UUID coop = UUID.randomUUID();
			UUID visitor = UUID.randomUUID();

			IslandOperationResult<IslandView> created = core.islands().createIsland(null, null, owner, null);
			assertTrue(created.success(), created.message());
			UUID islandId = created.value().islandId();
			Island rawIsland = core.coreIslands().grid().findById(islandId).orElseThrow();

			assertTrue(core.team().inviteMember(rawIsland, owner, member).success());
			assertTrue(core.team().acceptInvite(member).success());
			assertTrue(core.team().addCoop(rawIsland, owner, coop, false).success());
			assertTrue(core.islands().banPlayer(null, islandId, owner, visitor, false).success());

			IslandView view = core.islands().findById(islandId).orElseThrow();
			assertTrue(service.canViewLimits(view, owner, false));
			assertTrue(service.canViewLimits(view, member, false));
			assertFalse(service.canViewLimits(view, coop, false));
			assertFalse(service.canViewLimits(view, visitor, false));
			assertTrue(service.canViewLimits(view, visitor, true));

			assertTrue(core.islands().setProtectionRank(owner, islandId, IslandLimitService.VIEW_LIMITS_FLAG, IslandRole.COOP, false).success());
			view = core.islands().findById(islandId).orElseThrow();
			assertTrue(service.canViewLimits(view, coop, false));
			// Banned player remains blocked unless admin bypass is active.
			assertFalse(service.canViewLimits(view, visitor, false));

			// Optional IslandLevelProvider integration scales limit without importing ogsky-level.
			LimitRuleTable.LimitRule hopperRule = table.blockRule(table.findBlockRuleIndex("minecraft:hopper"));
			assertEquals(64, service.effectiveLimit(islandId, hopperRule));

			IslandLevelProvider fakeLevel = new IslandLevelProvider() {
				@Override
				public Optional<IslandLevelSnapshot> findSnapshot(UUID id) {
					return Optional.of(new IslandLevelSnapshot(id, owner, 100L, 10_000L, 100L, 0L, Instant.now(), List.of()));
				}

				@Override
				public OptionalInt rankOf(UUID id) {
					return OptionalInt.of(1);
				}

				@Override
				public List<IslandLevelSnapshot> topIslands(int limit) {
					return List.of();
				}

				@Override
				public int rankedIslandCount() {
					return 1;
				}

				@Override
				public boolean isScanInProgress(UUID id) {
					return false;
				}
			};
			core.services().register(IslandLevelProvider.class, fakeLevel);
			assertEquals(96, service.effectiveLimit(islandId, hopperRule));

			// Test filter + sort helpers.
			List<IslandLimitService.RuleStatus> entityOnly = LimitHelper.filterAndSortStatuses(
				service.getRuleStatusesSnapshot(islandId),
				"entities",
				"farm",
				"name"
			);
			assertEquals(1, entityOnly.size());
			assertEquals("group:farm_animals", entityOnly.get(0).rule().key());

			assertTrue(core.lifecycle().deleteIsland(null, islandId, owner, IslandPreDeleteEvent.DeleteReason.PLAYER_DELETE).success());
		} finally {
			core.sqliteDatabases().closeAll();
		}
	}

	private static CompoundTag minimalStructureTag() {
		CompoundTag root = new CompoundTag();
		ListTag size = new ListTag();
		size.add(IntTag.valueOf(1));
		size.add(IntTag.valueOf(1));
		size.add(IntTag.valueOf(1));
		root.put("size", size);
		root.put("palette", new ListTag());
		root.put("blocks", new ListTag());
		root.put("entities", new ListTag());
		return root;
	}
}
