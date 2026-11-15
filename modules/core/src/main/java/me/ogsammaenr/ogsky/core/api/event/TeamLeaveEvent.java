package me.ogsammaenr.ogsky.core.api.event;

import me.ogsammaenr.ogsky.core.api.island.IslandRole;

import java.util.Objects;
import java.util.UUID;

/**
 * Fired before a player voluntarily leaves an island membership.
 */
public final class TeamLeaveEvent extends AbstractCancellableOgskyEvent {
	private final UUID islandId;
	private final UUID playerId;
	private final byte oldRole;

	public TeamLeaveEvent(UUID islandId, UUID playerId, byte oldRole) {
		this.islandId = Objects.requireNonNull(islandId, "islandId");
		this.playerId = Objects.requireNonNull(playerId, "playerId");
		if (!IslandRole.isValidMemberRole(oldRole)) {
			throw new IllegalArgumentException("Invalid oldRole: " + oldRole);
		}
		this.oldRole = oldRole;
	}

	public UUID islandId() {
		return islandId;
	}

	public UUID playerId() {
		return playerId;
	}

	public byte oldRole() {
		return oldRole;
	}
}
