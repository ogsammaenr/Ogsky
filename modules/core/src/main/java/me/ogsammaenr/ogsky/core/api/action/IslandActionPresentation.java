package me.ogsammaenr.ogsky.core.api.action;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Dynamic, viewer- and island-aware display overrides for an {@link IslandActionDescriptor}.
 */
public record IslandActionPresentation(
	String title,
	String description,
	String iconItemId,
	@Nullable Boolean activeState,
	@Nullable String stateLabel,
	@Nullable String badgeLabel,
	boolean invertActiveColor
) {
	public IslandActionPresentation {
		if (title == null || title.isBlank()) {
			throw new IllegalArgumentException("Action presentation title must not be blank.");
		}
		description = description == null ? "" : description;
		if (iconItemId == null || iconItemId.isBlank()) {
			throw new IllegalArgumentException("Action presentation iconItemId must not be blank.");
		}
	}

	public static IslandActionPresentation toggle(
		String title,
		String description,
		String iconItemId,
		boolean enabled,
		String stateLabel,
		String badgeLabel
	) {
		return new IslandActionPresentation(title, description, iconItemId, enabled, stateLabel, badgeLabel, false);
	}
}
