package me.ogsammaenr.ogsky.level;

import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.OgskyCoreApi;
import me.ogsammaenr.ogsky.core.api.OgskyModule;
import me.ogsammaenr.ogsky.core.api.event.BlueprintPasteCompletedEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandDeletedEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandResetEvent;
import me.ogsammaenr.ogsky.core.api.level.IslandLevelProvider;
import me.ogsammaenr.ogsky.core.api.world.OgskyDimensionKeys;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;

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

		Path configPath = FabricLoader.getInstance()
			.getConfigDir()
			.resolve("ogsky")
			.resolve("level.json");
		IslandLevelService levelService = new IslandLevelService(core, configPath);
		core.services().register(IslandLevelService.class, levelService);
		core.services().register(IslandLevelProvider.class, levelService);

		LevelCommands commands = new LevelCommands(core, levelService);
		commands.registerAll(core.commands());

		LevelGuiRegistrar guiRegistrar = new LevelGuiRegistrar(core, levelService, commands);
		guiRegistrar.registerAll();

		core.events().subscribe(IslandDeletedEvent.class, event ->
			levelService.deleteIslandLevel(event.islandId()).exceptionally(ex -> {
				OgskyCore.LOGGER.error("Failed to remove level data for deleted island {}", event.islandId(), ex);
				return null;
			})
		);

		core.events().subscribe(IslandResetEvent.class, event -> {
			if (!event.isCancelled()) {
				levelService.onIslandReset(event.islandId());
			}
		});

		final MinecraftServer[] activeServer = new MinecraftServer[1];
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			activeServer[0] = server;
			levelService.onServerStarted(server);
		});
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
			if (success) {
				levelService.reloadConfig();
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			activeServer[0] = null;
			levelService.clearRuntimeState();
		});

		core.events().subscribe(BlueprintPasteCompletedEvent.class, event -> {
			if (!levelService.config().scan.autoScanOnPasteComplete) {
				return;
			}
			boolean skyblockDim = OgskyDimensionKeys.ALL.stream()
				.anyMatch(key -> key.identifier().equals(event.dimensionId()));
			if (!skyblockDim) {
				return;
			}
			MinecraftServer server = activeServer[0];
			if (server == null) {
				return;
			}
			core.islands().findById(event.islandId()).ifPresent(island ->
				levelService.requestScan(server, island, true, false)
			);
		});
	}
}
