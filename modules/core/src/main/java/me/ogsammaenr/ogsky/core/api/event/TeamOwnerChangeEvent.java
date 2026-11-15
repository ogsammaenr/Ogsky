package me.ogsammaenr.ogsky.core.api.event;

import java.util.Objects;
import java.util.UUID;

/**
 * Fired before island ownership transfers from one primary member to another.
 */
public final class TeamOwnerChangeEvent extends AbstractCancellableOgskyEvent {
	private final UUID islandId;
	private final UUID oldOwnerId;
	private final UUID newOwnerId;

	public TeamOwnerChangeEvent(UUID islandId, UUID oldOwnerId, UUID newOwnerId) {
		this.islandId = Objects.requireNonNull(islandId, "islandId");
		this.oldOwnerId = Objects.requireNonNull(oldOwnerId, "oldOwnerId");
		this.newOwnerId = Objects.requireNonNull(newOwnerId, "newOwnerId");
	}

	public UUID islandId() {
		return islandId;
	}

	public UUID oldOwnerId() {
		return oldOwnerId;
	}

	public UUID newOwnerId() {
		return newOwnerId;
	}
}
