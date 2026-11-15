package me.ogsammaenr.ogsky.core.api.event;

import java.util.Objects;
import java.util.UUID;

/**
 * Published after a new Skyblock island is created and registered in {@code ogsky-core}.
 */
public record IslandCreatedEvent(UUID islandId, UUID ownerId) implements OgskyEvent {
	public IslandCreatedEvent {
		Objects.requireNonNull(islandId, "islandId");
		Objects.requireNonNull(ownerId, "ownerId");
	}
}
