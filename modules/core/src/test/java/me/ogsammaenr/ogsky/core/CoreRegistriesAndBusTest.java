package me.ogsammaenr.ogsky.core;

import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.action.IslandActionDescriptor;
import me.ogsammaenr.ogsky.core.api.event.IslandCreatedEvent;
import me.ogsammaenr.ogsky.core.api.event.OgskyEventBus;
import me.ogsammaenr.ogsky.core.internal.action.SimpleIslandActionRegistry;
import me.ogsammaenr.ogsky.core.internal.event.SimpleOgskyEventBus;
import me.ogsammaenr.ogsky.core.internal.service.SimpleServiceRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreRegistriesAndBusTest {
	@Test
	void eventBusPublishesAndUnsubscribesCleanly() {
		SimpleOgskyEventBus bus = new SimpleOgskyEventBus();
		List<UUID> received = new ArrayList<>();

		OgskyEventBus.Subscription sub = bus.subscribe(
			IslandCreatedEvent.class,
			event -> received.add(event.islandId())
		);

		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		bus.publish(new IslandCreatedEvent(first, UUID.randomUUID()));
		sub.unsubscribe();
		bus.publish(new IslandCreatedEvent(second, UUID.randomUUID()));

		assertEquals(List.of(first), received);
	}

	@Test
	void serviceRegistrySupportsOptionalAndRejectsDuplicates() {
		SimpleServiceRegistry registry = new SimpleServiceRegistry();
		assertTrue(registry.find(String.class).isEmpty());

		registry.register(String.class, "ogsky-service");
		assertEquals("ogsky-service", registry.require(String.class));
		assertThrows(IllegalStateException.class, () -> registry.register(String.class, "duplicate"));
	}

	@Test
	void actionRegistryOrdersDescriptorsBySortOrderThenId() {
		SimpleIslandActionRegistry registry = new SimpleIslandActionRegistry();
		registry.register(new IslandActionDescriptor(OgskyCore.id("zeta"), "Zeta", "minecraft:stone", 20, ctx -> { }));
		registry.register(new IslandActionDescriptor(OgskyCore.id("alpha"), "Alpha", "minecraft:oak_sapling", 10, ctx -> { }));
		registry.register(new IslandActionDescriptor(OgskyCore.id("beta"), "Beta", "minecraft:chest", 10, ctx -> { }));

		List<String> ids = registry.listOrdered().stream().map(d -> d.id().toString()).toList();
		assertEquals(List.of("ogsky:alpha", "ogsky:beta", "ogsky:zeta"), ids);
	}
}
