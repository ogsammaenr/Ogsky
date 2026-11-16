package me.ogsammaenr.ogsky.core.mixin;

import me.ogsammaenr.ogsky.core.api.mutation.EntitySpawnSource;
import me.ogsammaenr.ogsky.core.internal.mutation.CoreWorldMutationService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.SpawnEggItemBehavior;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.DispenserBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SpawnEggItemBehavior.class)
public abstract class SpawnEggDispenserMixin {
	@Inject(
		method = "execute",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/entity/EntityType;spawn(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/EntitySpawnReason;ZZ)Lnet/minecraft/world/entity/Entity;"
		),
		cancellable = true
	)
	private void ogsky$guardDispenserSpawnEgg(
		BlockSource source,
		ItemStack dispensed,
		CallbackInfoReturnable<ItemStack> cir
	) {
		if (!CoreWorldMutationService.hasEntitySpawnGuards()) {
			return;
		}
		CoreWorldMutationService service = CoreWorldMutationService.active();
		EntityType<?> type = SpawnEggItem.getType(dispensed);
		if (service == null || type == null) {
			return;
		}
		Direction direction = source.state().getValue(DispenserBlock.FACING);
		BlockPos pos = source.pos().relative(direction);
		if (!service.evaluateEntitySpawn(
			source.level(),
			type,
			pos.getX() + 0.5,
			pos.getY(),
			pos.getZ() + 0.5,
			null,
			EntitySpawnSource.DISPENSER
		)) {
			cir.setReturnValue(dispensed);
		}
	}
}
