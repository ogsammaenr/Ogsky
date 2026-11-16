package me.ogsammaenr.ogsky.core.mixin;

import me.ogsammaenr.ogsky.core.api.mutation.EntitySpawnSource;
import me.ogsammaenr.ogsky.core.internal.mutation.CoreWorldMutationService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.EndCrystalItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EndCrystalItem.class)
public abstract class EndCrystalItemMixin {
	@Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
	private void ogsky$guardEndCrystalPlace(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
		if (!CoreWorldMutationService.hasEntitySpawnGuards()) {
			return;
		}
		Level level = context.getLevel();
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		BlockPos pos = context.getClickedPos();
		BlockState blockState = level.getBlockState(pos);
		if (!blockState.is(Blocks.OBSIDIAN) && !blockState.is(Blocks.BEDROCK)) {
			return;
		}
		BlockPos above = pos.above();
		if (!level.isEmptyBlock(above)) {
			return;
		}
		CoreWorldMutationService service = CoreWorldMutationService.active();
		if (service == null) {
			return;
		}
		ServerPlayer actor = context.getPlayer() instanceof ServerPlayer sp ? sp : null;
		if (!service.evaluateEntitySpawn(
			serverLevel,
			EntityTypes.END_CRYSTAL,
			above.getX() + 0.5,
			above.getY(),
			above.getZ() + 0.5,
			actor,
			EntitySpawnSource.ITEM_USE
		)) {
			cir.setReturnValue(InteractionResult.FAIL);
		}
	}
}
