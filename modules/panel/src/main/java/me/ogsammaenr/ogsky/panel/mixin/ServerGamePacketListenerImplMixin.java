package me.ogsammaenr.ogsky.panel.mixin;

import me.ogsammaenr.ogsky.panel.OgskyPanelMenu;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents client-side offhand/hotbar swap desyncs and Creative-mode panel icon spoofing while
 * {@link OgskyPanelMenu} is open or when a forged creative packet references a marked panel icon.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
	@Shadow
	public ServerPlayer player;

	@Unique
	private AbstractContainerMenu ogsky$preClickMenu;

	@Inject(
		method = "handleContainerClick",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
			shift = At.Shift.AFTER
		)
	)
	private void ogsky$capturePreClickMenu(ServerboundContainerClickPacket packet, CallbackInfo ci) {
		this.ogsky$preClickMenu = this.player.containerMenu;
	}

	@Inject(method = "handleContainerClick", at = @At("TAIL"))
	private void ogsky$enforcePostClickResync(ServerboundContainerClickPacket packet, CallbackInfo ci) {
		AbstractContainerMenu before = this.ogsky$preClickMenu;
		this.ogsky$preClickMenu = null;
		if (this.player.containerMenu instanceof OgskyPanelMenu panelMenu) {
			if (panelMenu.consumeForceResync()) {
				panelMenu.syncClientState(this.player, panelMenu.consumePendingHotbarResync());
			}
			return;
		}
		if (before instanceof OgskyPanelMenu) {
			OgskyPanelMenu.scrubLeakedPanelItems(this.player.getInventory());
			OgskyPanelMenu.syncPlayerInventoryAndOffhand(this.player, -1);
		}
	}

	@Inject(
		method = "handlePlayerAction",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
			shift = At.Shift.AFTER
		),
		cancellable = true
	)
	private void ogsky$blockOffhandSwapWhilePanelOpen(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
		if (!(this.player.containerMenu instanceof OgskyPanelMenu panelMenu)) {
			return;
		}
		ServerboundPlayerActionPacket.Action action = packet.getAction();
		if (action == ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND
			|| action == ServerboundPlayerActionPacket.Action.DROP_ITEM
			|| action == ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS) {
			panelMenu.syncClientState(this.player, -1);
			ci.cancel();
		}
	}

	@Inject(
		method = "handleSetCreativeModeSlot",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
			shift = At.Shift.AFTER
		),
		cancellable = true
	)
	private void ogsky$blockCreativePanelItemInjection(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
		if (this.player.containerMenu instanceof OgskyPanelMenu panelMenu) {
			panelMenu.syncClientState(this.player, -1);
			ci.cancel();
			return;
		}
		if (OgskyPanelMenu.hasPanelItemMarker(packet.itemStack())) {
			OgskyPanelMenu.scrubLeakedPanelItems(this.player.getInventory());
			OgskyPanelMenu.syncPlayerInventoryAndOffhand(this.player, -1);
			ci.cancel();
		}
	}
}
