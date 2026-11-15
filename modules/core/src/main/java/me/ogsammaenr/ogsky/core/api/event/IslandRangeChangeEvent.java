package me.ogsammaenr.ogsky.core.api.event;

import java.util.Objects;
import java.util.UUID;

/**
 * Published after an island's protection boundary {@code halfSize} changes.
 */
public record IslandRangeChangeEvent(UUID islandId, short oldHalfSize, short newHalfSize) implements OgskyEvent {
	public IslandRangeChangeEvent {
		Objects.requireNonNull(islandId, "islandId");
	}
}
