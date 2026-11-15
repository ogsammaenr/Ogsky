package me.ogsammaenr.ogsky.core.api.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.Objects;

/**
 * Optional two-click confirmation requirement attached to destructive or high-impact {@link GuiButton}s.
 */
public record GuiConfirmSpec(String targetKey, Component promptLine) {
	public GuiConfirmSpec {
		if (targetKey == null || targetKey.isBlank()) {
			throw new IllegalArgumentException("Confirmation targetKey must not be blank.");
		}
		Objects.requireNonNull(promptLine, "promptLine");
	}

	public static GuiConfirmSpec of(String targetKey, String promptText) {
		Objects.requireNonNull(promptText, "promptText");
		return new GuiConfirmSpec(
			targetKey,
			Component.literal(promptText).withStyle(ChatFormatting.YELLOW).withStyle(style -> style.withItalic(false))
		);
	}
}
