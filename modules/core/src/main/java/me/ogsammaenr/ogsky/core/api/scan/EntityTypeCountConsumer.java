package me.ogsammaenr.ogsky.core.api.scan;

import net.minecraft.world.entity.EntityType;

/**
 * Server-thread consumer receiving {@link EntityType} counts during an island scan.
 */
@FunctionalInterface
public interface EntityTypeCountConsumer {
	void accept(EntityType<?> entityType, int count);
}
