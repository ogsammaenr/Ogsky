package me.ogsammaenr.ogsky.core.api.mutation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import org.jspecify.annotations.Nullable;

/**
 * Pre-spawn guard consulted before an entity is created or added to a server world.
 */
@FunctionalInterface
public interface EntitySpawnGuard {
	/**
	 * Returns {@code true} if an entity of {@code entityType} may spawn or cross into {@code (x, y, z)} in {@code level}.
	 */
	boolean allowEntitySpawn(
		ServerLevel level,
		EntityType<?> entityType,
		double x,
		double y,
		double z,
		@Nullable ServerPlayer actor,
		EntitySpawnSource source
	);
}
