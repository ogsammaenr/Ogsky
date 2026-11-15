package me.ogsammaenr.ogsky.level;

import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.OgskyCoreApi;
import me.ogsammaenr.ogsky.core.api.OgskyModule;
import me.ogsammaenr.ogsky.core.api.action.IslandActionDescriptor;
import me.ogsammaenr.ogsky.core.api.event.IslandDeletedEvent;
import net.minecraft.network.chat.Component;

/**
 * Ogsky module entrypoint for {@code ogsky-level}.
 */
public final class OgskyLevelMod implements OgskyModule {
	@Override
	public String moduleId() {
		return IslandLevelService.MODULE_DB_ID;
	}

	@Override
	public void onLoad(OgskyCoreApi core) {
		core.databases().registerModule(moduleId(), IslandLevelService.MIGRATIONS);

		IslandLevelService levelService = new IslandLevelService(core);
		core.services().register(IslandLevelService.class, levelService);

		core.events().subscribe(IslandDeletedEvent.class, event ->
			levelService.deleteIslandLevel(event.islandId()).exceptionally(ex -> {
				OgskyCore.LOGGER.error("Failed to remove level data for deleted island {}", event.islandId(), ex);
				return null;
			})
		);

		core.actions().register(new IslandActionDescriptor(
			OgskyCore.id("island_level"),
			"Island Level",
			"minecraft:experience_bottle",
			20,
			context -> context.actor().sendSystemMessage(
				Component.literal("[Ogsky] Island level calculation is not implemented yet.")
			)
		));
	}
}
