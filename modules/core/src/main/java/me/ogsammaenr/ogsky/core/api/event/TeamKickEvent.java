package me.ogsammaenr.ogsky.core.api.event;

import me.ogsammaenr.ogsky.core.api.island.IslandRole;

import java.util.Objects;
import java.util.UUID;

/**
 * Fired before a member or secondary helper is removed from an island by a manager.
 */
public final class TeamKickEvent extends AbstractCancellableOgskyEvent {
	private final UUID islandId;
	private final UUID actorId;
	private final UUID targetId;
	private final byte oldRole;

	public TeamKickEvent(UUID islandId, UUID actorId, UUID targetId, byte oldRole) {
		this.islandId = Objects.requireNonNull(islandId, "islandId");
		this.actorId = Objects.requireNonNull(actorId, "actorId");
		this.targetId = Objects.requireNonNull(targetId, "targetId");
		if (!IslandRole.isValidMemberRole(oldRole)) {
			throw new IllegalArgumentException("Invalid oldRole: " + oldRole);
		}
		this.oldRole = oldRole;
	}

	public UUID islandId() {
		return islandId;
	}

	public UUID actorId() {
		return actorId;
	}

	public UUID targetId() {
		return targetId;
	}

	public byte oldRole() {
		return oldRole;
	}
}
