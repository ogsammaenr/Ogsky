package me.ogsammaenr.ogsky.panel;

import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.action.IslandActionDescriptor;
import me.ogsammaenr.ogsky.core.internal.OgskyCoreApiImpl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PanelViewModelServiceTest {
	@Test
	void snapshotReflectsDynamicallyRegisteredModuleActions() {
		OgskyCoreApiImpl core = new OgskyCoreApiImpl();
		PanelViewModelService service = new PanelViewModelService(core);
		UUID islandId = UUID.randomUUID();

		assertEquals(List.of(), service.buildSnapshot(islandId).entries());

		core.actions().register(new IslandActionDescriptor(
			OgskyCore.id("island_level"),
			"Island Level",
			"minecraft:experience_bottle",
			20,
			ctx -> { }
		));
		core.actions().register(new IslandActionDescriptor(
			OgskyCore.id("island_home"),
			"Island Home",
			"minecraft:oak_door",
			10,
			ctx -> { }
		));

		PanelSnapshot snapshot = service.buildSnapshot(islandId);
		assertEquals(
			List.of("ogsky:island_home", "ogsky:island_level"),
			snapshot.entries().stream().map(e -> e.actionId().toString()).toList()
		);
	}
}
