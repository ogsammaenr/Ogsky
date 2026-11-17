package me.ogsammaenr.ogsky.warps;

import me.ogsammaenr.ogsky.core.api.db.DatabaseService;
import me.ogsammaenr.ogsky.core.api.db.ModuleDatabase;
import me.ogsammaenr.ogsky.core.api.db.SchemaMigration;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Isolated async SQLite backing store at {@code <world>/ogsky/data/warps.db}.
 */
final class WarpRepository {
	static final String MODULE_ID = "warps";

	static final List<SchemaMigration> MIGRATIONS = List.of(
		SchemaMigration.sql(
			1,
			"Create ogsky_warps and ogsky_warp_island_meta tables",
			"""
			CREATE TABLE IF NOT EXISTS ogsky_warps (
				island_id TEXT NOT NULL,
				slug TEXT NOT NULL,
				display_name TEXT NOT NULL,
				dimension_id TEXT NOT NULL,
				rel_x REAL NOT NULL,
				y REAL NOT NULL,
				rel_z REAL NOT NULL,
				yaw REAL NOT NULL,
				pitch REAL NOT NULL,
				category_id TEXT NOT NULL,
				icon_item_id TEXT,
				description TEXT NOT NULL,
				enabled INTEGER NOT NULL,
				is_default INTEGER NOT NULL,
				visit_count INTEGER NOT NULL,
				created_by TEXT NOT NULL,
				created_at INTEGER NOT NULL,
				updated_at INTEGER NOT NULL,
				PRIMARY KEY (island_id, slug)
			)
			""",
			"""
			CREATE TABLE IF NOT EXISTS ogsky_warp_island_meta (
				island_id TEXT PRIMARY KEY,
				cached_max_warps INTEGER NOT NULL,
				updated_at INTEGER NOT NULL
			)
			""",
			"CREATE INDEX IF NOT EXISTS idx_ogsky_warps_island ON ogsky_warps(island_id)"
		)
	);

	record LoadedState(List<IslandWarp> warps, Map<UUID, Integer> cachedMaxWarps) {
	}

	private final Supplier<Optional<ModuleDatabase>> databaseSupplier;

	WarpRepository(DatabaseService databases) {
		this.databaseSupplier = () -> databases.find(MODULE_ID);
	}

	WarpRepository(ModuleDatabase database) {
		Optional<ModuleDatabase> fixed = Optional.of(database);
		this.databaseSupplier = () -> fixed;
	}

	private @Nullable ModuleDatabase currentDb() {
		return databaseSupplier.get().orElse(null);
	}

	LoadedState loadAllBlocking() {
		ModuleDatabase database = currentDb();
		if (database == null) {
			return new LoadedState(List.of(), Map.of());
		}
		return database.queryBlocking(conn -> {
			List<IslandWarp> warps = new ArrayList<>();
			try (Statement stmt = conn.createStatement();
				 ResultSet rs = stmt.executeQuery("""
				 	SELECT island_id, slug, display_name, dimension_id, rel_x, y, rel_z, yaw, pitch,
				 	       category_id, icon_item_id, description, enabled, is_default, visit_count,
				 	       created_by, created_at, updated_at
				 	FROM ogsky_warps
				 	ORDER BY island_id ASC, created_at ASC, slug ASC
				 	""")) {
				while (rs.next()) {
					UUID islandId = parseUuidQuietly(rs.getString(1));
					String slug = rs.getString(2);
					String displayName = rs.getString(3);
					Identifier dimId = Identifier.tryParse(rs.getString(4));
					UUID createdBy = parseUuidQuietly(rs.getString(16));
					if (islandId == null || slug == null || displayName == null || dimId == null || createdBy == null) {
						continue;
					}
					warps.add(new IslandWarp(
						islandId,
						slug,
						displayName,
						dimId,
						rs.getDouble(5),
						rs.getDouble(6),
						rs.getDouble(7),
						rs.getFloat(8),
						rs.getFloat(9),
						rs.getString(10),
						rs.getString(11),
						rs.getString(12),
						rs.getInt(13) != 0,
						rs.getInt(14) != 0,
						Math.max(0L, rs.getLong(15)),
						createdBy,
						rs.getLong(17),
						rs.getLong(18)
					));
				}
			}

			Map<UUID, Integer> meta = new HashMap<>();
			try (Statement stmt = conn.createStatement();
				 ResultSet rs = stmt.executeQuery("SELECT island_id, cached_max_warps FROM ogsky_warp_island_meta")) {
				while (rs.next()) {
					UUID islandId = parseUuidQuietly(rs.getString(1));
					int maxWarps = rs.getInt(2);
					if (islandId != null && maxWarps > 0) {
						meta.put(islandId, maxWarps);
					}
				}
			}
			return new LoadedState(List.copyOf(warps), Map.copyOf(meta));
		});
	}

