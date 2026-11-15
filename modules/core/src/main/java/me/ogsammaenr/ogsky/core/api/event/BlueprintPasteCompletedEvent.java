package me.ogsammaenr.ogsky.core.api.event;

import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.UUID;

/**
 * Fired on the server thread when tick-budgeted {@code .nbt} blueprint placement finishes for a dimension.
 */
public record BlueprintPasteCompletedEvent(UUID islandId, Identifier dimensionId, String bundleId) implements OgskyEvent {
	public BlueprintPasteCompletedEvent {
		Objects.requireNonNull(islandId, "islandId");
		Objects.requireNonNull(dimensionId, "dimensionId");
		Objects.requireNonNull(bundleId, "bundleId");
	}
}
