package me.ogsammaenr.ogsky.core.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.ogsammaenr.ogsky.core.api.mutation.EntitySpawnSource;
import me.ogsammaenr.ogsky.core.internal.mutation.CoreWorldMutationService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BoatItem.class)
public abstract class BoatItemMixin {
	@Shadow
	@Final
	private EntityType<? extends AbstractBoat> entityType;

	@WrapOperation(
		method = "use",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/item/BoatItem;getBoat(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/HitResult;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/entity/vehicle/boat/AbstractBoat;"
		)
	)
	private @Nullable AbstractBoat ogsky$guardBoatPlace(
		BoatItem instance,
		Level level,
		HitResult hitResult,
		ItemStack itemStack,
		Player player,
		Operation<@Nullable AbstractBoat> original
	) {
		if (CoreWorldMutationService.hasEntitySpawnGuards() && level instanceof ServerLevel serverLevel) {
			CoreWorldMutationService service = CoreWorldMutationService.active();
			if (service != null) {
				Vec3 location = hitResult.getLocation();
				ServerPlayer actor = player instanceof ServerPlayer sp ? sp : null;
				if (!service.evaluateEntitySpawn(
					serverLevel,
					this.entityType,
					location.x,
					location.y,
					location.z,
					actor,
					EntitySpawnSource.ITEM_USE
				)) {
					return null;
				}
			}
		}
		return original.call(instance, level, hitResult, itemStack, player);
	}
}
