package me.ogsammaenr.ogsky.core.api.event;

import java.util.Objects;
import java.util.UUID;

/**
 * Published when a player exits an island's boundary.
 */
public record IslandExitEvent(UUID islandId, UUID playerId) implements OgskyEvent {
	public IslandExitEvent {
		Objects.requireNonNull(islandId, "islandId");
		Objects.requireNonNull(playerId, "playerId");
	}
}
