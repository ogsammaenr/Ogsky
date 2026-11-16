package me.ogsammaenr.ogsky.core.mixin;

import me.ogsammaenr.ogsky.core.internal.mutation.CoreWorldMutationService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.server.level.ServerLevel$EntityCallbacks")
public abstract class ServerLevelEntityCallbacksMixin {
	@Shadow
	@Final
	ServerLevel this$0;

	@Inject(method = "onCreated(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"))
	private void ogsky$notifyEntityCreated(Entity entity, CallbackInfo ci) {
		if (!CoreWorldMutationService.hasEntityLifecycleObservers() || entity instanceof ServerPlayer) {
			return;
		}
		CoreWorldMutationService service = CoreWorldMutationService.active();
		if (service != null) {
			service.dispatchEntityAdded(this.this$0, entity);
		}
	}

	@Inject(method = "onDestroyed(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"))
	private void ogsky$notifyEntityDestroyed(Entity entity, CallbackInfo ci) {
		if (!CoreWorldMutationService.hasEntityLifecycleObservers() || entity instanceof ServerPlayer) {
			return;
		}
		CoreWorldMutationService service = CoreWorldMutationService.active();
		if (service != null) {
			service.dispatchEntityRemoved(this.this$0, entity);
		}
	}
}
