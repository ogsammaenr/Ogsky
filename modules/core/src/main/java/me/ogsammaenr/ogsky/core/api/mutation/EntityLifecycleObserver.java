package me.ogsammaenr.ogsky.core.api.mutation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * Server-thread observer notified when entities are created, permanently destroyed/transferred out
 * of a dimension, or cross block column boundaries.
 * Chunk unloads/loads do not trigger {@link #onEntityAdded} or {@link #onEntityRemoved}.
 */
public interface EntityLifecycleObserver {
	/**
	 * Called after an entity is permanently added to {@code level} (not fired when chunks reload existing entities).
	 */
	void onEntityAdded(ServerLevel level, Entity entity);

	/**
	 * Called when an entity is killed, discarded, or transferred out of {@code level} (not on chunk unloads).
	 */
	void onEntityRemoved(ServerLevel level, Entity entity);

	/**
	 * Called when an entity moves from {@code (fromBlockX, fromBlockZ)} to {@code (toBlockX, toBlockZ)}.
	 * Returning {@code false} vetoes cross-border movement.
	 */
	default boolean onEntityCrossedBlockColumn(
		ServerLevel level,
		Entity entity,
		int fromBlockX,
		int fromBlockZ,
		int toBlockX,
		int toBlockZ
	) {
		return true;
	}
}
