package me.ogsammaenr.ogsky.core.mixin;

import me.ogsammaenr.ogsky.core.api.mutation.EntitySpawnSource;
import me.ogsammaenr.ogsky.core.internal.mutation.CoreWorldMutationService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.item.HangingEntityItem;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HangingEntityItem.class)
public abstract class HangingEntityItemMixin {
	@Shadow
	@Final
	private EntityType<? extends HangingEntity> type;

	@Inject(
		method = "useOn",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/entity/decoration/HangingEntity;playPlacementSound()V"
		),
		cancellable = true
	)
	private void ogsky$guardHangingEntityPlace(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
		if (!CoreWorldMutationService.hasEntitySpawnGuards() || !(context.getLevel() instanceof ServerLevel serverLevel)) {
			return;
		}
		CoreWorldMutationService service = CoreWorldMutationService.active();
		if (service == null) {
			return;
		}
		BlockPos blockPos = context.getClickedPos().relative(context.getClickedFace());
		ServerPlayer actor = context.getPlayer() instanceof ServerPlayer sp ? sp : null;
		if (!service.evaluateEntitySpawn(
			serverLevel,
			this.type,
			blockPos.getX() + 0.5,
			blockPos.getY() + 0.5,
			blockPos.getZ() + 0.5,
			actor,
			EntitySpawnSource.ITEM_USE
		)) {
			cir.setReturnValue(InteractionResult.FAIL);
		}
	}
}
