package me.ogsammaenr.ogsky.core.mixin;

import me.ogsammaenr.ogsky.core.api.mutation.EntitySpawnSource;
import me.ogsammaenr.ogsky.core.internal.mutation.CoreWorldMutationService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ArmorStandItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ArmorStandItem.class)
public abstract class ArmorStandItemMixin {
	@Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
	private void ogsky$guardArmorStandPlace(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
		if (!CoreWorldMutationService.hasEntitySpawnGuards()
			|| context.getClickedFace() == Direction.DOWN
			|| !(context.getLevel() instanceof ServerLevel serverLevel)) {
			return;
		}
		CoreWorldMutationService service = CoreWorldMutationService.active();
		if (service == null) {
			return;
		}
		BlockPos blockPos = new BlockPlaceContext(context).getClickedPos();
		ServerPlayer actor = context.getPlayer() instanceof ServerPlayer sp ? sp : null;
		if (!service.evaluateEntitySpawn(
			serverLevel,
			EntityTypes.ARMOR_STAND,
			blockPos.getX() + 0.5,
			blockPos.getY(),
			blockPos.getZ() + 0.5,
			actor,
			EntitySpawnSource.ITEM_USE
		)) {
			cir.setReturnValue(InteractionResult.FAIL);
		}
	}
}
