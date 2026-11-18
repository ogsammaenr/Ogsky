package me.ogsammaenr.ogsky.panel;

import me.ogsammaenr.ogui.api.Ogui;
import me.ogsammaenr.ogui.runtime.OguiFeedbackSupport;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Routes panel feedback through {@link Ogui#service()} (client toast banner when active, chat + sound fallback otherwise).
 */
final class PanelFeedback {
	private PanelFeedback() {
	}

	static void applyMutationFeedback(ServerPlayer player, boolean success, String message) {
		Ogui.service().sendFeedback(player, success, Component.literal("[Ogsky] " + message));
	}

	static void sendFeedback(ServerPlayer player, boolean success, String message) {
		Ogui.service().sendFeedback(player, success, Component.literal("[Ogsky] " + message));
	}

	static void playClickSound(ServerPlayer player) {
		if (!Ogui.service().isClientRendererAvailable(player)) {
			OguiFeedbackSupport.playClickSound(player);
		}
	}

	static void playErrorSound(ServerPlayer player) {
		OguiFeedbackSupport.playErrorSound(player);
	}
}
