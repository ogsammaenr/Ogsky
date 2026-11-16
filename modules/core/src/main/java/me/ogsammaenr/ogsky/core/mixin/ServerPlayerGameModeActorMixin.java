package me.ogsammaenr.ogsky.core.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.ogsammaenr.ogsky.core.internal.mutation.CoreWorldMutationService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeActorMixin {
	@Shadow
	@Final
	protected ServerPlayer player;

	@WrapOperation(
		method = "useItem",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/item/ItemStack;use(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;"
		)
	)
	private InteractionResult ogsky$trackUseItemActor(
		ItemStack instance,
		Level level,
		Player user,
		InteractionHand hand,
		Operation<InteractionResult> original
	) {
		CoreWorldMutationService service = CoreWorldMutationService.active();
		if (service == null) {
			return original.call(instance, level, user, hand);
		}
		ServerPlayer actor = user instanceof ServerPlayer sp ? sp : this.player;
		ServerPlayer previous = service.pushActor(actor);
		try {
			return original.call(instance, level, user, hand);
		} finally {
			service.popActor(previous);
		}
	}

	@WrapOperation(
		method = "useItemOn",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/item/ItemStack;useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;"
		)
	)
	private InteractionResult ogsky$trackUseItemOnActor(
		ItemStack instance,
		UseOnContext context,
		Operation<InteractionResult> original
	) {
		CoreWorldMutationService service = CoreWorldMutationService.active();
		if (service == null) {
			return original.call(instance, context);
		}
		ServerPlayer actor = context.getPlayer() instanceof ServerPlayer sp ? sp : this.player;
		ServerPlayer previous = service.pushActor(actor);
		try {
			return original.call(instance, context);
		} finally {
			service.popActor(previous);
		}
	}
}
