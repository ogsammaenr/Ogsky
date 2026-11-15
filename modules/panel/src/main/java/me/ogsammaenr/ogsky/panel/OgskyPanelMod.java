package me.ogsammaenr.ogsky.panel;

import me.ogsammaenr.ogsky.core.api.OgskyCoreApi;
import me.ogsammaenr.ogsky.core.api.OgskyModule;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/**
 * Optional Skyblock panel module providing UI-agnostic {@link PanelSnapshot} state and vanilla
 * server-side {@link OgskyPanelMenu} rendering backed by {@code panel.db}.
 */
public final class OgskyPanelMod implements OgskyModule {
	@Override
	public String moduleId() {
		return PanelPreferenceRepository.MODULE_ID;
	}

	@Override
	public void onLoad(OgskyCoreApi core) {
		core.databases().registerModule(PanelPreferenceRepository.MODULE_ID, PanelPreferenceRepository.MIGRATIONS);

		PanelPreferenceRepository preferences = new PanelPreferenceRepository(core.databases());
		PanelViewModelService viewModels = new PanelViewModelService(core);
		PanelController controller = new PanelController(core, viewModels, preferences);
		PanelCommands commands = new PanelCommands(core, preferences, controller);

		core.services().register(PanelPreferenceRepository.class, preferences);
		core.services().register(PanelViewModelService.class, viewModels);
		core.services().register(PanelController.class, controller);

		controller.registerEvents();
		commands.registerAll(core.commands());

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
			controller.onPlayerDisconnect(handler.getPlayer().getUUID())
		);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> controller.clearAll());
	}
}
