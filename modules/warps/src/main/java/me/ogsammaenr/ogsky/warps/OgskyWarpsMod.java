package me.ogsammaenr.ogsky.warps;

import me.ogsammaenr.ogsky.core.api.OgskyCoreApi;
import me.ogsammaenr.ogsky.core.api.OgskyModule;
import me.ogsammaenr.ogsky.core.api.event.IslandDeletedEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandRangeChangeEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandResetEvent;
import me.ogsammaenr.ogsky.core.api.event.TeamOwnerChangeEvent;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;

/**
 * Ogsky module entrypoint for {@code ogsky-warps}.
 */
public final class OgskyWarpsMod implements OgskyModule {
	@Override
	public String moduleId() {
		return WarpRepository.MODULE_ID;
	}

	@Override
	public void onLoad(OgskyCoreApi core) {
		core.databases().registerModule(moduleId(), WarpRepository.MIGRATIONS);

		Path configPath = FabricLoader.getInstance()
			.getConfigDir()
			.resolve("ogsky")
			.resolve("warps.json");
		WarpRepository repository = new WarpRepository(core.databases());
		IslandWarpService warpService = new IslandWarpService(core, configPath, repository);

		core.flags().register(warpService.createManageWarpsFlagDefinition());
		core.flags().register(warpService.createUseWarpFlagDefinition());
		core.services().register(IslandWarpService.class, warpService);

		WarpsCommands commands = new WarpsCommands(core, warpService);
		commands.registerAll(core.commands());

		WarpsGuiRegistrar guiRegistrar = new WarpsGuiRegistrar(core, warpService, commands);
		guiRegistrar.registerAll();

		final MinecraftServer[] activeServer = new MinecraftServer[1];

		core.events().subscribe(IslandDeletedEvent.class, event ->
			warpService.onIslandDeleted(event.islandId())
		);
		core.events().subscribe(IslandResetEvent.class, event -> {
			if (!event.isCancelled()) {
				warpService.onIslandReset(event.islandId());
			}
		});
		core.events().subscribe(IslandRangeChangeEvent.class, event ->
			warpService.onIslandRangeChanged(event.islandId())
		);
		core.events().subscribe(TeamOwnerChangeEvent.class, event -> {
			if (!event.isCancelled()) {
				warpService.onTeamOwnerChanged(activeServer[0], event.islandId(), event.newOwnerId());
			}
		});

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			activeServer[0] = server;
			warpService.onServerStarted(server);
		});
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
			if (success) {
				warpService.reload(server);
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(warpService::onServerTick);
		ServerLivingEntityEvents.AFTER_DAMAGE.register(warpService::onLivingDamage);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
			warpService.onPlayerJoin(handler.getPlayer())
		);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
			warpService.onPlayerDisconnect(handler.getPlayer().getUUID())
		);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			activeServer[0] = null;
			warpService.clearRuntimeState();
		});
	}
}
