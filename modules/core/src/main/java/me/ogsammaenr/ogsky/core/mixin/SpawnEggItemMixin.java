package me.ogsammaenr.ogsky.core.mixin;

import me.ogsammaenr.ogsky.core.api.mutation.EntitySpawnSource;
import me.ogsammaenr.ogsky.core.internal.mutation.CoreWorldMutationService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SpawnEggItem.class)
public abstract class SpawnEggItemMixin {
	@Inject(method = "spawnMob", at = @At("HEAD"), cancellable = true)
	private static void ogsky$guardSpawnEgg(
		EntityType<?> type,
		@Nullable LivingEntity user,
		ItemStack itemStack,
		ServerLevel level,
		BlockPos spawnPos,
		boolean tryMoveDown,
		boolean movedUp,
		CallbackInfoReturnable<InteractionResult> cir
	) {
		if (!CoreWorldMutationService.hasEntitySpawnGuards()) {
			return;
		}
		CoreWorldMutationService service = CoreWorldMutationService.active();
		if (service == null) {
			return;
		}
		ServerPlayer actor = user instanceof ServerPlayer sp ? sp : null;
		if (!service.evaluateEntitySpawn(
			level,
			type,
			spawnPos.getX() + 0.5,
			spawnPos.getY(),
			spawnPos.getZ() + 0.5,
			actor,
			EntitySpawnSource.ITEM_USE
		)) {
			cir.setReturnValue(InteractionResult.FAIL);
		}
	}
}
