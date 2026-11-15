package me.ogsammaenr.ogsky.core.api.gui;

import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable navigation & filter state request passed when opening or refreshing a registered module GUI screen.
 */
public record GuiOpenRequest(
	Identifier screenId,
	@Nullable UUID islandId,
	int page,
	Map<String, String> state,
	@Nullable GuiOpenRequest backRequest
) {
	private static final int MAX_BACK_DEPTH = 16;

	public GuiOpenRequest {
		Objects.requireNonNull(screenId, "screenId");
		page = Math.max(0, page);
		state = state == null ? Map.of() : Map.copyOf(state);
		if (depth(backRequest) > MAX_BACK_DEPTH) {
			backRequest = truncate(backRequest, MAX_BACK_DEPTH);
		}
	}

	public static GuiOpenRequest of(Identifier screenId, @Nullable UUID islandId) {
		return new GuiOpenRequest(screenId, islandId, 0, Map.of(), null);
	}

	public static GuiOpenRequest of(Identifier screenId, @Nullable UUID islandId, int page) {
		return new GuiOpenRequest(screenId, islandId, page, Map.of(), null);
	}

	public GuiOpenRequest withPage(int nextPage) {
		return new GuiOpenRequest(screenId, islandId, nextPage, state, backRequest);
	}

	public GuiOpenRequest withState(String key, String value) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(value, "value");
		Map<String, String> copy = new LinkedHashMap<>(state);
		copy.put(key, value);
		return new GuiOpenRequest(screenId, islandId, 0, copy, backRequest);
	}

	public GuiOpenRequest withBackRequest(@Nullable GuiOpenRequest parent) {
		return new GuiOpenRequest(screenId, islandId, page, state, parent);
	}

	public @Nullable Identifier backScreenId() {
		return backRequest != null ? backRequest.screenId() : null;
	}

	public @Nullable UUID backIslandId() {
		return backRequest != null ? backRequest.islandId() : null;
	}

	private static int depth(@Nullable GuiOpenRequest request) {
		int count = 0;
		GuiOpenRequest cursor = request;
		while (cursor != null && count <= MAX_BACK_DEPTH + 1) {
			count++;
			cursor = cursor.backRequest();
		}
		return count;
	}

	private static @Nullable GuiOpenRequest truncate(@Nullable GuiOpenRequest request, int remaining) {
		if (request == null || remaining <= 0) {
			return null;
		}
		return new GuiOpenRequest(
			request.screenId(),
			request.islandId(),
			request.page(),
			request.state(),
			truncate(request.backRequest(), remaining - 1)
		);
	}
}
