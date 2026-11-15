package me.ogsammaenr.ogsky.core.api.gui;

import me.ogsammaenr.ogsky.core.api.OgskyCore;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * UI-agnostic registry and dispatcher allowing optional modules to expose interactive GUI screens and island
 * summary contributions without depending on {@code ogsky-panel} or duplicating menu/dupe protections.
 */
public interface GuiService {
	Identifier MAIN_PANEL_SCREEN_ID = OgskyCore.id("main_panel");

	/**
	 * @return {@code true} when an active {@link GuiHost} (e.g., {@code ogsky-panel}) is available to render menus.
	 */
	boolean isAvailable();

	void registerHost(GuiHost host);

	void registerScreen(Identifier screenId, GuiScreenProvider provider);

	void registerSummaryContributor(Identifier contributorId, int sortOrder, IslandSummaryContributor contributor);

	boolean openScreen(ServerPlayer player, Identifier screenId, @Nullable UUID islandId);

	boolean openScreen(ServerPlayer player, GuiOpenRequest request);

	void refreshIslandScreens(UUID islandId);

	void refreshScreen(ServerPlayer player, Identifier screenId);

	Optional<GuiScreenProvider> findScreen(Identifier screenId);

	List<Component> collectSummaryLines(IslandSummaryContext context);
}
