package me.ogsammaenr.ogsky.core.api.event;

import java.util.Objects;
import java.util.UUID;

/**
 * Fired before an island is reset to a new blueprint bundle.
 */
public final class IslandResetEvent extends AbstractCancellableOgskyEvent {
	private final UUID islandId;
	private final UUID ownerId;
	private final String newBundleId;

	public IslandResetEvent(UUID islandId, UUID ownerId, String newBundleId) {
		this.islandId = Objects.requireNonNull(islandId, "islandId");
		this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
		this.newBundleId = Objects.requireNonNull(newBundleId, "newBundleId");
	}

	public UUID islandId() {
		return islandId;
	}

	public UUID ownerId() {
		return ownerId;
	}

	public String newBundleId() {
		return newBundleId;
	}
}
