package me.ogsammaenr.ogsky.core;

import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.internal.OgskyCoreApiImpl;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfig;
import me.ogsammaenr.ogsky.core.internal.flag.BuiltinFlags;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandService;
import me.ogsammaenr.ogsky.core.internal.island.GridCleanupEntry;
import me.ogsammaenr.ogsky.core.internal.island.Island;
import me.ogsammaenr.ogsky.core.internal.island.IslandGrid;
import me.ogsammaenr.ogsky.core.internal.island.PackedGridMap;
import me.ogsammaenr.ogsky.core.internal.world.OgskyDimensions;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IslandGridAndModelTest {
	@Test
	void packedGridMapHandlesZeroAndNegativeGridCoordinates() {
		PackedGridMap<String> map = new PackedGridMap<>();
		for (int gx = -5; gx <= 5; gx++) {
			for (int gz = -5; gz <= 5; gz++) {
				map.put(PackedGridMap.pack(gx, gz), gx + "," + gz);
			}
		}
		assertEquals("0,0", map.get(PackedGridMap.pack(0, 0)));
		assertEquals("-5,-5", map.get(PackedGridMap.pack(-5, -5)));
		assertEquals("5,-3", map.get(PackedGridMap.pack(5, -3)));
		assertEquals(121, map.size());

		assertEquals("0,0", map.remove(PackedGridMap.pack(0, 0)));
		assertNull(map.get(PackedGridMap.pack(0, 0)));
		assertEquals(120, map.size());
	}

	@Test
	void spiralAllocatorAndSpatialLookupWorkAcrossPositiveAndNegativeCells() {
		CoreConfig config = CoreConfig.defaults();
		OgskyCoreApiImpl api = new OgskyCoreApiImpl(config);
		api.freezeRegistries();
		CoreIslandService service = api.coreIslands();
		IslandGrid grid = service.grid();

		// Cell (0,0) -> center (512, 512)
		long c0 = grid.allocateNextCell();
		assertEquals(0, PackedGridMap.unpackX(c0));
		assertEquals(0, PackedGridMap.unpackZ(c0));
		Island i0 = newIsland(api, config, UUID.randomUUID(), 0, 0, grid);
		grid.register(i0);

		// Mark (1,0) pending cleanup so spiral must skip it and pick (1,1)
		grid.addCleanupEntry(new GridCleanupEntry(1, 0, 1024, 2047, 0, 1023, 64, 0, Instant.now()));
		long c1 = grid.allocateNextCell();
		assertEquals(1, PackedGridMap.unpackX(c1));
		assertEquals(1, PackedGridMap.unpackZ(c1));

		// Register negative cell (-1, -1) -> center (-512, -512)
		Island neg = newIsland(api, config, UUID.randomUUID(), -1, -1, grid);
		grid.register(neg);

		// Lookup across all 3 Skyblock dimensions shares the exact same single Island
		assertEquals(i0.islandId(), service.findAt(OgskyDimensions.OVERWORLD, 512, 512).orElseThrow().islandId());
		assertEquals(i0.islandId(), service.findAt(OgskyDimensions.NETHER, 562, 562).orElseThrow().islandId());
		assertEquals(i0.islandId(), service.findAt(OgskyDimensions.END, 462, 462).orElseThrow().islandId());

		// Outside halfSize (50) inside cell (0,0) -> wilderness buffer
		assertTrue(service.findAt(OgskyDimensions.OVERWORLD, 563, 512).isEmpty());
		// Non-Skyblock vanilla dimension -> empty (avoid Level.<clinit> in unbootstrapped JUnit)
		ResourceKey<Level> vanillaOverworld = ResourceKey.create(
			Registries.DIMENSION,
			Identifier.withDefaultNamespace("overworld")
		);
		assertTrue(service.findAt(vanillaOverworld, 512, 512).isEmpty());
		// Negative cell lookup
		assertEquals(neg.islandId(), service.findAt(OgskyDimensions.OVERWORLD, -512, -512).orElseThrow().islandId());
	}

	@Test
	void enforcesPrimaryAndSecondaryMembershipLimitsAndProtectionThresholds() {
		CoreConfig config = CoreConfig.defaults(); // maxCoop=1, maxTrusted=2
		OgskyCoreApiImpl api = new OgskyCoreApiImpl(config);
		api.freezeRegistries();
		CoreIslandService service = api.coreIslands();
		IslandGrid grid = service.grid();

		UUID owner1 = UUID.randomUUID();
		UUID owner2 = UUID.randomUUID();
		UUID owner3 = UUID.randomUUID();
		UUID helper = UUID.randomUUID();

		Island island1 = newIsland(api, config, owner1, 0, 0, grid);
		Island island2 = newIsland(api, config, owner2, 1, 0, grid);
		Island island3 = newIsland(api, config, owner3, 0, 1, grid);
		grid.register(island1);
		grid.register(island2);
		grid.register(island3);

		// Helper gets 1 primary island (island1 as MEMBER)
		assertTrue(grid.canJoinOrSetRole(island1, helper, IslandRole.MEMBER));
		grid.assignMemberRole(island1, helper, IslandRole.MEMBER);
		assertEquals(island1.islandId(), service.findPrimaryIsland(helper).orElseThrow().islandId());

		// Helper CANNOT join a second primary island
		assertFalse(grid.canJoinOrSetRole(island2, helper, IslandRole.MEMBER));
		assertThrows(IllegalStateException.class, () -> grid.assignMemberRole(island2, helper, IslandRole.ADMIN));

		// Helper CAN hold 1 COOP and 2 TRUSTED on other islands
		grid.assignMemberRole(island2, helper, IslandRole.COOP);
		assertFalse(grid.canJoinOrSetRole(island3, helper, IslandRole.COOP)); // cap=1 reached
		grid.assignMemberRole(island3, helper, IslandRole.TRUSTED);

		UUID owner4 = UUID.randomUUID();
		Island island4 = newIsland(api, config, owner4, -1, 0, grid);
		grid.register(island4);
		grid.assignMemberRole(island4, helper, IslandRole.TRUSTED); // 2nd TRUSTED allowed

		assertEquals(3, service.findSecondaryIslands(helper).size());

		// Protection & ban checks
		assertTrue(service.isProtectionAllowed(OgskyDimensions.OVERWORLD, 512, 512, helper, BuiltinFlags.BREAK_BLOCKS, false));
		UUID visitor = UUID.randomUUID();
		assertFalse(service.isProtectionAllowed(OgskyDimensions.OVERWORLD, 512, 512, visitor, BuiltinFlags.BREAK_BLOCKS, false));
		assertTrue(service.isProtectionAllowed(OgskyDimensions.OVERWORLD, 512, 512, visitor, BuiltinFlags.BREAK_BLOCKS, true));

		// Banning even a member blocks protection checks
		island1.addBan(helper);
		assertFalse(service.isProtectionAllowed(OgskyDimensions.OVERWORLD, 512, 512, helper, BuiltinFlags.BREAK_BLOCKS, false));

		// DISABLED (6) locks out owner unless adminBypass=true
		int breakSlot = api.flags().slotOf(BuiltinFlags.BREAK_BLOCKS);
		island1.setProtectionRankBySlot(breakSlot, IslandRole.DISABLED);
		assertFalse(service.isProtectionAllowed(OgskyDimensions.OVERWORLD, 512, 512, owner1, BuiltinFlags.BREAK_BLOCKS, false));
		assertTrue(service.isProtectionAllowed(OgskyDimensions.OVERWORLD, 512, 512, owner1, BuiltinFlags.BREAK_BLOCKS, true));

		// Owner cannot be demoted before transfer
		assertThrows(IllegalStateException.class, () -> grid.assignMemberRole(island1, owner1, IslandRole.ADMIN));
		// Transferring ownership first succeeds
		grid.assignMemberRole(island1, helper, IslandRole.OWNER);
		grid.assignMemberRole(island1, owner1, IslandRole.ADMIN);
		assertEquals(helper, island1.ownerId());
		assertEquals(IslandRole.ADMIN, island1.roleOf(owner1));
		assertEquals(List.of(helper), island1.bannedPlayers());
	}

	private static Island newIsland(
		OgskyCoreApiImpl api,
		CoreConfig config,
		UUID ownerId,
		int gridX,
		int gridZ,
		IslandGrid grid
	) {
		return new Island(
			UUID.randomUUID(),
			ownerId,
			gridX,
			gridZ,
			grid.centerCoordinate(gridX),
			grid.centerCoordinate(gridZ),
			(short) config.island.initialHalfSize,
			config.blueprints.defaultBundle,
			Instant.now(),
			api.flags(),
			config
		);
	}
}
