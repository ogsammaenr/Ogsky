package me.ogsammaenr.ogsky.core.mixin;

import me.ogsammaenr.ogsky.core.internal.world.OgskyDimensions;
import me.ogsammaenr.ogsky.core.internal.world.PortalRouter;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EndPortalBlock.class)
public abstract class EndPortalBlockMixin {
	@Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
	private void ogsky$routeEndPortal(
		ServerLevel currentLevel,
		Entity entity,
		BlockPos portalEntryPos,
		CallbackInfoReturnable<TeleportTransition> cir
	) {
		if (!OgskyDimensions.isSkyblockDimension(currentLevel.dimension())) {
			return;
		}
		PortalRouter router = PortalRouter.active();
		cir.setReturnValue(router == null ? null : router.routeEndPortal(currentLevel, entity, portalEntryPos));
	}
}
