package me.ogsammaenr.ogsky.border;

import me.ogsammaenr.ogsky.core.api.OgskyCoreApi;
import me.ogsammaenr.ogsky.core.api.OgskyModule;
import me.ogsammaenr.ogsky.core.api.event.IslandDeletedEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandRangeChangeEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandResetEvent;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;

/**
 * Ogsky module entrypoint for {@code ogsky-border}.
 */
public final class OgskyBorderMod implements OgskyModule {
	@Override
	public String moduleId() {
		return BorderRepository.MODULE_ID;
	}

	@Override
	public void onLoad(OgskyCoreApi core) {
		core.databases().registerModule(moduleId(), BorderRepository.MIGRATIONS);

		Path configPath = FabricLoader.getInstance()
			.getConfigDir()
			.resolve("ogsky")
			.resolve("border.json");
		BorderRepository repository = new BorderRepository(core.databases());
		IslandBorderService borderService = new IslandBorderService(core, configPath, repository);
		core.services().register(IslandBorderService.class, borderService);

		BorderCommands commands = new BorderCommands(core, borderService);
		commands.registerAll(core.commands());

		BorderGuiRegistrar guiRegistrar = new BorderGuiRegistrar(core, borderService);
		guiRegistrar.registerAll();

		final MinecraftServer[] activeServer = new MinecraftServer[1];

		core.events().subscribe(IslandRangeChangeEvent.class, event ->
			borderService.onIslandRangeChanged(activeServer[0], event.islandId())
		);
		core.events().subscribe(IslandDeletedEvent.class, event ->
			borderService.onIslandDeletedOrReset(activeServer[0], event.islandId())
		);
		core.events().subscribe(IslandResetEvent.class, event -> {
			if (!event.isCancelled()) {
				borderService.onIslandDeletedOrReset(activeServer[0], event.islandId());
			}
		});

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			activeServer[0] = server;
			borderService.onServerStarted(server);
		});
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
			if (success) {
				borderService.reload(server);
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(borderService::onServerTick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
			borderService.onPlayerJoin(handler.getPlayer())
		);
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) ->
			borderService.onPlayerDimensionChanged(player)
		);
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
			borderService.onPlayerRespawn(newPlayer)
		);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
			borderService.onPlayerDisconnect(handler.getPlayer().getUUID())
		);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			activeServer[0] = null;
			borderService.clearRuntimeState();
		});
	}
}
