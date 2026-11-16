package me.ogsammaenr.ogsky.core.mixin;

import me.ogsammaenr.ogsky.core.api.mutation.EntitySpawnSource;
import me.ogsammaenr.ogsky.core.internal.mutation.CoreWorldMutationService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.WitherSkullBlock;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WitherSkullBlock.class)
public abstract class WitherSkullSpawnMixin {
	@Inject(
		method = "checkSpawn(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/SkullBlockEntity;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/entity/EntityType;create(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/EntitySpawnReason;)Lnet/minecraft/world/entity/Entity;"
		),
		cancellable = true
	)
	private static void ogsky$guardWitherSpawn(
		Level level,
		BlockPos pos,
		SkullBlockEntity placedSkull,
		CallbackInfo ci
	) {
		if (!CoreWorldMutationService.hasEntitySpawnGuards() || !(level instanceof ServerLevel serverLevel)) {
			return;
		}
		CoreWorldMutationService service = CoreWorldMutationService.active();
		if (service == null) {
			return;
		}
		if (!service.evaluateEntitySpawn(
			serverLevel,
			EntityTypes.WITHER,
			pos.getX() + 0.5,
			pos.getY(),
			pos.getZ() + 0.5,
			service.currentActor(),
			EntitySpawnSource.STRUCTURE_SUMMON
		)) {
			ci.cancel();
		}
	}
}
