package me.ogsammaenr.ogsky.core.api.event;

import java.util.Objects;
import java.util.UUID;

/**
 * Published when an island is permanently deleted so optional modules can clean up or archive their own data.
 */
public record IslandDeletedEvent(UUID islandId) implements OgskyEvent {
	public IslandDeletedEvent {
		Objects.requireNonNull(islandId, "islandId");
	}
}
