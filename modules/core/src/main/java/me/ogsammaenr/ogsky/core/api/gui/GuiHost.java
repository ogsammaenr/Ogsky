package me.ogsammaenr.ogsky.core.api.gui;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * UI host implementation (provided by {@code ogsky-panel} or future UI modules) executing screen rendering and lifecycle.
 */
public interface GuiHost {
	boolean openScreen(ServerPlayer player, GuiOpenRequest request);

	void refreshIslandScreens(UUID islandId);

	void refreshPlayerScreen(ServerPlayer player, Identifier screenId);
}
