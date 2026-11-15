package me.ogsammaenr.ogsky.core.api.event;

import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.UUID;

/**
 * Fired when a Skyblock dimension (including lazy {@code ogsky:nether} or {@code ogsky:end}) is initialized for an island.
 */
public record IslandDimensionInitEvent(UUID islandId, Identifier dimensionId, String blueprintId) implements OgskyEvent {
	public IslandDimensionInitEvent {
		Objects.requireNonNull(islandId, "islandId");
		Objects.requireNonNull(dimensionId, "dimensionId");
		Objects.requireNonNull(blueprintId, "blueprintId");
	}
}
