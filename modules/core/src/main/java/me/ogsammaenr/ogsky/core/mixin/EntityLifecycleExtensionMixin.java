package me.ogsammaenr.ogsky.core.mixin;

import me.ogsammaenr.ogsky.core.internal.mutation.CoreWorldMutationService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityLifecycleExtensionMixin {
	@Shadow
	private Level level;

	@Shadow
	private BlockPos blockPosition;

	@Shadow
	public abstract boolean isRemoved();

	@Inject(method = "removeAfterChangingDimensions", at = @At("HEAD"))
	private void ogsky$notifyRemovedOnDimensionTransfer(CallbackInfo ci) {
		if (!CoreWorldMutationService.hasEntityLifecycleObservers()) {
			return;
		}
		Entity self = (Entity) (Object) this;
		if (self instanceof ServerPlayer || !(this.level instanceof ServerLevel serverLevel)) {
			return;
		}
		CoreWorldMutationService service = CoreWorldMutationService.active();
		if (service != null) {
			service.dispatchEntityRemoved(serverLevel, self);
		}
	}

	@Inject(method = "setPosRaw", at = @At("HEAD"), cancellable = true)
	private void ogsky$notifyEntityColumnTransition(double x, double y, double z, CallbackInfo ci) {
		if (!CoreWorldMutationService.hasEntityLifecycleObservers()) {
			return;
		}
		Entity self = (Entity) (Object) this;
		if (self instanceof ServerPlayer
			|| this.isRemoved()
			|| !(this.level instanceof ServerLevel serverLevel)
			|| ((EntityLevelCallbackAccessor) this).ogsky$getLevelCallback() == EntityInLevelCallback.NULL) {
			return;
		}
		int newBx = Mth.floor(x);
		int newBz = Mth.floor(z);
		int oldBx = this.blockPosition.getX();
		int oldBz = this.blockPosition.getZ();
		if (newBx == oldBx && newBz == oldBz) {
			return;
		}
		CoreWorldMutationService service = CoreWorldMutationService.active();
		if (service != null && !service.dispatchEntityCrossedBlockColumn(serverLevel, self, oldBx, oldBz, newBx, newBz)) {
			ci.cancel();
		}
	}
}
