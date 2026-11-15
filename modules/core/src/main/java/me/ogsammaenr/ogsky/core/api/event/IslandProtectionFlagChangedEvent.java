package me.ogsammaenr.ogsky.core.api.event;

import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.UUID;

/**
 * Published after a per-island {@code PROTECTION} flag rank threshold changes.
 */
public record IslandProtectionFlagChangedEvent(
	UUID islandId,
	UUID actorId,
	Identifier flagId,
	byte oldMinRank,
	byte newMinRank
) implements OgskyEvent {
	public IslandProtectionFlagChangedEvent {
		Objects.requireNonNull(islandId, "islandId");
		Objects.requireNonNull(flagId, "flagId");
		if (!IslandRole.isValidThreshold(oldMinRank) || !IslandRole.isValidThreshold(newMinRank)) {
			throw new IllegalArgumentException("Invalid protection rank transition: " + oldMinRank + " -> " + newMinRank);
		}
	}
}
