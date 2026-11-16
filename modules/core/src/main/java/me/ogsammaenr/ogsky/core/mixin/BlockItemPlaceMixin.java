package me.ogsammaenr.ogsky.core.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.ogsammaenr.ogsky.core.api.mutation.BlockMutationDecision;
import me.ogsammaenr.ogsky.core.api.mutation.BlockPlacementSource;
import me.ogsammaenr.ogsky.core.internal.mutation.CoreWorldMutationService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockItem.class)
public abstract class BlockItemPlaceMixin {
	@WrapOperation(
		method = "place",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/item/BlockItem;placeBlock(Lnet/minecraft/world/item/context/BlockPlaceContext;Lnet/minecraft/world/level/block/state/BlockState;)Z"
		)
	)
	private boolean ogsky$guardPlaceBlock(
		BlockItem instance,
		BlockPlaceContext context,
		BlockState placementState,
		Operation<Boolean> original
	) {
		if (!CoreWorldMutationService.hasBlockPlacementGuards() || !(context.getLevel() instanceof ServerLevel serverLevel)) {
			return original.call(instance, context, placementState);
		}
		CoreWorldMutationService service = CoreWorldMutationService.active();
		if (service == null) {
			return original.call(instance, context, placementState);
		}
		BlockPos pos = context.getClickedPos();
		BlockState previousState = serverLevel.getBlockState(pos);
		ServerPlayer actor = context.getPlayer() instanceof ServerPlayer sp ? sp : null;
		BlockPlacementSource source = actor != null ? BlockPlacementSource.PLAYER : BlockPlacementSource.DISPENSER;
		BlockMutationDecision decision = service.evaluateBlockPlacement(
			serverLevel,
			pos,
			placementState,
			previousState,
			actor,
			source
		);
		if (decision.isDenied()) {
			return false;
		}
		BlockState effective = decision.isReplaced() && decision.replacementState() != null
			? decision.replacementState()
			: placementState;
		return original.call(instance, context, effective);
	}
}
