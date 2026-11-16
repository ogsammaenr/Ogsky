package me.ogsammaenr.ogsky.core.mixin;

import me.ogsammaenr.ogsky.core.api.mutation.EntitySpawnSource;
import me.ogsammaenr.ogsky.core.internal.mutation.CoreWorldMutationService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public abstract class ServerLevelAddEntityMixin {
	@Inject(method = "addEntity", at = @At("HEAD"), cancellable = true)
	private void ogsky$guardServerLevelAddEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (!CoreWorldMutationService.hasEntitySpawnGuards() || entity instanceof ServerPlayer) {
			return;
		}
		CoreWorldMutationService service = CoreWorldMutationService.active();
		if (service == null) {
			return;
		}
		if (!service.evaluateEntitySpawn(
			(ServerLevel) (Object) this,
			entity.getType(),
			entity.getX(),
			entity.getY(),
			entity.getZ(),
			service.currentActor(),
			EntitySpawnSource.OTHER
		)) {
			cir.setReturnValue(false);
		}
	}
}
