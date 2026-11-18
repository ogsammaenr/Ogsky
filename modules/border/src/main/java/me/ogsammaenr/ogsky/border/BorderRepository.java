package me.ogsammaenr.ogsky.border;

import me.ogsammaenr.ogsky.core.api.db.DatabaseService;
import me.ogsammaenr.ogsky.core.api.db.ModuleDatabase;
import me.ogsammaenr.ogsky.core.api.db.SchemaMigration;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * SQLite persistence repository for player border preferences in {@code border.db}.
 * Blocking queries run only during server startup/tests; live gameplay mutations run asynchronously.
 */
public final class BorderRepository {
	public static final String MODULE_ID = "border";

	public static final List<SchemaMigration> MIGRATIONS = List.of(
		SchemaMigration.sql(
			1,
			"Create ogsky_border_preferences table",
			"""
			CREATE TABLE IF NOT EXISTS ogsky_border_preferences (
				player_uuid TEXT PRIMARY KEY,
				enabled INTEGER NOT NULL,
				color TEXT NOT NULL,
				updated_at TEXT NOT NULL
			)
			"""
		)
	);

	private final DatabaseService databases;

	public BorderRepository(DatabaseService databases) {
		this.databases = Objects.requireNonNull(databases, "databases");
	}

	/**
	 * Loads all persisted border preferences on server startup.
	 */
	public Map<UUID, PlayerBorderPreferences> loadAllBlocking(BorderColor fallbackColor) {
		Objects.requireNonNull(fallbackColor, "fallbackColor");
		Optional<ModuleDatabase> maybeDb = databases.find(MODULE_ID);
		if (maybeDb.isEmpty()) {
			return Map.of();
		}
		return maybeDb.get().queryBlocking(conn -> {
			Map<UUID, PlayerBorderPreferences> result = new LinkedHashMap<>();
			try (Statement stmt = conn.createStatement();
				 ResultSet rs = stmt.executeQuery(
					 "SELECT player_uuid, enabled, color, updated_at FROM ogsky_border_preferences"
				 )) {
				while (rs.next()) {
					UUID playerUuid = parseUuidOrNull(rs.getString(1));
					if (playerUuid == null) {
						continue;
					}
					boolean enabled = rs.getInt(2) != 0;
					BorderColor color = BorderColor.fromDbKey(rs.getString(3), fallbackColor);
					Instant updatedAt = parseInstantOrNow(rs.getString(4));
					result.put(playerUuid, new PlayerBorderPreferences(playerUuid, enabled, color, updatedAt));
				}
			}
			return result;
		});
	}

	/**
	 * Asynchronously inserts or updates a player's border preference row in {@code border.db}.
	 */
	public CompletableFuture<Void> upsertAsync(PlayerBorderPreferences prefs) {
		Objects.requireNonNull(prefs, "prefs");
		Optional<ModuleDatabase> maybeDb = databases.find(MODULE_ID);
		if (maybeDb.isEmpty()) {
			return CompletableFuture.completedFuture(null);
		}
		return maybeDb.get().executeAsync(conn -> {
			try (PreparedStatement stmt = conn.prepareStatement(
				"""
				INSERT INTO ogsky_border_preferences (player_uuid, enabled, color, updated_at)
				VALUES (?, ?, ?, ?)
				ON CONFLICT(player_uuid) DO UPDATE SET
					enabled = excluded.enabled,
					color = excluded.color,
					updated_at = excluded.updated_at
				"""
			)) {
				stmt.setString(1, prefs.playerUuid().toString());
				stmt.setInt(2, prefs.enabled() ? 1 : 0);
				stmt.setString(3, prefs.color().id());
				stmt.setString(4, prefs.updatedAt().toString());
				stmt.executeUpdate();
			}
		});
	}

	private static UUID parseUuidOrNull(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(raw.trim());
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private static Instant parseInstantOrNow(String raw) {
		if (raw == null || raw.isBlank()) {
			return Instant.now();
		}
		try {
			return Instant.parse(raw);
		} catch (Exception ignored) {
			return Instant.now();
		}
	}
}
