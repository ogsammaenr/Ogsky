package me.ogsammaenr.ogsky.core.api.action;

import net.minecraft.resources.Identifier;

import java.util.Objects;

/**
 * Metadata and server-side handler for a module-contributed island action.
 * Consumed by UI modules (such as {@code ogsky-panel}) without coupling to the contributing module.
 */
public record IslandActionDescriptor(
	Identifier id,
	String title,
	String description,
	String iconItemId,
	int sortOrder,
	boolean closePanelOnExecute,
	IslandActionHandler handler
) {
	public IslandActionDescriptor {
		Objects.requireNonNull(id, "id");
		if (title == null || title.isBlank()) {
			throw new IllegalArgumentException("Action title must not be blank.");
		}
		description = description == null ? "" : description;
		if (iconItemId == null || iconItemId.isBlank()) {
			throw new IllegalArgumentException("Action iconItemId must not be blank.");
		}
		Objects.requireNonNull(handler, "handler");
	}

	public IslandActionDescriptor(
		Identifier id,
		String title,
		String iconItemId,
		int sortOrder,
		IslandActionHandler handler
	) {
		this(id, title, "", iconItemId, sortOrder, false, handler);
	}
}
