package me.ogsammaenr.ogsky.core.mixin;

import me.ogsammaenr.ogsky.core.internal.protection.ProtectionResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Enforces Skyblock world explosion settings ({@code creeper_damage}, {@code creeper_griefing},
 * {@code tnt_damage}, {@code wither_damage}, and {@code chest_damage}).
 */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionProtectionMixin {
	@Inject(method = "calculateExplodedPositions", at = @At("RETURN"))
	private void ogsky$filterExplodedBlocks(CallbackInfoReturnable<List<BlockPos>> cir) {
		ProtectionResolver resolver = ProtectionResolver.active();
		if (resolver == null) {
			return;
		}
		ServerExplosion self = (ServerExplosion) (Object) this;
		List<BlockPos> blocks = cir.getReturnValue();
		blocks.removeIf(pos -> !resolver.canExplosionBreakBlock(self, pos));
	}

	@Inject(method = "hurtEntities", at = @At("HEAD"), cancellable = true)
	private void ogsky$guardExplosionEntityDamage(CallbackInfo ci) {
		ProtectionResolver resolver = ProtectionResolver.active();
		if (resolver != null && !resolver.shouldExplosionHurtEntities((ServerExplosion) (Object) this)) {
			ci.cancel();
		}
	}
}
