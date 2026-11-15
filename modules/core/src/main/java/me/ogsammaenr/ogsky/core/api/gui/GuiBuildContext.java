package me.ogsammaenr.ogsky.core.api.gui;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Contextual parameters supplied to a {@link GuiScreenProvider} when rendering or refreshing a screen.
 */
public record GuiBuildContext(
	ServerPlayer viewer,
	@Nullable UUID islandId,
	int requestedPage,
	Map<String, String> state,
	@Nullable Identifier backScreenId,
	@Nullable UUID backIslandId
) {
	public GuiBuildContext {
		Objects.requireNonNull(viewer, "viewer");
		requestedPage = Math.max(0, requestedPage);
		state = state == null ? Map.of() : Map.copyOf(state);
	}

	public String stateOrDefault(String key, String defaultValue) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(defaultValue, "defaultValue");
		return state.getOrDefault(key, defaultValue);
	}
}
