package me.ogsammaenr.ogsky.core.mixin;

import me.ogsammaenr.ogsky.core.api.mutation.BlockMutationDecision;
import me.ogsammaenr.ogsky.core.api.mutation.BlockPlacementSource;
import me.ogsammaenr.ogsky.core.internal.mutation.CoreWorldMutationService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.LavaFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LavaFluid.class)
public abstract class LavaFluidGeneratorMixin extends FlowingFluid {
	@Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true)
	private void ogsky$guardLavaDownwardStone(
		LevelAccessor level,
		BlockPos pos,
		BlockState state,
		Direction direction,
		FluidState target,
		CallbackInfo ci
	) {
		if (!CoreWorldMutationService.hasBlockPlacementGuards()
			|| direction != Direction.DOWN
			|| !this.is(FluidTags.LAVA)
			|| !(level instanceof ServerLevel serverLevel)) {
			return;
		}
		FluidState fluidState = level.getFluidState(pos);
		if (!fluidState.is(FluidTags.WATER) || !(state.getBlock() instanceof LiquidBlock)) {
			return;
		}
		CoreWorldMutationService service = CoreWorldMutationService.active();
		if (service == null) {
			return;
		}
		BlockMutationDecision decision = service.evaluateBlockPlacement(
			serverLevel,
			pos,
			Blocks.STONE.defaultBlockState(),
			state,
			null,
			BlockPlacementSource.LIQUID_GENERATOR
		);
		if (decision.isDenied()) {
			ci.cancel();
			return;
		}
		if (decision.isReplaced() && decision.replacementState() != null) {
			level.setBlockAndUpdate(pos, decision.replacementState());
			level.levelEvent(1501, pos, 0);
			ci.cancel();
		}
	}
}
