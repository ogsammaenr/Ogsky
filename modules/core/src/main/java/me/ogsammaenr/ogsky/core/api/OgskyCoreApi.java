package me.ogsammaenr.ogsky.core.api;

import me.ogsammaenr.ogsky.core.api.action.IslandActionRegistry;
import me.ogsammaenr.ogsky.core.api.command.IslandCommandRegistry;
import me.ogsammaenr.ogsky.core.api.db.DatabaseService;
import me.ogsammaenr.ogsky.core.api.event.OgskyEventBus;
import me.ogsammaenr.ogsky.core.api.flag.FlagRegistry;
import me.ogsammaenr.ogsky.core.api.gui.GuiService;
import me.ogsammaenr.ogsky.core.api.island.IslandService;
import me.ogsammaenr.ogsky.core.api.permission.PermissionService;
import me.ogsammaenr.ogsky.core.api.service.ServiceRegistry;

/**
 * Contract exposed by {@code ogsky-core} to optional Ogsky modules.
 */
public interface OgskyCoreApi {
	OgskyEventBus events();

	ServiceRegistry services();

	IslandActionRegistry actions();

	DatabaseService databases();

	FlagRegistry flags();

	IslandCommandRegistry commands();

	IslandService islands();

	PermissionService permissions();

	GuiService guis();
}
