package me.ogsammaenr.ogsky.border;

import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;

/**
 * Supported visual Minecraft WorldBorder color modes.
 */
public enum BorderColor {
	BLUE("blue", "Blue", "minecraft:light_blue_stained_glass"),
	GREEN("green", "Green", "minecraft:lime_stained_glass"),
	RED("red", "Red", "minecraft:red_stained_glass");

	private final String id;
	private final String displayName;
	private final String iconItemId;

	BorderColor(String id, String displayName, String iconItemId) {
		this.id = id;
		this.displayName = displayName;
		this.iconItemId = iconItemId;
	}

	public String id() {
		return id;
	}

	public String displayName() {
		return displayName;
	}

	public String iconItemId() {
		return iconItemId;
	}

	public BorderColor next() {
		BorderColor[] all = values();
		return all[(ordinal() + 1) % all.length];
	}

	public static Optional<BorderColor> parse(@Nullable String raw) {
		if (raw == null || raw.isBlank()) {
			return Optional.empty();
		}
		String normalized = raw.trim().toLowerCase(Locale.ROOT);
		for (BorderColor color : values()) {
			if (color.id.equals(normalized) || color.name().toLowerCase(Locale.ROOT).equals(normalized)) {
				return Optional.of(color);
			}
		}
		return Optional.empty();
	}

	public static BorderColor fromDbKey(@Nullable String raw, BorderColor fallback) {
		return parse(raw).orElse(fallback);
	}
}
