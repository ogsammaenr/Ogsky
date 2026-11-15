package me.ogsammaenr.ogsky.core;

import me.ogsammaenr.ogsky.core.api.db.ModuleDatabase;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import me.ogsammaenr.ogsky.core.api.island.RelativeSpawn;
import me.ogsammaenr.ogsky.core.internal.OgskyCoreApiImpl;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfig;
import me.ogsammaenr.ogsky.core.internal.flag.BuiltinFlags;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandRepository;
import me.ogsammaenr.ogsky.core.internal.island.GridCleanupEntry;
import me.ogsammaenr.ogsky.core.internal.island.Island;
import me.ogsammaenr.ogsky.core.internal.world.OgskyDimensions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreIslandRepositoryTest {
	@TempDir
	Path tempDir;

	@Test
	void persistsIslandsFlagsMembersBansAndEnforcesCellSizeWorldLock() {
		CoreConfig config = CoreConfig.defaults();
		UUID islandId = UUID.randomUUID();
		UUID ownerId = UUID.randomUUID();
		UUID memberId = UUID.randomUUID();
		UUID bannedId = UUID.randomUUID();

		OgskyCoreApiImpl first = new OgskyCoreApiImpl(config);
		first.databases().registerModule(CoreIslandRepository.MODULE_ID, CoreIslandRepository.MIGRATIONS);
		first.freezeRegistries();
		first.sqliteDatabases().openAllForRoot(tempDir);
		try {
			first.coreIslands().loadFromDatabase();
			assertFalse(first.coreIslands().isCellSizeLocked());

			Island island = new Island(
				islandId,
				ownerId,
				0,
				0,
				first.coreIslands().grid().centerCoordinate(0),
				first.coreIslands().grid().centerCoordinate(0),
				(short) 75,
				"default",
				Instant.now(),
				first.flags(),
				config
			);
			island.setStateBit(Island.STATE_OVERWORLD_READY, true);
			island.setOverworldSpawn(new RelativeSpawn((short) 2, (short) 70, (short) -3, (byte) 64, (byte) 0));
			island.setProtectionRankBySlot(first.flags().slotOf(BuiltinFlags.BREAK_BLOCKS), IslandRole.COOP);
			island.setSettingEnabledBySlot(first.flags().slotOf(BuiltinFlags.PVP_OVERWORLD), true);
			first.coreIslands().grid().register(island);
			first.coreIslands().grid().assignMemberRole(island, memberId, IslandRole.MEMBER);
			island.addBan(bannedId);
			first.coreIslands().markCellSizeLocked();
			first.coreIslands().setWorldSetting(BuiltinFlags.TNT_DAMAGE, true);

			ModuleDatabase db = first.databases().require(CoreIslandRepository.MODULE_ID);
			CoreIslandRepository.upsertIslandAsync(db, island).join();
			CoreIslandRepository.upsertMemberAsync(db, islandId, ownerId, IslandRole.OWNER).join();
			CoreIslandRepository.upsertMemberAsync(db, islandId, memberId, IslandRole.MEMBER).join();
			CoreIslandRepository.upsertBanAsync(db, islandId, bannedId, ownerId).join();
			CoreIslandRepository.saveProtectionFlagAsync(db, islandId, BuiltinFlags.BREAK_BLOCKS, IslandRole.COOP).join();
			CoreIslandRepository.saveSettingFlagAsync(db, islandId, BuiltinFlags.PVP_OVERWORLD, true).join();
			CoreIslandRepository.upsertCleanupEntryAsync(
				db,
				new GridCleanupEntry(2, 2, 2048, 3071, 2048, 3071, 128, 128, Instant.now())
			).join();
			db.flush().join();
		} finally {
			first.sqliteDatabases().closeAll();
		}

		// Reload with mismatched cellSize -> must fail loudly
		CoreConfig mismatched = CoreConfig.defaults();
		mismatched.grid.cellSize = 512;
		mismatched.island.maxHalfSize = 200;
		OgskyCoreApiImpl wrongBoot = new OgskyCoreApiImpl(mismatched);
		wrongBoot.databases().registerModule(CoreIslandRepository.MODULE_ID, CoreIslandRepository.MIGRATIONS);
		wrongBoot.freezeRegistries();
		wrongBoot.sqliteDatabases().openAllForRoot(tempDir);
		try {
			assertThrows(IllegalStateException.class, () -> wrongBoot.coreIslands().loadFromDatabase());
		} finally {
			wrongBoot.sqliteDatabases().closeAll();
		}

		// Reload with matching cellSize -> restores complete state into RAM
		OgskyCoreApiImpl second = new OgskyCoreApiImpl(config);
		second.databases().registerModule(CoreIslandRepository.MODULE_ID, CoreIslandRepository.MIGRATIONS);
		second.freezeRegistries();
		second.sqliteDatabases().openAllForRoot(tempDir);
		try {
			second.coreIslands().loadFromDatabase();
			assertTrue(second.coreIslands().isCellSizeLocked());
			assertEquals(1, second.coreIslands().islandCount());
			assertTrue(second.islands().isWorldSettingEnabled(BuiltinFlags.TNT_DAMAGE));

			IslandView loaded = second.islands().findById(islandId).orElseThrow();
			assertEquals(ownerId, loaded.ownerId());
			assertEquals((short) 75, loaded.halfSize());
			assertTrue(loaded.isOverworldReady());
			assertFalse(loaded.isNetherReady());
			assertEquals((short) 2, loaded.overworldSpawn().relX());
			assertEquals(IslandRole.MEMBER, loaded.roleOf(memberId));
			assertTrue(loaded.isBanned(bannedId));
			assertEquals(IslandRole.COOP, loaded.protectionRank(BuiltinFlags.BREAK_BLOCKS));
			assertTrue(loaded.isSettingEnabled(BuiltinFlags.PVP_OVERWORLD));
			assertEquals(1, second.coreIslands().grid().pendingCleanups().size());
			assertEquals(
				islandId,
				second.islands().findAt(OgskyDimensions.OVERWORLD, 512, 512).orElseThrow().islandId()
			);
		} finally {
			second.sqliteDatabases().closeAll();
		}
	}
}
