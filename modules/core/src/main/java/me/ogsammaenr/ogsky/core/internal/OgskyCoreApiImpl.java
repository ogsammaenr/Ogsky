package me.ogsammaenr.ogsky.core.internal;

import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.OgskyCoreApi;
import me.ogsammaenr.ogsky.core.api.action.IslandActionContext;
import me.ogsammaenr.ogsky.core.api.action.IslandActionDescriptor;
import me.ogsammaenr.ogsky.core.api.action.IslandActionRegistry;
import me.ogsammaenr.ogsky.core.api.command.IslandCommandRegistry;
import me.ogsammaenr.ogsky.core.api.db.DatabaseService;
import me.ogsammaenr.ogsky.core.api.event.OgskyEventBus;
import me.ogsammaenr.ogsky.core.api.flag.FlagRegistry;
import me.ogsammaenr.ogsky.core.api.island.IslandOperationResult;
import me.ogsammaenr.ogsky.core.api.island.IslandService;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import me.ogsammaenr.ogsky.core.api.service.ServiceRegistry;
import me.ogsammaenr.ogsky.core.internal.action.SimpleIslandActionRegistry;
import me.ogsammaenr.ogsky.core.internal.blueprint.CoreBlueprintService;
import me.ogsammaenr.ogsky.core.internal.command.SimpleIslandCommandRegistry;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfig;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfigLoader;
import me.ogsammaenr.ogsky.core.internal.db.SqliteDatabaseService;
import me.ogsammaenr.ogsky.core.internal.event.SimpleOgskyEventBus;
import me.ogsammaenr.ogsky.core.internal.flag.BuiltinFlags;
import me.ogsammaenr.ogsky.core.internal.flag.SimpleFlagRegistry;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandService;
import me.ogsammaenr.ogsky.core.internal.island.Island;
import me.ogsammaenr.ogsky.core.internal.island.IslandLifecycleService;
import me.ogsammaenr.ogsky.core.internal.island.OperationResult;
import me.ogsammaenr.ogsky.core.internal.island.TeamService;
import me.ogsammaenr.ogsky.core.internal.permission.LuckPermsPermissionBridge;
import me.ogsammaenr.ogsky.core.internal.protection.ProtectionListeners;
import me.ogsammaenr.ogsky.core.internal.protection.ProtectionResolver;
import me.ogsammaenr.ogsky.core.internal.service.SimpleServiceRegistry;
import me.ogsammaenr.ogsky.core.internal.world.OgskyDimensions;
import me.ogsammaenr.ogsky.core.internal.world.PortalRouter;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Default {@link OgskyCoreApi} implementation wired during {@link OgskyCoreMod} startup.
 */
public final class OgskyCoreApiImpl implements OgskyCoreApi {
	private final OgskyEventBus eventBus = new SimpleOgskyEventBus();
	private final ServiceRegistry serviceRegistry = new SimpleServiceRegistry();
	private final IslandActionRegistry actionRegistry = new SimpleIslandActionRegistry();
	private final SqliteDatabaseService databaseService = new SqliteDatabaseService();
	private final SimpleFlagRegistry flagRegistry = new SimpleFlagRegistry();
	private final SimpleIslandCommandRegistry commandRegistry = new SimpleIslandCommandRegistry();
	private final CoreConfig config;
	private final @Nullable Path configPath;
	private final CoreIslandService islandService;
	private final PortalRouter portalRouter;
	private final CoreBlueprintService blueprintService;
	private final IslandLifecycleService lifecycleService;
	private final TeamService teamService;
	private final LuckPermsPermissionBridge permissionBridge;
	private final ProtectionResolver protectionResolver;
	private final ProtectionListeners protectionListeners;

	public OgskyCoreApiImpl() {
		this(CoreConfig.defaults());
	}

	public OgskyCoreApiImpl(CoreConfig config) {
		this(config, Path.of("config", "ogsky", "blueprints"), null);
	}

	public OgskyCoreApiImpl(CoreConfig config, Path blueprintsDir) {
		this(config, blueprintsDir, null);
	}

