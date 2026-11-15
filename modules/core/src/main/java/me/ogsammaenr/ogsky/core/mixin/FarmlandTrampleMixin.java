package me.ogsammaenr.ogsky.core.mixin;

import me.ogsammaenr.ogsky.core.internal.protection.ProtectionResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Enforces {@code ogsky:crop_trample} when entities trample farmland inside Skyblock islands.
 */
@Mixin(FarmlandBlock.class)
public abstract class FarmlandTrampleMixin {
	@Inject(method = "turnToBaseBlock", at = @At("HEAD"), cancellable = true)
	private void ogsky$guardFarmlandTrample(
		@Nullable Entity sourceEntity,
		BlockState state,
		Level level,
		BlockPos pos,
		CallbackInfo ci
	) {
		if (sourceEntity == null || !(level instanceof ServerLevel serverLevel)) {
			return;
		}
		ProtectionResolver resolver = ProtectionResolver.active();
		if (resolver != null && !resolver.canTrampleFarmland(serverLevel, pos, sourceEntity)) {
			ci.cancel();
		}
	}
}
