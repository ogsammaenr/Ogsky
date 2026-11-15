package me.ogsammaenr.ogsky.core.api.island;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable member entry snapshot pairing a player UUID with an {@link IslandRole} byte ({@code 1..5}).
 */
public record IslandMemberEntry(UUID playerUuid, byte role) {
	public IslandMemberEntry {
		Objects.requireNonNull(playerUuid, "playerUuid");
		if (!IslandRole.isValidMemberRole(role)) {
			throw new IllegalArgumentException("Invalid member role: " + role);
		}
	}
}
