package me.ogsammaenr.ogsky.level;

import me.ogsammaenr.ogsky.core.api.OgskyCoreApi;
import me.ogsammaenr.ogsky.core.api.db.ModuleDatabase;
import me.ogsammaenr.ogsky.core.api.db.SchemaMigration;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Manages island level persistence in the isolated {@code level.db} SQLite database.
 */
public final class IslandLevelService {
	public static final String MODULE_DB_ID = "level";

	public static final List<SchemaMigration> MIGRATIONS = List.of(
		SchemaMigration.sql(
			1,
			"Initialize island_levels table",
			"""
			CREATE TABLE IF NOT EXISTS island_levels (
				island_id TEXT PRIMARY KEY,
				level_value INTEGER NOT NULL,
				block_points INTEGER NOT NULL,
				updated_at TEXT NOT NULL
			)
			""",
			"CREATE INDEX IF NOT EXISTS idx_island_levels_rank ON island_levels(level_value DESC, block_points DESC)"
		)
	);

	private final OgskyCoreApi core;

	public IslandLevelService(OgskyCoreApi core) {
		this.core = Objects.requireNonNull(core, "core");
	}

	public CompletableFuture<Void> saveLevel(UUID islandId, long levelValue, long blockPoints) {
		Objects.requireNonNull(islandId, "islandId");
		ModuleDatabase db = core.databases().require(MODULE_DB_ID);
		String now = Instant.now().toString();
		return db.executeAsync(conn -> {
			try (PreparedStatement ps = conn.prepareStatement("""
				INSERT INTO island_levels (island_id, level_value, block_points, updated_at)
				VALUES (?, ?, ?, ?)
				ON CONFLICT(island_id) DO UPDATE SET
					level_value = excluded.level_value,
					block_points = excluded.block_points,
					updated_at = excluded.updated_at
				""")) {
				ps.setString(1, islandId.toString());
				ps.setLong(2, levelValue);
				ps.setLong(3, blockPoints);
				ps.setString(4, now);
				ps.executeUpdate();
			}
		});
	}

	public CompletableFuture<Optional<Long>> findLevel(UUID islandId) {
		Objects.requireNonNull(islandId, "islandId");
		ModuleDatabase db = core.databases().require(MODULE_DB_ID);
		return db.queryAsync(conn -> {
			try (PreparedStatement ps = conn.prepareStatement(
				"SELECT level_value FROM island_levels WHERE island_id = ?"
			)) {
				ps.setString(1, islandId.toString());
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.next()) {
						return Optional.of(rs.getLong(1));
					}
					return Optional.empty();
				}
			}
		});
	}

	public CompletableFuture<Void> deleteIslandLevel(UUID islandId) {
		Objects.requireNonNull(islandId, "islandId");
		return core.databases().find(MODULE_DB_ID)
			.map(db -> db.executeAsync(conn -> {
				try (PreparedStatement ps = conn.prepareStatement(
					"DELETE FROM island_levels WHERE island_id = ?"
				)) {
					ps.setString(1, islandId.toString());
					ps.executeUpdate();
				}
			}))
			.orElseGet(() -> CompletableFuture.completedFuture(null));
	}
}
