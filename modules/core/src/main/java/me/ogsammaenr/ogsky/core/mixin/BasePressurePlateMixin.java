package me.ogsammaenr.ogsky.core.mixin;

import me.ogsammaenr.ogsky.core.internal.flag.BuiltinFlags;
import me.ogsammaenr.ogsky.core.internal.protection.ProtectionResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Enforces {@code ogsky:pressure_plate} when players step onto pressure plates.
 */
@Mixin(BasePressurePlateBlock.class)
public abstract class BasePressurePlateMixin {
	@Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
	private void ogsky$guardPressurePlate(
		BlockState state,
		Level level,
		BlockPos pos,
		Entity entity,
		InsideBlockEffectApplier effectApplier,
		boolean isPrecise,
		CallbackInfo ci
	) {
		if (level.isClientSide() || !(entity instanceof Player player)) {
			return;
		}
		ProtectionResolver resolver = ProtectionResolver.active();
		if (resolver != null && !resolver.isPlayerAllowed(player, level, pos, BuiltinFlags.PRESSURE_PLATE)) {
			ci.cancel();
		}
	}
}
