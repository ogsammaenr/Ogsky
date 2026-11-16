package me.ogsammaenr.ogsky.core.mixin;

import me.ogsammaenr.ogsky.core.api.mutation.BlockMutationDecision;
import me.ogsammaenr.ogsky.core.api.mutation.BlockPlacementSource;
import me.ogsammaenr.ogsky.core.api.mutation.EntitySpawnSource;
import me.ogsammaenr.ogsky.core.internal.mutation.CoreWorldMutationService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BucketItem.class)
public abstract class BucketItemEmptyMixin {
	@Shadow
	@Final
	private Fluid content;

	@Inject(method = "emptyContents", at = @At("HEAD"), cancellable = true)
	private void ogsky$guardEmptyBucket(
		@Nullable LivingEntity user,
		Level level,
		BlockPos pos,
		@Nullable BlockHitResult hitResult,
		CallbackInfoReturnable<Boolean> cir
	) {
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		if (!(this.content instanceof FlowingFluid)) {
			return;
		}
		boolean checkEntity = CoreWorldMutationService.hasEntitySpawnGuards() && this instanceof MobBucketItemAccessor;
		boolean checkBlock = CoreWorldMutationService.hasBlockPlacementGuards();
		if (!checkEntity && !checkBlock) {
			return;
		}

		BlockState blockState = level.getBlockState(pos);
		Block block = blockState.getBlock();
		boolean mayReplace = blockState.canBeReplaced(this.content);
		boolean shiftKeyDown = user != null && user.isShiftKeyDown();
		boolean placeLiquid = mayReplace || block instanceof LiquidBlockContainer container && container.canPlaceLiquid(user, level, pos, blockState, this.content);
		boolean canPlaceFluidInsideBlock = blockState.isAir() || placeLiquid && (!shiftKeyDown || hitResult == null);
		if (!canPlaceFluidInsideBlock) {
			// Vanilla delegates to pos.relative(hitResult.getDirection()), which will enter this @Inject again.
			return;
		}

		CoreWorldMutationService service = CoreWorldMutationService.active();
		if (service == null) {
			return;
		}
		ServerPlayer actor = user instanceof ServerPlayer sp ? sp : null;

		if (checkEntity && this instanceof MobBucketItemAccessor mobBucket) {
			EntitySpawnSource spawnSource = actor != null ? EntitySpawnSource.ITEM_USE : EntitySpawnSource.DISPENSER;
			if (!service.evaluateEntitySpawn(
				serverLevel,
				mobBucket.ogsky$getEntityType(),
				pos.getX() + 0.5,
				pos.getY(),
				pos.getZ() + 0.5,
				actor,
				spawnSource
			)) {
				cir.setReturnValue(false);
				return;
			}
		}

		if (!checkBlock) {
			return;
		}

		if (level.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, pos) && this.content.is(FluidTags.WATER)) {
			return;
		}

		BlockPlacementSource placeSource = actor != null ? BlockPlacementSource.PLAYER : BlockPlacementSource.DISPENSER;
		if (!(block instanceof LiquidBlockContainer && this.content == Fluids.WATER)) {
			BlockState fluidBlockState = this.content.defaultFluidState().createLegacyBlock();
			BlockMutationDecision fluidDecision = service.evaluateBlockPlacement(
				serverLevel,
				pos,
				fluidBlockState,
				blockState,
				actor,
				placeSource
			);
			if (fluidDecision.isDenied()) {
				cir.setReturnValue(false);
				return;
			}
		}

		// Pre-check immediate lava<->water and lava+blue_ice+soul_soil generator outcomes before mutating world/bucket.
		if (this.content.is(FluidTags.LAVA)) {
			boolean overSoulSoil = serverLevel.getBlockState(pos.below()).is(Blocks.SOUL_SOIL);
			for (Direction direction : LiquidBlock.POSSIBLE_FLOW_DIRECTIONS) {
				BlockPos neighbourPos = pos.relative(direction.getOpposite());
				if (serverLevel.getFluidState(neighbourPos).is(FluidTags.WATER)) {
					if (service.evaluateBlockPlacement(
						serverLevel,
						pos,
						Blocks.OBSIDIAN.defaultBlockState(),
						blockState,
						actor,
						BlockPlacementSource.LIQUID_GENERATOR
					).isDenied()) {
						cir.setReturnValue(false);
						return;
					}
					break;
				}
				if (overSoulSoil && serverLevel.getBlockState(neighbourPos).is(Blocks.BLUE_ICE)) {
					if (service.evaluateBlockPlacement(
						serverLevel,
						pos,
						Blocks.BASALT.defaultBlockState(),
						blockState,
						actor,
						BlockPlacementSource.LIQUID_GENERATOR
					).isDenied()) {
						cir.setReturnValue(false);
						return;
					}
					break;
				}
			}
		} else if (this.content.is(FluidTags.WATER)) {
			for (Direction direction : LiquidBlock.POSSIBLE_FLOW_DIRECTIONS) {
				BlockPos lavaPos = pos.relative(direction);
				if (serverLevel.getFluidState(lavaPos).is(FluidTags.LAVA)) {
					Block targetBlock = serverLevel.getFluidState(lavaPos).isSource() ? Blocks.OBSIDIAN : Blocks.COBBLESTONE;
					if (service.evaluateBlockPlacement(
						serverLevel,
						lavaPos,
						targetBlock.defaultBlockState(),
						serverLevel.getBlockState(lavaPos),
						actor,
						BlockPlacementSource.LIQUID_GENERATOR
					).isDenied()) {
						cir.setReturnValue(false);
						return;
					}
				}
			}
			BlockPos abovePos = pos.above();
			if (serverLevel.getFluidState(abovePos).is(FluidTags.LAVA)) {
				if (service.evaluateBlockPlacement(
					serverLevel,
					pos,
					Blocks.STONE.defaultBlockState(),
					blockState,
					actor,
					BlockPlacementSource.LIQUID_GENERATOR
				).isDenied()) {
					cir.setReturnValue(false);
				}
			}
		}
	}
}
