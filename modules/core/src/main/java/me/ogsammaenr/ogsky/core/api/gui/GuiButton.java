package me.ogsammaenr.ogsky.core.api.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * UI-agnostic declarative button item rendered inside a {@link GuiScreenModel}.
 */
public record GuiButton(
	String iconItemId,
	@Nullable UUID playerHeadUuid,
	int count,
	Component name,
	List<Component> lore,
	boolean glowing,
	@Nullable GuiConfirmSpec confirmSpec,
	@Nullable Consumer<GuiClickContext> onClick
) {
	public GuiButton {
		if (iconItemId == null || iconItemId.isBlank()) {
			throw new IllegalArgumentException("GuiButton iconItemId must not be blank.");
		}
		count = Math.clamp(count, 1, 99);
		Objects.requireNonNull(name, "name");
		lore = lore == null ? List.of() : List.copyOf(lore);
	}

	public static Builder builder(String iconItemId, Component name) {
		return new Builder(iconItemId, name);
	}

	public static Builder builder(String iconItemId, String title, ChatFormatting color) {
		Objects.requireNonNull(title, "title");
		Objects.requireNonNull(color, "color");
		return new Builder(iconItemId, nonItalic(title, color));
	}

	public static Builder builder(String iconItemId, String title) {
		return builder(iconItemId, title, ChatFormatting.AQUA);
	}

	public static Builder playerHead(UUID playerUuid, String title, ChatFormatting color) {
		Objects.requireNonNull(playerUuid, "playerUuid");
		return builder("minecraft:player_head", title, color).playerHeadUuid(playerUuid);
	}

	public static GuiButton of(String iconItemId, String title, ChatFormatting color) {
		return builder(iconItemId, title, color).build();
	}

	public static Component nonItalic(String text, ChatFormatting formatting) {
		return Component.literal(text)
			.withStyle(formatting)
			.withStyle(style -> style.withItalic(false));
	}

	public static final class Builder {
		private final String iconItemId;
		private final Component name;
		private @Nullable UUID playerHeadUuid;
		private int count = 1;
		private final List<Component> lore = new ArrayList<>();
		private boolean glowing;
		private @Nullable GuiConfirmSpec confirmSpec;
		private @Nullable Consumer<GuiClickContext> onClick;

		private Builder(String iconItemId, Component name) {
			this.iconItemId = iconItemId;
			this.name = Objects.requireNonNull(name, "name");
		}

		public Builder playerHeadUuid(@Nullable UUID playerHeadUuid) {
			this.playerHeadUuid = playerHeadUuid;
			return this;
		}

		public Builder count(int count) {
			this.count = count;
			return this;
		}

		public Builder lore(Component... lines) {
			for (Component line : lines) {
				if (line != null) {
					this.lore.add(line);
				}
			}
			return this;
		}

		public Builder lore(List<Component> lines) {
			for (Component line : lines) {
				if (line != null) {
					this.lore.add(line);
				}
			}
			return this;
		}

		public Builder loreLine(String text, ChatFormatting color) {
			Objects.requireNonNull(text, "text");
			Objects.requireNonNull(color, "color");
			this.lore.add(nonItalic(text, color));
			return this;
		}

		public Builder glowing(boolean glowing) {
			this.glowing = glowing;
			return this;
		}

		public Builder confirm(String targetKey, String promptText) {
			this.confirmSpec = GuiConfirmSpec.of(targetKey, promptText);
			return this;
		}

		public Builder confirm(@Nullable GuiConfirmSpec confirmSpec) {
			this.confirmSpec = confirmSpec;
			return this;
		}

		public Builder onClick(@Nullable Consumer<GuiClickContext> handler) {
			this.onClick = handler;
			return this;
		}

		public GuiButton build() {
			return new GuiButton(
				this.iconItemId,
				this.playerHeadUuid,
				this.count,
				this.name,
				this.lore,
				this.glowing,
				this.confirmSpec,
				this.onClick
			);
		}
	}
}