	public OgskyCoreApiImpl(CoreConfig config, Path blueprintsDir, @Nullable Path configPath) {
		Objects.requireNonNull(config, "config");
		Objects.requireNonNull(blueprintsDir, "blueprintsDir");
		config.ensureDefaultsAndValidate();
		this.config = config;
		this.configPath = configPath;
		BuiltinFlags.registerAll(flagRegistry);
		this.islandService = new CoreIslandService(config, flagRegistry, databaseService);
		this.portalRouter = new PortalRouter(islandService);
		this.blueprintService = new CoreBlueprintService(config, blueprintsDir, islandService, databaseService, eventBus);
		this.portalRouter.setLazyDimensionHandler(this.blueprintService);
		this.lifecycleService = new IslandLifecycleService(
			config,
			this.islandService,
			this.blueprintService,
			this.databaseService,
			this.flagRegistry,
			this.eventBus
		);
		this.teamService = new TeamService(config, this.islandService, this.databaseService, this.eventBus);
		this.islandService.bindServices(this.blueprintService, this.lifecycleService, this.teamService);
		this.permissionBridge = new LuckPermsPermissionBridge(config);
		this.protectionResolver = new ProtectionResolver(this.islandService, this.permissionBridge);
		this.portalRouter.setProtectionChecker(this.protectionResolver);
		this.protectionListeners = new ProtectionListeners(
			config,
			this.islandService,
			this.lifecycleService,
			this.protectionResolver,
			this.eventBus
		);
	}

	@Override
	public OgskyEventBus events() {
		return eventBus;
	}

	@Override
	public ServiceRegistry services() {
		return serviceRegistry;
	}

	@Override
	public IslandActionRegistry actions() {
		return actionRegistry;
	}

	@Override
	public DatabaseService databases() {
		return databaseService;
	}

	@Override
	public FlagRegistry flags() {
		return flagRegistry;
	}

	@Override
	public IslandCommandRegistry commands() {
		return commandRegistry;
	}

	@Override
	public IslandService islands() {
		return islandService;
	}

	public SqliteDatabaseService sqliteDatabases() {
		return databaseService;
	}

	public SimpleFlagRegistry simpleFlagRegistry() {
		return flagRegistry;
	}

	public SimpleIslandCommandRegistry simpleCommandRegistry() {
		return commandRegistry;
	}

	public CoreIslandService coreIslands() {
		return islandService;
	}

	public PortalRouter portalRouter() {
		return portalRouter;
	}

	public CoreBlueprintService blueprints() {
		return blueprintService;
	}

	public IslandLifecycleService lifecycle() {
		return lifecycleService;
	}

	public TeamService team() {
		return teamService;
	}

	public CoreConfig config() {
		return config;
	}

	@Override
	public LuckPermsPermissionBridge permissions() {
		return permissionBridge;
	}

	public ProtectionResolver protection() {
		return protectionResolver;
	}

	public ProtectionListeners protectionListeners() {
		return protectionListeners;
	}

	public void reloadConfigAndBlueprints() {
		reloadConfigAndBlueprints(null);
	}

	public void reloadConfigAndBlueprints(@Nullable MinecraftServer server) {
		if (configPath != null) {
			CoreConfig fresh = CoreConfigLoader.loadOrCreate(configPath);
			config.copyRuntimeFrom(fresh);
		}
		blueprintService.registry().reload(server != null ? server.getFixerUpper() : null);
	}

	public void freezeRegistries() {
		flagRegistry.freeze();
		islandService.initializeWorldSettingsFromDefaults();
	}

