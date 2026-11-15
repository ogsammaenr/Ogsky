package me.ogsammaenr.ogsky.core.internal;

import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.OgskyModule;
import me.ogsammaenr.ogsky.core.internal.command.OgskyCommands;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfig;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfigLoader;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandRepository;
import me.ogsammaenr.ogsky.core.internal.protection.ProtectionResolver;
import me.ogsammaenr.ogsky.core.internal.world.PortalRouter;
import me.ogsammaenr.ogsky.core.internal.world.VoidChunkGenerator;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fabric entrypoint for {@code ogsky-core}. Bootstraps core configuration, shared registries,
 * SQLite lifecycle hooks, and deterministically initializes optional {@link OgskyModule}s.
 */
public final class OgskyCoreMod implements ModInitializer {
	@Override
	public void onInitialize() {
		Path configDir = FabricLoader.getInstance().getConfigDir().resolve("ogsky");
		Path configPath = configDir.resolve("core.json");
		CoreConfig config = CoreConfigLoader.loadOrCreate(configPath);

		OgskyCoreApiImpl api = new OgskyCoreApiImpl(config, configDir.resolve("blueprints"), configPath);
		OgskyCore.bind(api);
		PortalRouter.bind(api.portalRouter());
		ProtectionResolver.bind(api.protection());
		Registry.register(BuiltInRegistries.CHUNK_GENERATOR, OgskyCore.id("void"), VoidChunkGenerator.CODEC);

		api.databases().registerModule(CoreIslandRepository.MODULE_ID, CoreIslandRepository.MIGRATIONS);

		List<OgskyModule> modules = FabricLoader.getInstance()
			.getEntrypoints("ogsky-module", OgskyModule.class)
			.stream()
			.sorted(Comparator.comparing(OgskyModule::moduleId))
			.toList();

		Set<String> seenIds = new HashSet<>();
		for (OgskyModule module : modules) {
			if (!seenIds.add(module.moduleId())) {
				throw new IllegalStateException("Duplicate Ogsky moduleId: " + module.moduleId());
			}
			module.onLoad(api);
			OgskyCore.LOGGER.info("Loaded Ogsky module: {}", module.moduleId());
		}

		api.freezeRegistries();

		OgskyCommands commands = new OgskyCommands(
			api.coreIslands(),
			api.lifecycle(),
			api.team(),
			api.blueprints(),
			api.flags(),
			api.commands(),
			api.permissions(),
			api.protection(),
			api::reloadConfigAndBlueprints
		);
		commands.registerCallbacks();
		api.protectionListeners().registerAll();

		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			Path dataRoot = server.getWorldPath(LevelResource.ROOT).resolve("ogsky").resolve("data");
			api.sqliteDatabases().openAllForRoot(dataRoot);
			api.coreIslands().loadFromDatabase();
			api.blueprints().onServerStarting();
			OgskyCore.LOGGER.info(
				"Loaded {} Skyblock island(s) into RAM (cellSize={}, locked={}).",
				api.coreIslands().islandCount(),
				api.coreIslands().lockedCellSize(),
				api.coreIslands().isCellSizeLocked()
			);
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> api.blueprints().tick(server));

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			api.blueprints().onServerStopping();
			api.team().clearPendingInvites();
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			var playerUuid = handler.getPlayer().getUUID();
			api.team().onPlayerDisconnect(server, playerUuid);
			api.protectionListeners().onPlayerDisconnect(playerUuid);
			commands.clearPlayerState(playerUuid);
		});

		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			api.protectionListeners().clearTracking();
			commands.clearAllState();
			api.coreIslands().clear();
			api.sqliteDatabases().closeAll();
		});

		OgskyCore.LOGGER.info("Ogsky Core initialized ({} optional module(s)).", modules.size());
	}
}
