package me.ogsammaenr.ogsky.core.mixin;

import me.ogsammaenr.ogsky.core.internal.flag.BuiltinFlags;
import me.ogsammaenr.ogsky.core.internal.protection.ProtectionResolver;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Enforces the {@code ogsky:pickup_item} protection flag when players touch item entities.
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityPickupMixin {
	@Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
	private void ogsky$guardPickupItem(Player player, CallbackInfo ci) {
		ItemEntity self = (ItemEntity) (Object) this;
		if (self.level().isClientSide()) {
			return;
		}
		ProtectionResolver resolver = ProtectionResolver.active();
		if (resolver != null && !resolver.isPlayerAllowed(player, self.level(), self.blockPosition(), BuiltinFlags.PICKUP_ITEM)) {
			ci.cancel();
		}
	}
}
