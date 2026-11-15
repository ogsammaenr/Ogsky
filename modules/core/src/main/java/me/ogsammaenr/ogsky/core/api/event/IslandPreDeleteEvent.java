package me.ogsammaenr.ogsky.core.api.event;

import java.util.Objects;
import java.util.UUID;

/**
 * Fired before an island is permanently deleted.
 */
public final class IslandPreDeleteEvent extends AbstractCancellableOgskyEvent {
	public enum DeleteReason {
		PLAYER_DELETE,
		PURGE,
		ADMIN
	}

	private final UUID islandId;
	private final UUID actorId;
	private final DeleteReason reason;

	public IslandPreDeleteEvent(UUID islandId, UUID actorId, DeleteReason reason) {
		this.islandId = Objects.requireNonNull(islandId, "islandId");
		this.actorId = actorId;
		this.reason = Objects.requireNonNull(reason, "reason");
	}

	public UUID islandId() {
		return islandId;
	}

	public UUID actorId() {
		return actorId;
	}

	public DeleteReason reason() {
		return reason;
	}
}
