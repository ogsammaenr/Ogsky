package me.ogsammaenr.ogsky.limits;

import me.ogsammaenr.ogsky.core.api.OgskyCoreApi;
import me.ogsammaenr.ogsky.core.api.OgskyModule;
import me.ogsammaenr.ogsky.core.api.event.BlueprintPasteCompletedEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandDeletedEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandRangeChangeEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandResetEvent;
import me.ogsammaenr.ogsky.core.api.world.OgskyDimensionKeys;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;

/**
 * Ogsky module entrypoint for {@code ogsky-limits}.
 */
public final class OgskyLimitsMod implements OgskyModule {
	@Override
	public String moduleId() {
		return LimitRepository.MODULE_ID;
	}

	@Override
	public void onLoad(OgskyCoreApi core) {
		core.databases().registerModule(moduleId(), LimitRepository.MIGRATIONS);

		Path configPath = FabricLoader.getInstance()
			.getConfigDir()
			.resolve("ogsky")
			.resolve("limits.json");
		LimitRepository repository = new LimitRepository(core.databases());
		IslandLimitService limitService = new IslandLimitService(core, configPath, repository);

		core.flags().register(limitService.createViewLimitsFlagDefinition());
		core.services().register(IslandLimitService.class, limitService);
		core.mutations().registerBlockPlacementGuard(limitService);
		core.mutations().registerBlockChangeObserver(limitService);
		core.mutations().registerEntitySpawnGuard(limitService);
		core.mutations().registerEntityLifecycleObserver(limitService);

		LimitsCommands commands = new LimitsCommands(core, limitService);
		commands.registerAll(core.commands());

		LimitsGuiRegistrar guiRegistrar = new LimitsGuiRegistrar(core, limitService, commands);
		guiRegistrar.registerAll();

		final MinecraftServer[] activeServer = new MinecraftServer[1];

		core.events().subscribe(BlueprintPasteCompletedEvent.class, event -> {
			boolean skyblockDim = OgskyDimensionKeys.ALL.stream()
				.anyMatch(key -> key.identifier().equals(event.dimensionId()));
			if (!skyblockDim) {
				return;
			}
			MinecraftServer server = activeServer[0];
			if (server != null) {
				limitService.onBlueprintPasteCompleted(server, event.islandId());
			}
		});

		core.events().subscribe(IslandResetEvent.class, event -> {
			if (!event.isCancelled()) {
				limitService.onIslandReset(event.islandId());
			}
		});

		core.events().subscribe(IslandDeletedEvent.class, event ->
			limitService.onIslandDeleted(event.islandId())
		);

		core.events().subscribe(IslandRangeChangeEvent.class, event ->
			limitService.onIslandRangeChanged(activeServer[0], event.islandId())
		);

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			activeServer[0] = server;
			limitService.onServerStarted(server);
		});
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
			if (success) {
				limitService.reload(server);
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(limitService::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> limitService.onServerStopping());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			activeServer[0] = null;
			limitService.clearRuntimeState();
		});
	}
}
