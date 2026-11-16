package me.ogsammaenr.ogsky.core.api.mutation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Server-thread guard consulted before guarded placements or fluid generator conversions mutate the world.
 * Registered guards execute sequentially; the first {@code DENY} short-circuits, while {@code REPLACE} chains state.
 */
@FunctionalInterface
public interface BlockPlacementGuard {
	/**
	 * Evaluates a candidate block placement or fluid conversion before it writes {@code newState} at {@code pos}.
	 */
	BlockMutationDecision checkPlacement(
		ServerLevel level,
		BlockPos pos,
		BlockState newState,
		BlockState previousState,
		@Nullable ServerPlayer actor,
		BlockPlacementSource source
	);
}
