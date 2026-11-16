package me.ogsammaenr.ogsky.core.mixin;

import me.ogsammaenr.ogsky.core.api.mutation.EntitySpawnSource;
import me.ogsammaenr.ogsky.core.internal.mutation.CoreWorldMutationService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.MobBucketItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MobBucketItem.class)
public abstract class MobBucketItemEmptyMixin extends BucketItem {
	protected MobBucketItemEmptyMixin(Fluid content, Properties properties) {
		super(content, properties);
	}

	@Inject(method = "emptyContents", at = @At("HEAD"), cancellable = true)
	private void ogsky$guardEmptyMobBucketWithoutFluid(
		@Nullable LivingEntity user,
		Level level,
		BlockPos pos,
		@Nullable BlockHitResult hitResult,
		CallbackInfoReturnable<Boolean> cir
	) {
		if (!CoreWorldMutationService.hasEntitySpawnGuards() || !(level instanceof ServerLevel serverLevel)) {
			return;
		}
		if (this.getContent() != Fluids.EMPTY) {
			return;
		}
		CoreWorldMutationService service = CoreWorldMutationService.active();
		if (service == null) {
			return;
		}
		ServerPlayer actor = user instanceof ServerPlayer sp ? sp : null;
		EntitySpawnSource source = actor != null ? EntitySpawnSource.ITEM_USE : EntitySpawnSource.DISPENSER;
		if (!service.evaluateEntitySpawn(
			serverLevel,
			((MobBucketItemAccessor) this).ogsky$getEntityType(),
			pos.getX() + 0.5,
			pos.getY(),
			pos.getZ() + 0.5,
			actor,
			source
		)) {
			cir.setReturnValue(false);
		}
	}
}
