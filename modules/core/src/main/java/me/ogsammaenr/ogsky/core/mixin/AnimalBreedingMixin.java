package me.ogsammaenr.ogsky.core.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.ogsammaenr.ogsky.core.api.mutation.EntitySpawnSource;
import me.ogsammaenr.ogsky.core.internal.mutation.CoreWorldMutationService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.animal.Animal;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Animal.class)
public abstract class AnimalBreedingMixin {
	@WrapOperation(
		method = "spawnChildFromBreeding",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/entity/animal/Animal;getBreedOffspring(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/AgeableMob;)Lnet/minecraft/world/entity/AgeableMob;"
		)
	)
	private @Nullable AgeableMob ogsky$guardBreedingOffspring(
		Animal instance,
		ServerLevel level,
		AgeableMob partner,
		Operation<@Nullable AgeableMob> original
	) {
		AgeableMob offspring = original.call(instance, level, partner);
		if (offspring == null || !CoreWorldMutationService.hasEntitySpawnGuards()) {
			return offspring;
		}
		CoreWorldMutationService service = CoreWorldMutationService.active();
		if (service == null) {
			return offspring;
		}
		ServerPlayer loveCause = instance.getLoveCause();
		if (loveCause == null && partner instanceof Animal partnerAnimal) {
			loveCause = partnerAnimal.getLoveCause();
		}
		if (!service.evaluateEntitySpawn(
			level,
			offspring.getType(),
			instance.getX(),
			instance.getY(),
			instance.getZ(),
			loveCause,
			EntitySpawnSource.BREEDING
		)) {
			// Clear love state and apply a brief cooldown so two crowded animals do not loop every tick.
			instance.resetLove();
			instance.setAge(600);
			if (partner instanceof Animal partnerAnimal) {
				partnerAnimal.resetLove();
			}
			partner.setAge(600);
			return null;
		}
		return offspring;
	}
}
