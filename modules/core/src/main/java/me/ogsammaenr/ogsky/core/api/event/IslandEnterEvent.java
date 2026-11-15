package me.ogsammaenr.ogsky.core.api.event;

import java.util.Objects;
import java.util.UUID;

/**
 * Fired when a player attempts to enter an island's boundary. Cancellable unless {@code adminBypass} is {@code true}.
 */
public final class IslandEnterEvent extends AbstractCancellableOgskyEvent {
	private final UUID islandId;
	private final UUID playerId;
	private final boolean adminBypass;

	public IslandEnterEvent(UUID islandId, UUID playerId, boolean adminBypass) {
		this.islandId = Objects.requireNonNull(islandId, "islandId");
		this.playerId = Objects.requireNonNull(playerId, "playerId");
		this.adminBypass = adminBypass;
	}

	public UUID islandId() {
		return islandId;
	}

	public UUID playerId() {
		return playerId;
	}

	public boolean adminBypass() {
		return adminBypass;
	}

	@Override
	public void setCancelled(boolean cancelled) {
		if (cancelled && adminBypass) {
			return;
		}
		super.setCancelled(cancelled);
	}
}