	CompletableFuture<Void> upsertWarpAsync(IslandWarp warp) {
		ModuleDatabase database = currentDb();
		if (database == null) {
			return CompletableFuture.completedFuture(null);
		}
		return database.executeAsync(conn -> {
			try (PreparedStatement stmt = conn.prepareStatement("""
				INSERT OR REPLACE INTO ogsky_warps (
					island_id, slug, display_name, dimension_id, rel_x, y, rel_z, yaw, pitch,
					category_id, icon_item_id, description, enabled, is_default, visit_count,
					created_by, created_at, updated_at
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""")) {
				stmt.setString(1, warp.islandId().toString());
				stmt.setString(2, warp.slug());
				stmt.setString(3, warp.displayName());
				stmt.setString(4, warp.dimensionId().toString());
				stmt.setDouble(5, warp.relX());
				stmt.setDouble(6, warp.y());
				stmt.setDouble(7, warp.relZ());
				stmt.setFloat(8, warp.yaw());
				stmt.setFloat(9, warp.pitch());
				stmt.setString(10, warp.categoryId());
				if (warp.iconItemId() == null) {
					stmt.setNull(11, Types.VARCHAR);
				} else {
					stmt.setString(11, warp.iconItemId());
				}
				stmt.setString(12, warp.description());
				stmt.setInt(13, warp.enabled() ? 1 : 0);
				stmt.setInt(14, warp.isDefault() ? 1 : 0);
				stmt.setLong(15, warp.visitCount());
				stmt.setString(16, warp.createdBy().toString());
				stmt.setLong(17, warp.createdAt());
				stmt.setLong(18, warp.updatedAt());
				stmt.executeUpdate();
			}
		});
	}

	CompletableFuture<Void> incrementVisitCountAsync(UUID islandId, String slug, long visitCount) {
		ModuleDatabase database = currentDb();
		if (database == null) {
			return CompletableFuture.completedFuture(null);
		}
		return database.executeAsync(conn -> {
			try (PreparedStatement stmt = conn.prepareStatement(
				"UPDATE ogsky_warps SET visit_count = ? WHERE island_id = ? AND slug = ?"
			)) {
				stmt.setLong(1, visitCount);
				stmt.setString(2, islandId.toString());
				stmt.setString(3, slug);
				stmt.executeUpdate();
			}
		});
	}

	CompletableFuture<Void> deleteWarpAsync(UUID islandId, String slug) {
		ModuleDatabase database = currentDb();
		if (database == null) {
			return CompletableFuture.completedFuture(null);
		}
		return database.executeAsync(conn -> {
			try (PreparedStatement stmt = conn.prepareStatement(
				"DELETE FROM ogsky_warps WHERE island_id = ? AND slug = ?"
			)) {
				stmt.setString(1, islandId.toString());
				stmt.setString(2, slug);
				stmt.executeUpdate();
			}
		});
	}

	CompletableFuture<Void> deleteIslandAsync(UUID islandId) {
		ModuleDatabase database = currentDb();
		if (database == null) {
			return CompletableFuture.completedFuture(null);
		}
		return database.executeAsync(conn -> {
			try (PreparedStatement delWarps = conn.prepareStatement("DELETE FROM ogsky_warps WHERE island_id = ?");
				 PreparedStatement delMeta = conn.prepareStatement("DELETE FROM ogsky_warp_island_meta WHERE island_id = ?")) {
				String idStr = islandId.toString();
				delWarps.setString(1, idStr);
				delWarps.executeUpdate();
				delMeta.setString(1, idStr);
				delMeta.executeUpdate();
			}
		});
	}

	CompletableFuture<Void> deleteIslandsAsync(Set<UUID> islandIds) {
		ModuleDatabase database = currentDb();
		if (database == null || islandIds.isEmpty()) {
			return CompletableFuture.completedFuture(null);
		}
		List<UUID> copy = List.copyOf(islandIds);
		return database.executeAsync(conn -> {
			boolean prevAuto = conn.getAutoCommit();
			conn.setAutoCommit(false);
			try (PreparedStatement delWarps = conn.prepareStatement("DELETE FROM ogsky_warps WHERE island_id = ?");
				 PreparedStatement delMeta = conn.prepareStatement("DELETE FROM ogsky_warp_island_meta WHERE island_id = ?")) {
				for (UUID id : copy) {
					String idStr = id.toString();
					delWarps.setString(1, idStr);
					delWarps.addBatch();
					delMeta.setString(1, idStr);
					delMeta.addBatch();
				}
				delWarps.executeBatch();
				delMeta.executeBatch();
				conn.commit();
			} catch (Exception ex) {
				conn.rollback();
				throw ex;
			} finally {
				conn.setAutoCommit(prevAuto);
			}
		});
	}

	CompletableFuture<Void> upsertCachedMaxWarpsAsync(UUID islandId, int maxWarps) {
		ModuleDatabase database = currentDb();
		if (database == null) {
			return CompletableFuture.completedFuture(null);
		}
		long now = System.currentTimeMillis();
		return database.executeAsync(conn -> {
			try (PreparedStatement stmt = conn.prepareStatement(
				"INSERT OR REPLACE INTO ogsky_warp_island_meta (island_id, cached_max_warps, updated_at) VALUES (?, ?, ?)"
			)) {
				stmt.setString(1, islandId.toString());
				stmt.setInt(2, maxWarps);
				stmt.setLong(3, now);
				stmt.executeUpdate();
			}
		});
	}

	private static @Nullable UUID parseUuidQuietly(@Nullable String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(raw);
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}
}
