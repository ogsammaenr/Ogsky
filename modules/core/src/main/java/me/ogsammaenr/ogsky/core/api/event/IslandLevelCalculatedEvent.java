package me.ogsammaenr.ogsky.core.api.event;

import java.util.Objects;
import java.util.UUID;

/**
 * Fired on the server tick thread after {@code ogsky-level} finishes recalculating an island's level.
 */
public record IslandLevelCalculatedEvent(
	UUID islandId,
	UUID ownerId,
	long previousLevel,
	long newLevel,
	long blockPoints
) implements OgskyEvent {
	public IslandLevelCalculatedEvent {
		Objects.requireNonNull(islandId, "islandId");
		Objects.requireNonNull(ownerId, "ownerId");
	}
}
