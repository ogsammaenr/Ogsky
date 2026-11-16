package me.ogsammaenr.ogsky.core.api.gui;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

/**
 * Interaction context delivered to a {@link GuiButton} click handler by the active {@link GuiHost}.
 *
 * <p>Provides navigation helpers, per-screen key/value state mutations, live refresh, and
 * actor feedback without coupling the button handler to vanilla {@code ChestMenu} slots.</p>
 */
public interface GuiClickContext {
	/** Player performing the interaction. */
	ServerPlayer actor();

	/** Target island UUID bound to this screen session, or {@code null} for global screens. */
	@Nullable UUID islandId();

	/** Normalized click action reported by the host (left, right, shift-click, etc.). */
	GuiClickAction clickAction();

	/** Current page index carried by the open screen request. */
	int currentPage();

	/** Immutable view of the current key/value UI state (filters, sorts, search terms, etc.). */
	Map<String, String> state();

	/** Convenience lookup for {@link #state()} returning {@code defaultValue} when absent. */
	default String stateOrDefault(String key, String defaultValue) {
		return state().getOrDefault(key, defaultValue);
	}

	/** Re-renders the current screen in place, preserving page and state. */
	void refresh();

	/** Updates a single key in {@link #state()} and re-renders the current screen. */
	void refreshWithState(String key, String value);

	/** Navigates to {@code screenId} targeting {@code targetIslandId}, pushing onto the host back-stack if supported. */
	void openScreen(Identifier screenId, @Nullable UUID targetIslandId);

	/** Navigates using an explicit {@link GuiOpenRequest} (custom page and initial state). */
	void openScreen(GuiOpenRequest request);

	/** Returns to the previous screen in the navigation stack, or closes the UI when at the root. */
	void goBack();

	/** Closes the active UI session for {@link #actor()}. */
	void close();

	/** Delivers styled feedback to {@link #actor()} using the host's feedback convention. */
	void sendFeedback(boolean success, String message);
}
