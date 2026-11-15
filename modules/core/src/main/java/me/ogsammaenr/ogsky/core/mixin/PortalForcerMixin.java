package me.ogsammaenr.ogsky.core.mixin;

import me.ogsammaenr.ogsky.core.internal.world.PortalRouter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.portal.PortalForcer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PortalForcer.class)
public abstract class PortalForcerMixin {
	@Redirect(
		method = "createPortal",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/server/level/ServerLevel;getWorldBorder()Lnet/minecraft/world/level/border/WorldBorder;"
		)
	)
	private WorldBorder ogsky$clampPortalForcerWorldBorder(ServerLevel level) {
		WorldBorder override = PortalRouter.activePortalWorldBorder();
		return override != null ? override : level.getWorldBorder();
	}
}
