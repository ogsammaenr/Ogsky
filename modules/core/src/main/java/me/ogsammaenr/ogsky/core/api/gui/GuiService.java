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

	/**
	 * Binds the UI renderer host; intended exclusively for UI provider modules such as {@code ogsky-panel}.
	 *
	 * @throws IllegalStateException if a different host is already registered
	 */
	void registerHost(GuiHost host);

	/**
	 * Registers a declarative {@link GuiScreenProvider} under a unique namespaced {@code screenId}.
	 */
	void registerScreen(Identifier screenId, GuiScreenProvider provider);

	/**
	 * Contributes dynamic lore lines appended to slot 8 on the main {@code /is panel} Island Summary card.
	 */
	void registerSummaryContributor(Identifier contributorId, int sortOrder, IslandSummaryContributor contributor);

	/**
	 * Opens {@code screenId} for {@code player}, returning {@code false} cleanly if no {@link GuiHost} is loaded
	 * or the screen ID is unknown so callers can fall back to chat output.
	 */
	boolean openScreen(ServerPlayer player, Identifier screenId, @Nullable UUID islandId);

	/**
	 * Opens a screen with explicit pagination, state parameters, and back-navigation request.
	 * Returns {@code false} cleanly when GUI rendering is unavailable.
	 */
	boolean openScreen(ServerPlayer player, GuiOpenRequest request);

	/**
	 * Refreshes any open UI screens currently displaying {@code islandId} (no-op if no host is active).
	 */
	void refreshIslandScreens(UUID islandId);

	/**
	 * Refreshes {@code screenId} if currently open for {@code player} (no-op if no host is active).
	 */
	void refreshScreen(ServerPlayer player, Identifier screenId);

	/**
	 * Looks up a registered screen provider by {@code screenId}.
	 */
	Optional<GuiScreenProvider> findScreen(Identifier screenId);

	/**
	 * Evaluates all registered {@link IslandSummaryContributor}s in {@code sortOrder} order.
	 */
	List<Component> collectSummaryLines(IslandSummaryContext context);
}
