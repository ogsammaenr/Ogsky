package me.ogsammaenr.ogsky.core.api.gui;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * UI host implementation (provided by {@code ogsky-panel} or future UI modules) executing screen rendering and lifecycle.
 */
public interface GuiHost {
	/**
	 * Opens the screen described by {@code request} for {@code player}.
	 * Returns {@code false} if no provider is registered for {@code request.screenId()} or rendering fails.
	 */
	boolean openScreen(ServerPlayer player, GuiOpenRequest request);

	/** Refreshes open GUI sessions bound to {@code islandId} for all viewing players. */
	void refreshIslandScreens(UUID islandId);

	/** Refreshes the open screen {@code screenId} for {@code player} if currently viewing it. */
	void refreshPlayerScreen(ServerPlayer player, Identifier screenId);
}
