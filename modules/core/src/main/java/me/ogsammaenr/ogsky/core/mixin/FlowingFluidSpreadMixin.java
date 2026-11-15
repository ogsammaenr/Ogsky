package me.ogsammaenr.ogsky.core.mixin;

import me.ogsammaenr.ogsky.core.internal.protection.ProtectionResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Enforces {@code ogsky:liquid_flow_outside_bounds} when fluids attempt to cross island borders.
 */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidSpreadMixin {
	@Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true)
	private void ogsky$guardLiquidSpread(
		LevelAccessor level,
		BlockPos pos,
		BlockState state,
		Direction direction,
		FluidState target,
		CallbackInfo ci
	) {
		ProtectionResolver resolver = ProtectionResolver.active();
		if (resolver != null && !resolver.canLiquidSpread(level, pos, direction)) {
			ci.cancel();
		}
	}
}
