package me.ogsammaenr.ogsky.core.mixin;

import me.ogsammaenr.ogsky.core.api.mutation.BlockMutationDecision;
import me.ogsammaenr.ogsky.core.api.mutation.BlockPlacementSource;
import me.ogsammaenr.ogsky.core.internal.mutation.CoreWorldMutationService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.SolidBucketItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SolidBucketItem.class)
public abstract class SolidBucketItemMixin extends BlockItem {
	protected SolidBucketItemMixin(net.minecraft.world.level.block.Block block, Properties properties) {
		super(block, properties);
	}

	@Inject(method = "emptyContents", at = @At("HEAD"), cancellable = true)
	private void ogsky$guardEmptySolidBucket(
		@Nullable LivingEntity user,
		Level level,
		BlockPos pos,
		@Nullable BlockHitResult hitResult,
		CallbackInfoReturnable<Boolean> cir
	) {
		if (!CoreWorldMutationService.hasBlockPlacementGuards() || !(level instanceof ServerLevel serverLevel)) {
			return;
		}
		if (!level.isInWorldBounds(pos) || !level.isEmptyBlock(pos)) {
			return;
		}
		CoreWorldMutationService service = CoreWorldMutationService.active();
		if (service == null) {
			return;
		}
		ServerPlayer actor = user instanceof ServerPlayer sp ? sp : null;
		BlockPlacementSource source = actor != null ? BlockPlacementSource.PLAYER : BlockPlacementSource.DISPENSER;
		BlockMutationDecision decision = service.evaluateBlockPlacement(
			serverLevel,
			pos,
			this.getBlock().defaultBlockState(),
			level.getBlockState(pos),
			actor,
			source
		);
		if (decision.isDenied()) {
			cir.setReturnValue(false);
		}
	}
}
