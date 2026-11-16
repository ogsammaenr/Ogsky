package me.ogsammaenr.ogsky.core.api;

import me.ogsammaenr.ogsky.core.api.action.IslandActionRegistry;
import me.ogsammaenr.ogsky.core.api.command.IslandCommandRegistry;
import me.ogsammaenr.ogsky.core.api.db.DatabaseService;
import me.ogsammaenr.ogsky.core.api.event.OgskyEventBus;
import me.ogsammaenr.ogsky.core.api.flag.FlagRegistry;
import me.ogsammaenr.ogsky.core.api.gui.GuiService;
import me.ogsammaenr.ogsky.core.api.island.IslandService;
import me.ogsammaenr.ogsky.core.api.mutation.WorldMutationService;
import me.ogsammaenr.ogsky.core.api.permission.PermissionService;
import me.ogsammaenr.ogsky.core.api.scan.IslandScanService;
import me.ogsammaenr.ogsky.core.api.service.ServiceRegistry;

/**
 * Contract exposed by {@code ogsky-core} to optional Ogsky modules.
 * Obtain the live instance via {@link OgskyCore#api()} or from {@link OgskyModule#onLoad(OgskyCoreApi)}.
 */
public interface OgskyCoreApi {
	/**
	 * Returns the synchronous domain event bus (all domain events MUST be published on the main server thread).
	 */
	OgskyEventBus events();

	/**
	 * Returns the singleton registry for optional cross-module service contracts (such as {@code IslandLevelProvider}).
	 */
	ServiceRegistry services();

	/**
	 * Returns the registry of island panel/command actions surfaced in {@code ogsky-panel}.
	 */
	IslandActionRegistry actions();

	/**
	 * Returns the isolated per-module SQLite lifecycle manager ({@code <world>/ogsky/data/<moduleId>.db}).
	 */
	DatabaseService databases();

	/**
	 * Returns the 3-tier flag registry; must be populated during {@link OgskyModule#onLoad(OgskyCoreApi)} before freezing.
	 */
	FlagRegistry flags();

	/**
	 * Returns the extensible Brigadier subcommand registry for {@code /island} ({@code /is}) and {@code /isadmin} ({@code /isa}).
	 */
	IslandCommandRegistry commands();

	/**
	 * Returns the RAM-first island query and mutation service.
	 */
	IslandService islands();

	/**
	 * Returns the permission and admin-bypass resolution bridge.
	 */
	PermissionService permissions();

	/**
	 * Returns the UI-agnostic GUI screen registry, summary contributor registry, and optional host dispatcher.
	 */
	GuiService guis();

	/**
	 * Returns the shared tick-budgeted 3-dimension block and entity island scanner.
	 */
	IslandScanService scans();

	/**
	 * Returns the module-agnostic world mutation guard and committed lifecycle observer registry.
	 */
	WorldMutationService mutations();
}
