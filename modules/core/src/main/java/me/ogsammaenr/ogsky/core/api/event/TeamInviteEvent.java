package me.ogsammaenr.ogsky.core.api.event;

import me.ogsammaenr.ogsky.core.api.island.IslandRole;

import java.util.Objects;
import java.util.UUID;

/**
 * Fired before an island invitation or helper assignment is sent to a target player.
 */
public final class TeamInviteEvent extends AbstractCancellableOgskyEvent {
	private final UUID islandId;
	private final UUID inviterId;
	private final UUID inviteeId;
	private final byte targetRole;

	public TeamInviteEvent(UUID islandId, UUID inviterId, UUID inviteeId, byte targetRole) {
		this.islandId = Objects.requireNonNull(islandId, "islandId");
		this.inviterId = Objects.requireNonNull(inviterId, "inviterId");
		this.inviteeId = Objects.requireNonNull(inviteeId, "inviteeId");
		if (!IslandRole.isValidMemberRole(targetRole)) {
			throw new IllegalArgumentException("Invalid targetRole: " + targetRole);
		}
		this.targetRole = targetRole;
	}

	public UUID islandId() {
		return islandId;
	}

	public UUID inviterId() {
		return inviterId;
	}

	public UUID inviteeId() {
		return inviteeId;
	}

	public byte targetRole() {
		return targetRole;
	}
}
