package me.ogsammaenr.ogsky.core.api.event;

import me.ogsammaenr.ogsky.core.api.island.IslandRole;

import java.util.Objects;
import java.util.UUID;

/**
 * Fired before a member's {@link IslandRole} is promoted or demoted.
 */
public final class TeamRankChangeEvent extends AbstractCancellableOgskyEvent {
	private final UUID islandId;
	private final UUID actorId;
	private final UUID targetId;
	private final byte oldRole;
	private final byte newRole;

	public TeamRankChangeEvent(UUID islandId, UUID actorId, UUID targetId, byte oldRole, byte newRole) {
		this.islandId = Objects.requireNonNull(islandId, "islandId");
		this.actorId = Objects.requireNonNull(actorId, "actorId");
		this.targetId = Objects.requireNonNull(targetId, "targetId");
		if (!IslandRole.isValidMemberRole(oldRole) || !IslandRole.isValidMemberRole(newRole)) {
			throw new IllegalArgumentException("Invalid role transition: " + oldRole + " -> " + newRole);
		}
		this.oldRole = oldRole;
		this.newRole = newRole;
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

	public byte newRole() {
		return newRole;
	}
}
