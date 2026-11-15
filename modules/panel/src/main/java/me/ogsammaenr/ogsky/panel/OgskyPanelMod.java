package me.ogsammaenr.ogsky.panel;

import me.ogsammaenr.ogsky.core.api.OgskyCoreApi;
import me.ogsammaenr.ogsky.core.api.OgskyModule;
import me.ogsammaenr.ogsky.core.api.db.SchemaMigration;

import java.util.List;

/**
 * Ogsky module entrypoint for {@code ogsky-panel}.
 */
public final class OgskyPanelMod implements OgskyModule {
	private static final List<SchemaMigration> PANEL_MIGRATIONS = List.of(
		SchemaMigration.sql(
			1,
			"Initialize player panel preferences table",
			"""
			CREATE TABLE IF NOT EXISTS panel_preferences (
				player_uuid TEXT PRIMARY KEY,
				ui_mode TEXT NOT NULL,
				updated_at TEXT NOT NULL
			)
			"""
		)
	);

	@Override
	public String moduleId() {
		return "panel";
	}

	@Override
	public void onLoad(OgskyCoreApi core) {
		core.databases().registerModule(moduleId(), PANEL_MIGRATIONS);
		core.services().register(PanelViewModelService.class, new PanelViewModelService(core));
	}
}
