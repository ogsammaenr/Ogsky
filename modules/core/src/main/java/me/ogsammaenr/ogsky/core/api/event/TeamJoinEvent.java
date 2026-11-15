package me.ogsammaenr.ogsky.core.api.event;

import me.ogsammaenr.ogsky.core.api.island.IslandRole;

import java.util.Objects;
import java.util.UUID;

/**
 * Published after a player joins an island under a primary or secondary role.
 */
public record TeamJoinEvent(UUID islandId, UUID playerId, byte role) implements OgskyEvent {
	public TeamJoinEvent {
		Objects.requireNonNull(islandId, "islandId");
		Objects.requireNonNull(playerId, "playerId");
		if (!IslandRole.isValidMemberRole(role)) {
			throw new IllegalArgumentException("Invalid role: " + role);
		}
	}
}
