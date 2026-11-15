package me.ogsammaenr.ogsky.core.mixin;

import me.ogsammaenr.ogsky.core.api.island.IslandView;
import me.ogsammaenr.ogsky.core.internal.flag.BuiltinFlags;
import me.ogsammaenr.ogsky.core.internal.protection.ProtectionResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Enforces {@code ogsky:ender_pearl} and {@code ogsky:island_lock} at Ender Pearl landing positions.
 */
@Mixin(ThrownEnderpearl.class)
public abstract class ThrownEnderpearlMixin {
	@Inject(method = "onHit", at = @At("HEAD"), cancellable = true)
	private void ogsky$guardEnderpearlLanding(HitResult hitResult, CallbackInfo ci) {
		ThrownEnderpearl self = (ThrownEnderpearl) (Object) this;
		if (!(self.level() instanceof ServerLevel serverLevel)) {
			return;
		}
		ProtectionResolver resolver = ProtectionResolver.active();
		if (resolver == null || !resolver.isProtectedDimension(serverLevel)) {
			return;
		}
		Entity owner = self.getOwner();
		if (!(owner instanceof Player player)) {
			return;
		}
		BlockPos landing = BlockPos.containing(self.oldPosition());
		IslandView destIsland = resolver.islandAt(serverLevel, landing);
		boolean allowed = resolver.isPlayerAllowed(player, serverLevel, landing, BuiltinFlags.ENDER_PEARL)
			&& (destIsland == null || resolver.canEnterIsland(destIsland, player));
		if (!allowed) {
			resolver.notifyDenied(player, "Ender pearl teleportation is protected at the destination.");
			self.discard();
			ci.cancel();
		}
	}
}
