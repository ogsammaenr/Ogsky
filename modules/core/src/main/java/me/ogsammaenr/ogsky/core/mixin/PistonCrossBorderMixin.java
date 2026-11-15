package me.ogsammaenr.ogsky.core.mixin;

import me.ogsammaenr.ogsky.core.internal.protection.ProtectionResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Enforces {@code ogsky:piston_outside_bounds} before pistons push or pull blocks across island perimeters.
 */
@Mixin(PistonBaseBlock.class)
public abstract class PistonCrossBorderMixin {
	@Inject(method = "moveBlocks", at = @At("HEAD"), cancellable = true)
	private void ogsky$guardPistonMoveBlocks(
		Level level,
		BlockPos pistonPos,
		Direction direction,
		boolean extending,
		CallbackInfoReturnable<Boolean> cir
	) {
		ProtectionResolver resolver = ProtectionResolver.active();
		if (resolver != null && !resolver.canPistonMove(level, pistonPos, direction, extending)) {
			cir.setReturnValue(false);
		}
	}
}
