package me.ogsammaenr.ogsky.core.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.ogsammaenr.ogsky.core.api.mutation.EntitySpawnSource;
import me.ogsammaenr.ogsky.core.internal.mutation.CoreWorldMutationService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MinecartItem;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MinecartItem.class)
public abstract class MinecartItemMixin {
	@WrapOperation(
		method = "useOn",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/entity/vehicle/minecart/AbstractMinecart;createMinecart(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/entity/EntitySpawnReason;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/entity/vehicle/minecart/AbstractMinecart;"
		)
	)
	private @Nullable AbstractMinecart ogsky$guardMinecartPlace(
		Level level,
		double x,
		double y,
		double z,
		EntityType<?> type,
		EntitySpawnReason reason,
		ItemStack stack,
		@Nullable Player player,
		Operation<@Nullable AbstractMinecart> original
	) {
		if (CoreWorldMutationService.hasEntitySpawnGuards() && level instanceof ServerLevel serverLevel) {
			CoreWorldMutationService service = CoreWorldMutationService.active();
			if (service != null) {
				ServerPlayer actor = player instanceof ServerPlayer sp ? sp : null;
				if (!service.evaluateEntitySpawn(serverLevel, type, x, y, z, actor, EntitySpawnSource.ITEM_USE)) {
					return null;
				}
			}
		}
		return original.call(level, x, y, z, type, reason, stack, player);
	}
}
