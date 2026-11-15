package me.ogsammaenr.ogsky.core.api.event;

import java.util.Objects;
import java.util.UUID;

/**
 * Published when an island's {@code island_lock} state changes.
 */
public record IslandLockChangedEvent(UUID islandId, UUID actorId, boolean locked) implements OgskyEvent {
	public IslandLockChangedEvent {
		Objects.requireNonNull(islandId, "islandId");
	}
}
