package me.ogsammaenr.ogsky.panel;

import me.ogsammaenr.ogsky.core.api.db.DatabaseService;
import me.ogsammaenr.ogsky.core.api.db.ModuleDatabase;
import me.ogsammaenr.ogsky.core.api.db.SchemaMigration;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Async SQLite repository + RAM cache for player panel UI preferences in {@code panel.db}.
 * Never performs blocking SQLite I/O on the server tick thread.
 */
public final class PanelPreferenceRepository {
	public static final String MODULE_ID = "panel";

	public static final List<SchemaMigration> MIGRATIONS = List.of(
		SchemaMigration.sql(
			1,
			"Initialize player panel preferences table",
			"""
			CREATE TABLE IF NOT EXISTS panel_preferences (
				player_uuid TEXT PRIMARY KEY,
				ui_mode TEXT NOT NULL,
				updated_at TEXT NOT NULL
			)
			"""
		),
		SchemaMigration.sql(
			2,
			"Add preferred_tab and flag_mode columns to panel_preferences",
			"ALTER TABLE panel_preferences ADD COLUMN preferred_tab TEXT NOT NULL DEFAULT 'actions'",
			"ALTER TABLE panel_preferences ADD COLUMN flag_mode TEXT NOT NULL DEFAULT 'basic'"
		)
	);

	private final DatabaseService databases;
	private final Map<UUID, PanelPreferences> cache = new ConcurrentHashMap<>();
	private final Map<UUID, CompletableFuture<PanelPreferences>> inFlightLoads = new ConcurrentHashMap<>();

	public PanelPreferenceRepository(DatabaseService databases) {
		this.databases = Objects.requireNonNull(databases, "databases");
	}

	public Optional<PanelPreferences> cached(UUID playerUuid) {
		Objects.requireNonNull(playerUuid, "playerUuid");
		return Optional.ofNullable(cache.get(playerUuid));
	}

	/**
	 * Loads a player's preferences from RAM cache or asynchronously from {@code panel.db},
	 * inserting default values if no row exists yet.
	 */
	public CompletableFuture<PanelPreferences> loadOrCreateAsync(UUID playerUuid) {
		Objects.requireNonNull(playerUuid, "playerUuid");
		PanelPreferences cached = cache.get(playerUuid);
		if (cached != null) {
			return CompletableFuture.completedFuture(cached);
		}
		Optional<ModuleDatabase> maybeDb = databases.find(MODULE_ID);
		if (maybeDb.isEmpty()) {
			PanelPreferences defaults = PanelPreferences.defaults(playerUuid);
			cache.putIfAbsent(playerUuid, defaults);
			return CompletableFuture.completedFuture(cache.get(playerUuid));
		}

		ModuleDatabase db = maybeDb.get();
		return inFlightLoads.computeIfAbsent(playerUuid, id -> {
			CompletableFuture<PanelPreferences> future = db.queryAsync(conn -> {
				try (PreparedStatement select = conn.prepareStatement(
					"SELECT ui_mode, preferred_tab, flag_mode, updated_at FROM panel_preferences WHERE player_uuid = ?"
				)) {
					select.setString(1, id.toString());
					try (ResultSet rs = select.executeQuery()) {
						if (rs.next()) {
							String uiMode = rs.getString(1);
							PanelTab tab = PanelTab.fromDbKey(rs.getString(2));
							FlagModeFilter flagMode = FlagModeFilter.fromDbKey(rs.getString(3));
							Instant updatedAt = parseInstantOrNow(rs.getString(4));
							return new PanelPreferences(id, uiMode, tab, flagMode, updatedAt);
						}
					}
				}
				PanelPreferences defaults = PanelPreferences.defaults(id);
				try (PreparedStatement insert = conn.prepareStatement(
					"""
					INSERT OR IGNORE INTO panel_preferences (player_uuid, ui_mode, preferred_tab, flag_mode, updated_at)
					VALUES (?, ?, ?, ?, ?)
					"""
				)) {
					insert.setString(1, id.toString());
					insert.setString(2, defaults.uiMode());
					insert.setString(3, defaults.preferredTab().id());
					insert.setString(4, defaults.flagMode().id());
					insert.setString(5, defaults.updatedAt().toString());
					insert.executeUpdate();
				}
				return defaults;
			});
			future.whenComplete((loaded, error) -> {
				inFlightLoads.remove(id, future);
				if (error == null && loaded != null) {
					cache.putIfAbsent(id, loaded);
				}
			});
			return future;
		});
	}

	public CompletableFuture<PanelPreferences> saveTabAsync(UUID playerUuid, PanelTab tab) {
		Objects.requireNonNull(playerUuid, "playerUuid");
		Objects.requireNonNull(tab, "tab");
		PanelPreferences current = cache.getOrDefault(playerUuid, PanelPreferences.defaults(playerUuid));
		PanelPreferences updated = current.withTab(tab);
		return saveAsync(updated);
	}

	public CompletableFuture<PanelPreferences> saveFlagModeAsync(UUID playerUuid, FlagModeFilter flagMode) {
		Objects.requireNonNull(playerUuid, "playerUuid");
		Objects.requireNonNull(flagMode, "flagMode");
		PanelPreferences current = cache.getOrDefault(playerUuid, PanelPreferences.defaults(playerUuid));
		PanelPreferences updated = current.withFlagMode(flagMode);
		return saveAsync(updated);
	}

	public CompletableFuture<PanelPreferences> saveAsync(PanelPreferences preferences) {
		Objects.requireNonNull(preferences, "preferences");
		cache.put(preferences.playerUuid(), preferences);
		Optional<ModuleDatabase> maybeDb = databases.find(MODULE_ID);
		if (maybeDb.isEmpty()) {
			return CompletableFuture.completedFuture(preferences);
		}
		return maybeDb.get().executeAsync(conn -> {
			try (PreparedStatement upsert = conn.prepareStatement(
				"""
				INSERT INTO panel_preferences (player_uuid, ui_mode, preferred_tab, flag_mode, updated_at)
				VALUES (?, ?, ?, ?, ?)
				ON CONFLICT(player_uuid) DO UPDATE SET
					ui_mode = excluded.ui_mode,
					preferred_tab = excluded.preferred_tab,
					flag_mode = excluded.flag_mode,
					updated_at = excluded.updated_at
				"""
			)) {
				upsert.setString(1, preferences.playerUuid().toString());
				upsert.setString(2, preferences.uiMode());
				upsert.setString(3, preferences.preferredTab().id());
				upsert.setString(4, preferences.flagMode().id());
				upsert.setString(5, preferences.updatedAt().toString());
				upsert.executeUpdate();
			}
		}).thenApply(ignored -> preferences);
	}

	public void evict(UUID playerUuid) {
		if (playerUuid != null) {
			cache.remove(playerUuid);
			inFlightLoads.remove(playerUuid);
		}
	}

	public void clearCache() {
		cache.clear();
		inFlightLoads.clear();
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
