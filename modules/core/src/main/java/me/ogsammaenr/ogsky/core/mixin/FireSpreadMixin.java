package me.ogsammaenr.ogsky.core.mixin;

import me.ogsammaenr.ogsky.core.internal.flag.BuiltinFlags;
import me.ogsammaenr.ogsky.core.internal.protection.ProtectionResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Enforces {@code ogsky:fire_spread} per-island setting for fire spreading and lava ignition.
 */
@Mixin(ServerLevel.class)
public abstract class FireSpreadMixin {
	@Inject(method = "canSpreadFireAround", at = @At("HEAD"), cancellable = true)
	private void ogsky$guardFireSpread(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		ProtectionResolver resolver = ProtectionResolver.active();
		if (resolver != null && !resolver.isSettingEnabledAt((ServerLevel) (Object) this, pos, BuiltinFlags.FIRE_SPREAD)) {
			cir.setReturnValue(false);
		}
	}
}
