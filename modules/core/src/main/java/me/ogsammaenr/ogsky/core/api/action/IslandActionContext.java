package me.ogsammaenr.ogsky.core.api.action;

import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;
import java.util.UUID;

/**
 * Execution context passed when a player triggers an island panel/command action.
 */
public record IslandActionContext(ServerPlayer actor, UUID islandId) {
	public IslandActionContext {
		Objects.requireNonNull(actor, "actor");
		Objects.requireNonNull(islandId, "islandId");
	}
}
