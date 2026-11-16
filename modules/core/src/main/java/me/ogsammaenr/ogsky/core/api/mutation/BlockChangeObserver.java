package me.ogsammaenr.ogsky.core.api.mutation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Server-thread observer notified whenever a block state transition commits inside a chunk.
 */
@FunctionalInterface
public interface BlockChangeObserver {
	/**
	 * Invoked after a chunk block state transition from {@code oldState} to {@code newState} commits at {@code pos}.
	 */
	void onBlockChanged(
		ServerLevel level,
		BlockPos pos,
		BlockState oldState,
		BlockState newState,
		int updateFlags
	);
}
