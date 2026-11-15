package me.ogsammaenr.ogsky.core.mixin;

import me.ogsammaenr.ogsky.core.internal.flag.BuiltinFlags;
import me.ogsammaenr.ogsky.core.internal.protection.ProtectionResolver;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Enforces {@code ogsky:drop_item} when players drop items in protected Skyblock dimensions.
 */
@Mixin(ServerPlayer.class)
public abstract class PlayerDropMixin {
	@Unique
	private static final ThreadLocal<Boolean> OGSKY_RESTORING_DROP = ThreadLocal.withInitial(() -> Boolean.FALSE);

	@Inject(method = "drop(Z)V", at = @At("HEAD"), cancellable = true)
	private void ogsky$guardDropSelected(boolean all, CallbackInfo ci) {
		ServerPlayer self = (ServerPlayer) (Object) this;
		ProtectionResolver resolver = ProtectionResolver.active();
		if (resolver != null && !resolver.checkAndNotify(self, self.level(), self.blockPosition(), BuiltinFlags.DROP_ITEM)) {
			ci.cancel();
		}
	}

	@Inject(
		method = "drop(Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/util/Prediction;)Lnet/minecraft/world/entity/item/ItemEntity;",
		at = @At("HEAD"),
		cancellable = true
	)
	private void ogsky$guardDropStack(
		ItemStack itemStack,
		boolean thrownFromHand,
		Prediction prediction,
		CallbackInfoReturnable<ItemEntity> cir
	) {
		if (!thrownFromHand || itemStack.isEmpty() || Boolean.TRUE.equals(OGSKY_RESTORING_DROP.get())) {
			return;
		}
		ServerPlayer self = (ServerPlayer) (Object) this;
		ProtectionResolver resolver = ProtectionResolver.active();
		if (resolver != null && !resolver.checkAndNotify(self, self.level(), self.blockPosition(), BuiltinFlags.DROP_ITEM)) {
			OGSKY_RESTORING_DROP.set(Boolean.TRUE);
			try {
				ItemStack restored = itemStack.copy();
				itemStack.setCount(0);
				self.getInventory().placeItemBackInInventory(restored, prediction);
			} finally {
				OGSKY_RESTORING_DROP.set(Boolean.FALSE);
			}
			cir.setReturnValue(null);
		}
	}
}
