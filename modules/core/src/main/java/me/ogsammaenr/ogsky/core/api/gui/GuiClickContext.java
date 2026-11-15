package me.ogsammaenr.ogsky.core.api.gui;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

/**
 * Interaction context delivered to a {@link GuiButton} click handler by the active UI host.
 */
public interface GuiClickContext {
	ServerPlayer actor();

	@Nullable UUID islandId();

	GuiClickAction clickAction();

	int currentPage();

	Map<String, String> state();

	default String stateOrDefault(String key, String defaultValue) {
		return state().getOrDefault(key, defaultValue);
	}

	void refresh();

	void refreshWithState(String key, String value);

	void openScreen(Identifier screenId, @Nullable UUID targetIslandId);

	void openScreen(GuiOpenRequest request);

	void goBack();

	void close();

	void sendFeedback(boolean success, String message);
}
