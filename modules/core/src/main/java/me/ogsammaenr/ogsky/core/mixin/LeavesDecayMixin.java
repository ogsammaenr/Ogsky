package me.ogsammaenr.ogsky.core.mixin;

import me.ogsammaenr.ogsky.core.internal.flag.BuiltinFlags;
import me.ogsammaenr.ogsky.core.internal.protection.ProtectionResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Enforces {@code ogsky:leaf_decay} per-island setting during random leaf decay ticks.
 */
@Mixin(LeavesBlock.class)
public abstract class LeavesDecayMixin {
	@Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
	private void ogsky$guardLeafDecay(
		BlockState state,
		ServerLevel level,
		BlockPos pos,
		RandomSource random,
		CallbackInfo ci
	) {
		ProtectionResolver resolver = ProtectionResolver.active();
		if (resolver != null && !resolver.isSettingEnabledAt(level, pos, BuiltinFlags.LEAF_DECAY)) {
			ci.cancel();
		}
	}
}
