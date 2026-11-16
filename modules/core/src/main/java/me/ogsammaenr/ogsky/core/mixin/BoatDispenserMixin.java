package me.ogsammaenr.ogsky.core.mixin;

import me.ogsammaenr.ogsky.core.api.mutation.EntitySpawnSource;
import me.ogsammaenr.ogsky.core.internal.mutation.CoreWorldMutationService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.BoatDispenseItemBehavior;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DispenserBlock;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BoatDispenseItemBehavior.class)
public abstract class BoatDispenserMixin {
	@Shadow
	@Final
	private EntityType<? extends AbstractBoat> type;

	@Inject(
		method = "execute",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/entity/EntityType;create(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/EntitySpawnReason;)Lnet/minecraft/world/entity/Entity;"
		),
		cancellable = true
	)
	private void ogsky$guardDispenserBoat(
		BlockSource source,
		ItemStack dispensed,
		CallbackInfoReturnable<ItemStack> cir
	) {
		if (!CoreWorldMutationService.hasEntitySpawnGuards()) {
			return;
		}
		CoreWorldMutationService service = CoreWorldMutationService.active();
		if (service == null) {
			return;
		}
		Direction direction = source.state().getValue(DispenserBlock.FACING);
		BlockPos front = source.pos().relative(direction);
		if (!service.evaluateEntitySpawn(
			source.level(),
			this.type,
			front.getX() + 0.5,
			front.getY(),
			front.getZ() + 0.5,
			null,
			EntitySpawnSource.DISPENSER
		)) {
			cir.setReturnValue(dispensed);
		}
	}
}
