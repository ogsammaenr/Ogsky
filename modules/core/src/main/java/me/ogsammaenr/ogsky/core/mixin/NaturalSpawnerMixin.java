package me.ogsammaenr.ogsky.core.mixin;

import me.ogsammaenr.ogsky.core.api.mutation.EntitySpawnSource;
import me.ogsammaenr.ogsky.core.internal.mutation.CoreWorldMutationService;
import me.ogsammaenr.ogsky.core.internal.protection.ProtectionResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Enforces per-island {@code ogsky:animal_spawn} and {@code ogsky:monster_spawn} settings.
 */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {
	@Inject(method = "isValidSpawnPostitionForType", at = @At("HEAD"), cancellable = true)
	private static void ogsky$guardNaturalSpawn(
		ServerLevel level,
		MobCategory mobCategory,
		StructureManager structureManager,
		ChunkGenerator generator,
		MobSpawnSettings.SpawnerData currentSpawnData,
		BlockPos.MutableBlockPos pos,
		double nearestPlayerDistanceSqr,
		CallbackInfoReturnable<Boolean> cir
	) {
		ProtectionResolver resolver = ProtectionResolver.active();
		if (resolver != null && !resolver.isNaturalSpawnAllowed(level, pos, mobCategory)) {
			cir.setReturnValue(false);
			return;
		}
		if (CoreWorldMutationService.hasEntitySpawnGuards()) {
			CoreWorldMutationService service = CoreWorldMutationService.active();
			if (service != null && !service.evaluateEntitySpawn(
				level,
				currentSpawnData.type(),
				pos.getX() + 0.5,
				pos.getY(),
				pos.getZ() + 0.5,
				null,
				EntitySpawnSource.NATURAL
			)) {
				cir.setReturnValue(false);
			}
		}
	}
}
