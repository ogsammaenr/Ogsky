package me.ogsammaenr.ogsky.panel;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-player UI preferences persisted asynchronously in {@code panel.db}.
 */
public record PanelPreferences(
	UUID playerUuid,
	String uiMode,
	PanelTab preferredTab,
	FlagModeFilter flagMode,
	Instant updatedAt
) {
	public static final String DEFAULT_UI_MODE = "CHEST_MENU";

	public PanelPreferences {
		Objects.requireNonNull(playerUuid, "playerUuid");
		uiMode = (uiMode == null || uiMode.isBlank()) ? DEFAULT_UI_MODE : uiMode;
		Objects.requireNonNull(preferredTab, "preferredTab");
		Objects.requireNonNull(flagMode, "flagMode");
		Objects.requireNonNull(updatedAt, "updatedAt");
	}

	public static PanelPreferences defaults(UUID playerUuid) {
		return new PanelPreferences(
			playerUuid,
			DEFAULT_UI_MODE,
			PanelTab.ACTIONS,
			FlagModeFilter.BASIC,
			Instant.now()
		);
	}

	public PanelPreferences withTab(PanelTab nextTab) {
		return new PanelPreferences(playerUuid, uiMode, nextTab, flagMode, Instant.now());
	}

	public PanelPreferences withFlagMode(FlagModeFilter nextFlagMode) {
		return new PanelPreferences(playerUuid, uiMode, preferredTab, nextFlagMode, Instant.now());
	}
}
