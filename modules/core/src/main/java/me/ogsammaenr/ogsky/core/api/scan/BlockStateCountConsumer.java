package me.ogsammaenr.ogsky.core.api.scan;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Server-thread consumer receiving batched non-air {@link BlockState} counts during an island scan.
 */
@FunctionalInterface
public interface BlockStateCountConsumer {
	void accept(BlockState state, int count);
}
