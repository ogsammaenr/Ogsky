package me.ogsammaenr.ogsky.core.mixin;

import me.ogsammaenr.ogsky.core.api.mutation.EntitySpawnSource;
import me.ogsammaenr.ogsky.core.internal.mutation.CoreWorldMutationService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CarvedPumpkinBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CarvedPumpkinBlock.class)
public abstract class CarvedPumpkinGolemMixin {
	@Inject(
		method = "trySpawnGolem",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/entity/EntityType;create(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/EntitySpawnReason;)Lnet/minecraft/world/entity/Entity;",
			ordinal = 0
		),
		cancellable = true
	)
	private void ogsky$guardSnowGolemSpawn(Level level, BlockPos topPos, CallbackInfo ci) {
		guardStructureSpawn(level, topPos, EntityTypes.SNOW_GOLEM, ci);
	}

	@Inject(
		method = "trySpawnGolem",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/entity/EntityType;create(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/EntitySpawnReason;)Lnet/minecraft/world/entity/Entity;",
			ordinal = 1
		),
		cancellable = true
	)
	private void ogsky$guardIronGolemSpawn(Level level, BlockPos topPos, CallbackInfo ci) {
		guardStructureSpawn(level, topPos, EntityTypes.IRON_GOLEM, ci);
	}

	@Inject(
		method = "trySpawnGolem",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/entity/EntityType;create(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/EntitySpawnReason;)Lnet/minecraft/world/entity/Entity;",
			ordinal = 2
		),
		cancellable = true
	)
	private void ogsky$guardCopperGolemSpawn(Level level, BlockPos topPos, CallbackInfo ci) {
		guardStructureSpawn(level, topPos, EntityTypes.COPPER_GOLEM, ci);
	}

	private static void guardStructureSpawn(Level level, BlockPos topPos, EntityType<?> entityType, CallbackInfo ci) {
		if (!CoreWorldMutationService.hasEntitySpawnGuards() || !(level instanceof ServerLevel serverLevel)) {
			return;
		}
		CoreWorldMutationService service = CoreWorldMutationService.active();
		if (service == null) {
			return;
		}
		if (!service.evaluateEntitySpawn(
			serverLevel,
			entityType,
			topPos.getX() + 0.5,
			topPos.getY(),
			topPos.getZ() + 0.5,
			service.currentActor(),
			EntitySpawnSource.STRUCTURE_SUMMON
		)) {
			ci.cancel();
		}
	}
}
