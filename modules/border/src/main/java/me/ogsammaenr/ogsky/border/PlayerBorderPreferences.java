package me.ogsammaenr.ogsky.border;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-player island border preference persisted asynchronously in {@code border.db}.
 */
public record PlayerBorderPreferences(
	UUID playerUuid,
	boolean enabled,
	BorderColor color,
	Instant updatedAt
) {
	public PlayerBorderPreferences {
		Objects.requireNonNull(playerUuid, "playerUuid");
		Objects.requireNonNull(color, "color");
		Objects.requireNonNull(updatedAt, "updatedAt");
	}

	public static PlayerBorderPreferences defaults(UUID playerUuid, BorderConfig config) {
		Objects.requireNonNull(config, "config");
		return new PlayerBorderPreferences(
			playerUuid,
			config.defaultEnabledForPlayers,
			config.defaultColorEnum(),
			Instant.now()
		);
	}

	public PlayerBorderPreferences withEnabled(boolean nextEnabled) {
		return new PlayerBorderPreferences(playerUuid, nextEnabled, color, Instant.now());
	}

	public PlayerBorderPreferences withColor(BorderColor nextColor) {
		return new PlayerBorderPreferences(playerUuid, enabled, nextColor, Instant.now());
	}
}
