package me.ogsammaenr.ogsky.core.mixin;

import me.ogsammaenr.ogsky.core.api.mutation.BlockMutationDecision;
import me.ogsammaenr.ogsky.core.api.mutation.BlockPlacementSource;
import me.ogsammaenr.ogsky.core.internal.mutation.CoreWorldMutationService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LiquidBlock.class)
public abstract class LiquidBlockGeneratorMixin {
	@Shadow
	@Final
	protected FlowingFluid fluid;

	@Inject(method = "shouldSpreadLiquid", at = @At("HEAD"), cancellable = true)
	private void ogsky$guardLiquidGeneratorConversion(
		Level level,
		BlockPos pos,
		BlockState state,
		CallbackInfoReturnable<Boolean> cir
	) {
		if (!CoreWorldMutationService.hasBlockPlacementGuards()
			|| !this.fluid.is(FluidTags.LAVA)
			|| !(level instanceof ServerLevel serverLevel)) {
			return;
		}
		CoreWorldMutationService service = CoreWorldMutationService.active();
		if (service == null) {
			return;
		}

		boolean isOverSoulSoil = level.getBlockState(pos.below()).is(Blocks.SOUL_SOIL);
		for (Direction direction : LiquidBlock.POSSIBLE_FLOW_DIRECTIONS) {
			BlockPos neighbourPos = pos.relative(direction.getOpposite());
			if (level.getFluidState(neighbourPos).is(FluidTags.WATER)) {
				Block targetBlock = level.getFluidState(pos).isSource() ? Blocks.OBSIDIAN : Blocks.COBBLESTONE;
				BlockMutationDecision decision = service.evaluateBlockPlacement(
					serverLevel,
					pos,
					targetBlock.defaultBlockState(),
					state,
					null,
					BlockPlacementSource.LIQUID_GENERATOR
				);
				if (decision.isDenied()) {
					cir.setReturnValue(false);
					return;
				}
				if (decision.isReplaced() && decision.replacementState() != null) {
					level.setBlockAndUpdate(pos, decision.replacementState());
					level.levelEvent(1501, pos, 0);
					cir.setReturnValue(false);
					return;
				}
				return;
			}

			if (isOverSoulSoil && level.getBlockState(neighbourPos).is(Blocks.BLUE_ICE)) {
				BlockMutationDecision decision = service.evaluateBlockPlacement(
					serverLevel,
					pos,
					Blocks.BASALT.defaultBlockState(),
					state,
					null,
					BlockPlacementSource.LIQUID_GENERATOR
				);
				if (decision.isDenied()) {
					cir.setReturnValue(false);
					return;
				}
				if (decision.isReplaced() && decision.replacementState() != null) {
					level.setBlockAndUpdate(pos, decision.replacementState());
					level.levelEvent(1501, pos, 0);
					cir.setReturnValue(false);
					return;
				}
				return;
			}
		}
	}
}
