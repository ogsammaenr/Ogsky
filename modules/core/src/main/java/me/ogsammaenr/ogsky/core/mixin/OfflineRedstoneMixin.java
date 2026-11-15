package me.ogsammaenr.ogsky.core.mixin;

import me.ogsammaenr.ogsky.core.internal.protection.ProtectionResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Enforces {@code ogsky:offline_redstone} during scheduled redstone block ticks.
 */
@Mixin(ServerLevel.class)
public abstract class OfflineRedstoneMixin {
	@Inject(method = "tickBlock", at = @At("HEAD"), cancellable = true)
	private void ogsky$guardOfflineRedstone(BlockPos pos, Block type, CallbackInfo ci) {
		ProtectionResolver resolver = ProtectionResolver.active();
		if (resolver != null && !resolver.isRedstoneTickAllowed((ServerLevel) (Object) this, pos, type)) {
			ci.cancel();
		}
	}
}
