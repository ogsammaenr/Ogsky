package me.ogsammaenr.ogsky.panel;

import me.ogsammaenr.ogsky.core.api.flag.FlagMode;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * UI flag visibility filter matching BentoBox's basic vs expert/advanced toggle.
 */
public enum FlagModeFilter {
	BASIC("basic", "Basic"),
	ADVANCED("advanced", "Advanced");

	private final String id;
	private final String displayName;

	FlagModeFilter(String id, String displayName) {
		this.id = id;
		this.displayName = displayName;
	}

	public String id() {
		return id;
	}

	public String displayName() {
		return displayName;
	}

	public boolean matches(FlagMode flagMode) {
		return this == ADVANCED || flagMode == FlagMode.BASIC;
	}

	public FlagModeFilter next() {
		return this == BASIC ? ADVANCED : BASIC;
	}

	public static FlagModeFilter fromDbKey(@Nullable String raw) {
		if (raw == null || raw.isBlank()) {
			return BASIC;
		}
		String normalized = raw.trim().toLowerCase(Locale.ROOT);
		return switch (normalized) {
			case "advanced", "expert" -> ADVANCED;
			default -> BASIC;
		};
	}
}
