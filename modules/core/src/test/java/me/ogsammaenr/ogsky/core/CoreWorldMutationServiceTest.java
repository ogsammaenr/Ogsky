package me.ogsammaenr.ogsky.core;

import me.ogsammaenr.ogsky.core.api.mutation.BlockChangeObserver;
import me.ogsammaenr.ogsky.core.api.mutation.BlockMutationDecision;
import me.ogsammaenr.ogsky.core.api.mutation.BlockPlacementSource;
import me.ogsammaenr.ogsky.core.api.mutation.EntityLifecycleObserver;
import me.ogsammaenr.ogsky.core.api.mutation.EntitySpawnSource;
import me.ogsammaenr.ogsky.core.internal.mutation.CoreWorldMutationService;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreWorldMutationServiceTest {
	@BeforeAll
	static void bootstrapRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@AfterEach
	void tearDown() {
		CoreWorldMutationService.setActive(null);
	}

	@Test
	void fastPathFlagsStayFalseUntilListenersRegistered() {
		CoreWorldMutationService service = new CoreWorldMutationService();
		CoreWorldMutationService.setActive(service);

		assertFalse(CoreWorldMutationService.hasBlockPlacementGuards());
		assertFalse(CoreWorldMutationService.hasBlockChangeObservers());
		assertFalse(CoreWorldMutationService.hasEntitySpawnGuards());
		assertFalse(CoreWorldMutationService.hasEntityLifecycleObservers());

		service.registerBlockPlacementGuard((level, pos, next, prev, actor, src) -> BlockMutationDecision.allow());
		assertTrue(CoreWorldMutationService.hasBlockPlacementGuards());

		service.registerBlockChangeObserver((level, pos, oldState, newState, flags) -> {
		});
		assertTrue(CoreWorldMutationService.hasBlockChangeObservers());

		service.registerEntitySpawnGuard((level, type, x, y, z, actor, src) -> true);
		assertTrue(CoreWorldMutationService.hasEntitySpawnGuards());

		service.registerEntityLifecycleObserver(new EntityLifecycleObserver() {
			@Override
			public void onEntityAdded(net.minecraft.server.level.ServerLevel level, net.minecraft.world.entity.Entity entity) {
			}

			@Override
			public void onEntityRemoved(net.minecraft.server.level.ServerLevel level, net.minecraft.world.entity.Entity entity) {
			}
		});
		assertTrue(CoreWorldMutationService.hasEntityLifecycleObservers());
	}

	@Test
	void placementGuardsPropagateReplaceAndShortCircuitOnDeny() {
		CoreWorldMutationService service = new CoreWorldMutationService();
		AtomicInteger secondGuardCalls = new AtomicInteger();

		service.registerBlockPlacementGuard((level, pos, next, prev, actor, src) ->
			BlockMutationDecision.replace(Blocks.STONE.defaultBlockState()));
		service.registerBlockPlacementGuard((level, pos, next, prev, actor, src) -> {
			secondGuardCalls.incrementAndGet();
			assertEquals(Blocks.STONE, next.getBlock());
			return BlockMutationDecision.deny();
		});
		service.registerBlockPlacementGuard((level, pos, next, prev, actor, src) -> {
			secondGuardCalls.addAndGet(100);
			return BlockMutationDecision.allow();
		});

		BlockMutationDecision decision = service.evaluateBlockPlacement(
			null,
			BlockPos.ZERO,
			Blocks.OBSIDIAN.defaultBlockState(),
			Blocks.LAVA.defaultBlockState(),
			null,
			BlockPlacementSource.LIQUID_GENERATOR
		);
		assertTrue(decision.isDenied());
		assertEquals(1, secondGuardCalls.get());
	}

	@Test
	void entitySpawnGuardsAndBlockObserversDispatchDeterministically() {
		CoreWorldMutationService service = new CoreWorldMutationService();
		service.registerEntitySpawnGuard((level, type, x, y, z, actor, src) -> type != EntityTypes.IRON_GOLEM);

		assertTrue(service.evaluateEntitySpawn(null, EntityTypes.COW, 0, 64, 0, null, EntitySpawnSource.BREEDING));
		assertFalse(service.evaluateEntitySpawn(null, EntityTypes.IRON_GOLEM, 0, 64, 0, null, EntitySpawnSource.STRUCTURE_SUMMON));

		AtomicInteger blockEvents = new AtomicInteger();
		BlockChangeObserver observer = (level, pos, oldState, newState, flags) -> blockEvents.incrementAndGet();
		service.registerBlockChangeObserver(observer);
		service.dispatchBlockChanged(null, BlockPos.ZERO, Blocks.AIR.defaultBlockState(), Blocks.HOPPER.defaultBlockState(), 3);
		assertEquals(1, blockEvents.get());

		assertNull(service.currentActor());
		var prev = service.pushActor(null);
		assertNull(prev);
		service.popActor(prev);
	}
}
