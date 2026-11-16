package me.ogsammaenr.ogsky.core.mixin;

import me.ogsammaenr.ogsky.core.internal.mutation.CoreWorldMutationService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class LevelChunkSetBlockMixin {
	@Shadow
	@Final
	private Level level;

	@Inject(method = "setBlockState", at = @At("RETURN"))
	private void ogsky$notifyBlockChanged(
		BlockPos pos,
		BlockState state,
		int flags,
		CallbackInfoReturnable<@Nullable BlockState> cir
	) {
		if (!CoreWorldMutationService.hasBlockChangeObservers()) {
			return;
		}
		BlockState oldState = cir.getReturnValue();
		if (oldState == null || oldState == state || !(this.level instanceof ServerLevel serverLevel)) {
			return;
		}
		// Skip same-block property flips (redstone, crops, lit furnaces) except base-only multi-block types.
		if (oldState.getBlock() == state.getBlock()
			&& !(state.getBlock() instanceof AbstractBedBlock)
			&& !(state.getBlock() instanceof DoorBlock)
			&& !(state.getBlock() instanceof DoublePlantBlock)) {
			return;
		}
		CoreWorldMutationService service = CoreWorldMutationService.active();
		if (service != null) {
			service.dispatchBlockChanged(serverLevel, pos, oldState, state, flags);
		}
	}
}
