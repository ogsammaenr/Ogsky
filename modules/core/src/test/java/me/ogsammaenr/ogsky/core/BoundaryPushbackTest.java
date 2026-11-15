package me.ogsammaenr.ogsky.core;

import me.ogsammaenr.ogsky.core.internal.OgskyCoreApiImpl;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfig;
import me.ogsammaenr.ogsky.core.internal.island.Island;
import me.ogsammaenr.ogsky.core.internal.protection.BoundaryPushback;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundaryPushbackTest {
	private static Island createTestIsland() {
		CoreConfig config = CoreConfig.defaults();
		OgskyCoreApiImpl api = new OgskyCoreApiImpl(config);
		api.freezeRegistries();
		return new Island(
			UUID.randomUUID(),
			UUID.randomUUID(),
			0,
			0,
			512,
			512,
			(short) 50,
			"default",
			Instant.now(),
			api.flags(),
			config
		);
	}

	@Test
	void cardinalAndDiagonalApproachesPushOutsideWithGentleKnockback() {
		Island island = createTestIsland(); // [462..562] x [462..562]

		// West wall approach (standing near x=462 edge)
		var west = BoundaryPushback.compute(island, new Vec3(461.9D, 65.0D, 512.5D), true);
		assertNotNull(west);
		assertFalse(island.contains(Mth.floor(west.position().x), Mth.floor(west.position().z)));
		assertTrue(west.position().x <= island.minX() - BoundaryPushback.BORDER_OFFSET + 1.0E-6D);
		assertEquals(65.0D, west.position().y, 1.0E-6D);
		assertEquals(-BoundaryPushback.HORIZONTAL_KNOCKBACK, west.knockback().x, 1.0E-6D);
		assertEquals(BoundaryPushback.VERTICAL_KNOCKBACK, west.knockback().y, 1.0E-6D);
		assertEquals(0.0D, west.knockback().z, 1.0E-6D);

		// East wall exact boundary (maxX + 1.0 = 563.0 is outside)
		var east = BoundaryPushback.compute(island, new Vec3(563.0D, 70.0D, 512.5D), false);
		assertNotNull(east);
		assertFalse(island.contains(Mth.floor(east.position().x), Mth.floor(east.position().z)));
		assertTrue(east.position().x >= (island.maxX() + 1.0D) + BoundaryPushback.BORDER_OFFSET - 1.0E-6D);
		assertEquals(BoundaryPushback.HORIZONTAL_KNOCKBACK, east.knockback().x, 1.0E-6D);
		assertEquals(0.0D, east.knockback().y, 1.0E-6D);

		// North & South walls
		var north = BoundaryPushback.compute(island, new Vec3(512.5D, 64.0D, 461.8D), true);
		assertNotNull(north);
		assertFalse(island.contains(Mth.floor(north.position().x), Mth.floor(north.position().z)));
		assertTrue(north.knockback().z < 0.0D);

		var south = BoundaryPushback.compute(island, new Vec3(512.5D, 64.0D, 563.2D), true);
		assertNotNull(south);
		assertFalse(island.contains(Mth.floor(south.position().x), Mth.floor(south.position().z)));
		assertTrue(south.knockback().z > 0.0D);

		// Diagonal NW corner
		var corner = BoundaryPushback.compute(island, new Vec3(461.8D, 64.0D, 461.8D), true);
		assertNotNull(corner);
		assertFalse(island.contains(Mth.floor(corner.position().x), Mth.floor(corner.position().z)));
		assertTrue(corner.knockback().x < 0.0D && corner.knockback().z < 0.0D);
	}

	@Test
	void insideOrFarOutsidePositionsReturnNullForEvictionFallback() {
		Island island = createTestIsland();

		assertNull(BoundaryPushback.compute(island, new Vec3(512.5D, 64.0D, 512.5D), true));
		assertNull(BoundaryPushback.compute(island, new Vec3(400.0D, 64.0D, 512.5D), true));
	}
}
