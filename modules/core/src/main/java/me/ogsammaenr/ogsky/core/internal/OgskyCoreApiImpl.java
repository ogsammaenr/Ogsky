package me.ogsammaenr.ogsky.core.internal;

import me.ogsammaenr.ogsky.core.api.OgskyCoreApi;
import me.ogsammaenr.ogsky.core.api.action.IslandActionRegistry;
import me.ogsammaenr.ogsky.core.api.command.IslandCommandRegistry;
import me.ogsammaenr.ogsky.core.api.db.DatabaseService;
import me.ogsammaenr.ogsky.core.api.event.OgskyEventBus;
import me.ogsammaenr.ogsky.core.api.flag.FlagRegistry;
import me.ogsammaenr.ogsky.core.api.island.IslandService;
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
import me.ogsammaenr.ogsky.core.internal.island.IslandLifecycleService;
import me.ogsammaenr.ogsky.core.internal.island.TeamService;
import me.ogsammaenr.ogsky.core.internal.permission.LuckPermsPermissionBridge;
import me.ogsammaenr.ogsky.core.internal.protection.ProtectionListeners;
import me.ogsammaenr.ogsky.core.internal.protection.ProtectionResolver;
import me.ogsammaenr.ogsky.core.internal.service.SimpleServiceRegistry;
import me.ogsammaenr.ogsky.core.internal.world.PortalRouter;
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
		if (configPath != null) {
			CoreConfig fresh = CoreConfigLoader.loadOrCreate(configPath);
			config.copyRuntimeFrom(fresh);
		}
		blueprintService.registry().reload();
	}

	public void freezeRegistries() {
		flagRegistry.freeze();
		islandService.initializeWorldSettingsFromDefaults();
	}
}
