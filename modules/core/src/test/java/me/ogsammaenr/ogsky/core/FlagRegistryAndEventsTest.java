package me.ogsammaenr.ogsky.core;

import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.event.IslandEnterEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandPreCreateEvent;
import me.ogsammaenr.ogsky.core.api.flag.FlagMode;
import me.ogsammaenr.ogsky.core.api.flag.FlagType;
import me.ogsammaenr.ogsky.core.api.flag.IslandFlagDefinition;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.internal.event.SimpleOgskyEventBus;
import me.ogsammaenr.ogsky.core.internal.flag.BuiltinFlags;
import me.ogsammaenr.ogsky.core.internal.flag.SimpleFlagRegistry;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlagRegistryAndEventsTest {
	@Test
	void registersBuiltinFlagsAndAssignsDeterministicSlotsOnFreeze() {
		SimpleFlagRegistry registry = new SimpleFlagRegistry();
		BuiltinFlags.registerAll(registry);
		registry.register(IslandFlagDefinition.setting(
			OgskyCore.id("custom_setting"),
			FlagMode.ADVANCED,
			"minecraft:compass",
			9999,
			false
		));

		assertFalse(registry.isFrozen());
		assertThrows(IllegalStateException.class, () -> registry.slotOf(BuiltinFlags.BREAK_BLOCKS));

		registry.freeze();
		assertTrue(registry.isFrozen());
		assertEquals(34, registry.count(FlagType.PROTECTION));
		assertEquals(11, registry.count(FlagType.SETTING));
		assertEquals(9, registry.count(FlagType.WORLD_SETTING));

		int customSlot = registry.slotOf(OgskyCore.id("custom_setting"));
		assertEquals(10, customSlot);

		assertThrows(IllegalStateException.class, () -> registry.register(
			IslandFlagDefinition.protection(
				OgskyCore.id("late_flag"),
				FlagMode.BASIC,
				"minecraft:stone",
				1,
				IslandRole.MEMBER,
				false
			)
		));
	}

	@Test
	void cancellableEventsRespectCancellationAndAdminBypass() {
		SimpleOgskyEventBus bus = new SimpleOgskyEventBus();
		bus.subscribe(IslandPreCreateEvent.class, event -> event.cancel("Quota reached"));
		IslandPreCreateEvent preCreate = new IslandPreCreateEvent(UUID.randomUUID(), "default");
		assertFalse(bus.publishCancellable(preCreate));
		assertEquals("Quota reached", preCreate.cancelReason());

		bus.subscribe(IslandEnterEvent.class, event -> event.cancel("Locked"));
		IslandEnterEvent normalEnter = new IslandEnterEvent(UUID.randomUUID(), UUID.randomUUID(), false);
		assertFalse(bus.publishCancellable(normalEnter));

		IslandEnterEvent adminEnter = new IslandEnterEvent(UUID.randomUUID(), UUID.randomUUID(), true);
		assertTrue(bus.publishCancellable(adminEnter));
		assertFalse(adminEnter.isCancelled());
	}
}