	/**
	 * Registers core island panel actions after core services are constructed.
	 * Separate from constructor so unit tests on clean registries stay deterministic.
	 */
	public void registerBuiltinActions() {
		actionRegistry.register(new IslandActionDescriptor(
			OgskyCore.id("island_home"),
			"Teleport Home",
			"Teleport to your island overworld home spawn.",
			"minecraft:oak_door",
			10,
			true,
			ctx -> teleportHomeAction(ctx, OgskyDimensions.OVERWORLD)
		));
		actionRegistry.register(new IslandActionDescriptor(
			OgskyCore.id("island_home_nether"),
			"Teleport Nether",
			"Teleport to your island Nether spawn (creates lazily if enabled).",
			"minecraft:obsidian",
			12,
			true,
			ctx -> teleportHomeAction(ctx, OgskyDimensions.NETHER)
		));
		actionRegistry.register(new IslandActionDescriptor(
			OgskyCore.id("island_home_end"),
			"Teleport End",
			"Teleport to your island End spawn (creates lazily if enabled).",
			"minecraft:end_stone",
			14,
			true,
			ctx -> teleportHomeAction(ctx, OgskyDimensions.END)
		));
		actionRegistry.register(new IslandActionDescriptor(
			OgskyCore.id("island_sethome"),
			"Set Home",
			"Set the island spawn in this dimension to your current position.",
			"minecraft:red_bed",
			16,
			false,
			this::setHomeAction
		));
		actionRegistry.register(new IslandActionDescriptor(
			OgskyCore.id("island_toggle_lock"),
			"Toggle Island Lock",
			"Lock or unlock your island to restrict or allow visitors.",
			"minecraft:iron_door",
			18,
			false,
			this::toggleLockAction
		));
	}

	private void teleportHomeAction(IslandActionContext ctx, ResourceKey<Level> dimension) {
		if (!permissionBridge.hasPermission(ctx.actor(), "ogsky.command.home", 0)) {
			sendActionFeedback(ctx.actor(), false, "You do not have permission to teleport home.");
			return;
		}
		Island island = islandService.grid().findById(ctx.islandId()).orElse(null);
		if (island == null) {
			sendActionFeedback(ctx.actor(), false, "Island does not exist.");
			return;
		}
		if (!protectionResolver.canEnterIsland(island, ctx.actor())) {
			String reason = island.isBanned(ctx.actor().getUUID())
				? "You are banned from that island."
				: "That island is currently locked.";
			sendActionFeedback(ctx.actor(), false, reason);
			return;
		}
		OperationResult<Void> result = lifecycleService.teleportHome(
			ctx.actor().level().getServer(),
			ctx.actor(),
			island,
			dimension
		);
		sendActionFeedback(ctx.actor(), result.success(), result.message());
	}

	private void setHomeAction(IslandActionContext ctx) {
		if (!permissionBridge.hasPermission(ctx.actor(), "ogsky.command.sethome", 0)) {
			sendActionFeedback(ctx.actor(), false, "You do not have permission to set island home.");
			return;
		}
		Island island = islandService.grid().findById(ctx.islandId()).orElse(null);
		if (island == null) {
			sendActionFeedback(ctx.actor(), false, "Island does not exist.");
			return;
		}
		boolean bypass = permissionBridge.hasBypass(ctx.actor(), BuiltinFlags.BREAK_BLOCKS)
			|| permissionBridge.isBypassActive(ctx.actor());
		OperationResult<Void> result = lifecycleService.setHome(ctx.actor(), island, bypass);
		sendActionFeedback(ctx.actor(), result.success(), result.message());
	}

	private void toggleLockAction(IslandActionContext ctx) {
		if (!permissionBridge.hasPermission(ctx.actor(), "ogsky.command.lock", 0)) {
			sendActionFeedback(ctx.actor(), false, "You do not have permission to lock or unlock islands.");
			return;
		}
		IslandView island = islandService.findById(ctx.islandId()).orElse(null);
		if (island == null) {
			sendActionFeedback(ctx.actor(), false, "Island does not exist.");
			return;
		}
		boolean nextLocked = !island.isSettingEnabled(BuiltinFlags.ISLAND_LOCK);
		boolean bypass = permissionBridge.hasBypass(ctx.actor(), BuiltinFlags.BREAK_BLOCKS)
			|| permissionBridge.isBypassActive(ctx.actor());
		IslandOperationResult<Boolean> result = islandService.setSettingFlag(
			ctx.actor().level().getServer(),
			ctx.actor().getUUID(),
			ctx.islandId(),
			BuiltinFlags.ISLAND_LOCK,
			nextLocked,
			bypass
		);
		sendActionFeedback(ctx.actor(), result.success(), result.message());
	}

	private static void sendActionFeedback(ServerPlayer actor, boolean success, String message) {
		actor.sendSystemMessage(
			Component.literal("[Ogsky] " + message)
				.withStyle(success ? ChatFormatting.GREEN : ChatFormatting.RED)
		);
	}
}
