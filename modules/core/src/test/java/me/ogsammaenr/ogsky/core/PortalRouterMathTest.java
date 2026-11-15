package me.ogsammaenr.ogsky.core;

import me.ogsammaenr.ogsky.core.internal.config.CoreConfig;
import me.ogsammaenr.ogsky.core.internal.flag.BuiltinFlags;
import me.ogsammaenr.ogsky.core.internal.flag.SimpleFlagRegistry;
import me.ogsammaenr.ogsky.core.internal.island.Island;
import me.ogsammaenr.ogsky.core.internal.world.OgskyDimensions;
import me.ogsammaenr.ogsky.core.internal.world.PortalRouter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalRouterMathTest {
	@Test
	void skyblockDimensionsAndCounterpartsResolveOneToOne() {
		ResourceKey<Level> vanillaOverworld = ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace("overworld"));
		ResourceKey<Level> vanillaNether = ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace("the_nether"));

		assertTrue(OgskyDimensions.isSkyblockDimension(OgskyDimensions.OVERWORLD));
		assertTrue(OgskyDimensions.isSkyblockDimension(OgskyDimensions.NETHER));
		assertTrue(OgskyDimensions.isSkyblockDimension(OgskyDimensions.END));
		assertFalse(OgskyDimensions.isSkyblockDimension(vanillaOverworld));
		assertEquals("overworld", OgskyDimensions.dimensionName(OgskyDimensions.OVERWORLD));
		assertEquals("nether", OgskyDimensions.dimensionName(OgskyDimensions.NETHER));
		assertEquals("end", OgskyDimensions.dimensionName(OgskyDimensions.END));

		assertEquals(OgskyDimensions.NETHER, PortalRouter.resolveNetherCounterpart(OgskyDimensions.OVERWORLD));
		assertEquals(OgskyDimensions.OVERWORLD, PortalRouter.resolveNetherCounterpart(OgskyDimensions.NETHER));
		assertNull(PortalRouter.resolveNetherCounterpart(OgskyDimensions.END));
		assertNull(PortalRouter.resolveNetherCounterpart(vanillaNether));

		assertEquals(OgskyDimensions.END, PortalRouter.resolveEndCounterpart(OgskyDimensions.OVERWORLD));
		assertEquals(OgskyDimensions.OVERWORLD, PortalRouter.resolveEndCounterpart(OgskyDimensions.END));
		assertNull(PortalRouter.resolveEndCounterpart(OgskyDimensions.NETHER));
		assertNull(PortalRouter.resolveEndCounterpart(vanillaOverworld));
	}

	@Test
	void portalTargetClampingPreservesOneToOneCoordsAndRespectsIslandMargin() {
		SimpleFlagRegistry flags = new SimpleFlagRegistry();
		BuiltinFlags.registerAll(flags);
		flags.freeze();

		Island island = new Island(
			UUID.randomUUID(),
			UUID.randomUUID(),
			0,
			0,
			512,
			512,
			(short) 50,
			"default",
			Instant.now(),
			flags,
			CoreConfig.defaults()
		);

		assertEquals(462, island.minX());
		assertEquals(562, island.maxX());

		// One-to-one coordinates strictly inside the island stay unchanged (no 8x scaling).
		BlockPos searchInside = PortalRouter.clampSearchTarget(island, 520, 80, 505, 0, 250);
		assertEquals(new BlockPos(520, 80, 505), searchInside);

		// Coordinates outside island bounds clamp strictly within [min..max].
		BlockPos searchClamped = PortalRouter.clampSearchTarget(island, 600, -10, 400, 0, 250);
		assertEquals(new BlockPos(562, 1, 462), searchClamped);

		// Portal frame creation inset leaves room for 2x3 obsidian frame inside island bounds.
		BlockPos createOrigin = PortalRouter.clampInsetOrigin(island, 562, 72, 462, 2, 70, 240);
		assertEquals(new BlockPos(560, 72, 464), createOrigin);
	}
}
