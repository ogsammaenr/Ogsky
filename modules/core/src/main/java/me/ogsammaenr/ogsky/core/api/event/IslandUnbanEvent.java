package me.ogsammaenr.ogsky.core.api.event;

import java.util.Objects;
import java.util.UUID;

/**
 * Published after a player is unbanned from an island.
 */
public record IslandUnbanEvent(UUID islandId, UUID actorId, UUID targetId) implements OgskyEvent {
	public IslandUnbanEvent {
		Objects.requireNonNull(islandId, "islandId");
		Objects.requireNonNull(actorId, "actorId");
		Objects.requireNonNull(targetId, "targetId");
	}
}
