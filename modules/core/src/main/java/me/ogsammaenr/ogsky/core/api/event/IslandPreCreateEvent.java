package me.ogsammaenr.ogsky.core.api.event;

import java.util.Objects;
import java.util.UUID;

/**
 * Fired on the server thread before a new Skyblock island is allocated and persisted.
 */
public final class IslandPreCreateEvent extends AbstractCancellableOgskyEvent {
	private final UUID ownerId;
	private final String bundleId;

	public IslandPreCreateEvent(UUID ownerId, String bundleId) {
		this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
		this.bundleId = Objects.requireNonNull(bundleId, "bundleId");
	}

	public UUID ownerId() {
		return ownerId;
	}

	public String bundleId() {
		return bundleId;
	}
}
