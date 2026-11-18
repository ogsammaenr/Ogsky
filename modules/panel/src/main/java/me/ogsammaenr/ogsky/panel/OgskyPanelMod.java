package me.ogsammaenr.ogsky.panel;

import me.ogsammaenr.ogsky.core.api.OgskyCoreApi;
import me.ogsammaenr.ogsky.core.api.OgskyModule;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/**
 * Optional Skyblock panel module delegating both {@code /is panel} and module GUIs
 * through {@code ogui-api} (rich client rendering with hardened vanilla {@code ChestMenu} fallback).
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
		PanelBlueprintAssetManager blueprintAssets = new PanelBlueprintAssetManager(core);
		PanelController controller = new PanelController(core, viewModels, preferences, blueprintAssets);
		PanelGuiHost guiHost = new PanelGuiHost(core, controller);
		PanelCommands commands = new PanelCommands(core, preferences, controller);

		core.services().register(PanelPreferenceRepository.class, preferences);
		core.services().register(PanelViewModelService.class, viewModels);
		core.services().register(PanelBlueprintAssetManager.class, blueprintAssets);
		core.services().register(PanelController.class, controller);
		core.services().register(PanelGuiHost.class, guiHost);
		core.guis().registerHost(guiHost);

		controller.registerOgui();
		controller.registerEvents();
		commands.registerAll(core.commands());

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			controller.onPlayerDisconnect(handler.getPlayer().getUUID());
			guiHost.onPlayerDisconnect(handler.getPlayer().getUUID());
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			controller.clearAll();
			guiHost.clearAll();
		});
	}
}
