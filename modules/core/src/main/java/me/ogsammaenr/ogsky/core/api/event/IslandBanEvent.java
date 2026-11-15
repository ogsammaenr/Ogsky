package me.ogsammaenr.ogsky.core.api.event;

import java.util.Objects;
import java.util.UUID;

/**
 * Fired before a player is banned from an island.
 */
public final class IslandBanEvent extends AbstractCancellableOgskyEvent {
	private final UUID islandId;
	private final UUID actorId;
	private final UUID targetId;

	public IslandBanEvent(UUID islandId, UUID actorId, UUID targetId) {
		this.islandId = Objects.requireNonNull(islandId, "islandId");
		this.actorId = Objects.requireNonNull(actorId, "actorId");
		this.targetId = Objects.requireNonNull(targetId, "targetId");
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
}
