package me.ogsammaenr.ogsky.limits;

import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.db.DatabaseService;
import me.ogsammaenr.ogsky.core.api.db.ModuleDatabase;
import me.ogsammaenr.ogsky.core.api.db.SchemaMigration;
import org.jspecify.annotations.Nullable;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
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
 * Isolated async SQLite backing store at {@code <world>/ogsky/data/limits.db}.
 */
final class LimitRepository {
	static final String MODULE_ID = "limits";

	static final List<SchemaMigration> MIGRATIONS = List.of(
		SchemaMigration.sql(
			1,
			"Create ogsky_island_limits and ogsky_limits_meta tables",
			"""
			CREATE TABLE IF NOT EXISTS ogsky_island_limits (
				island_id TEXT NOT NULL,
				kind TEXT NOT NULL,
				rule_key TEXT NOT NULL,
				count INTEGER NOT NULL,
				updated_at INTEGER NOT NULL,
				PRIMARY KEY (island_id, kind, rule_key)
			)
			""",
			"""
			CREATE TABLE IF NOT EXISTS ogsky_limits_meta (
				island_id TEXT PRIMARY KEY,
				rules_hash TEXT NOT NULL,
				synced_at INTEGER NOT NULL
			)
			""",
			"CREATE INDEX IF NOT EXISTS idx_ogsky_limits_island ON ogsky_island_limits(island_id)"
		)
	);

	record PersistedCount(String kind, String ruleKey, int count) {
	}

	record PersistedIslandRecord(
		UUID islandId,
		String rulesHash,
		long syncedAt,
		List<PersistedCount> counts
	) {
	}

	private final Supplier<Optional<ModuleDatabase>> databaseSupplier;

	LimitRepository(DatabaseService databases) {
		this.databaseSupplier = () -> databases.find(MODULE_ID);
	}

	LimitRepository(ModuleDatabase database) {
		Optional<ModuleDatabase> fixed = Optional.of(database);
		this.databaseSupplier = () -> fixed;
	}

	private @Nullable ModuleDatabase currentDb() {
		return databaseSupplier.get().orElse(null);
	}

	List<PersistedIslandRecord> loadAllBlocking() {
		ModuleDatabase database = currentDb();
		if (database == null) {
			return List.of();
		}
		return database.queryBlocking(conn -> {
			Map<UUID, String> hashByIsland = new HashMap<>();
			Map<UUID, Long> syncedAtByIsland = new HashMap<>();
			try (Statement stmt = conn.createStatement();
				 ResultSet rs = stmt.executeQuery("SELECT island_id, rules_hash, synced_at FROM ogsky_limits_meta")) {
				while (rs.next()) {
					UUID islandId = parseUuidQuietly(rs.getString(1));
					if (islandId != null) {
						hashByIsland.put(islandId, rs.getString(2));
						syncedAtByIsland.put(islandId, rs.getLong(3));
					}
				}
			}

			Map<UUID, List<PersistedCount>> countsByIsland = new HashMap<>();
			try (Statement stmt = conn.createStatement();
				 ResultSet rs = stmt.executeQuery("SELECT island_id, kind, rule_key, count FROM ogsky_island_limits")) {
				while (rs.next()) {
					UUID islandId = parseUuidQuietly(rs.getString(1));
					if (islandId != null) {
						countsByIsland
							.computeIfAbsent(islandId, ignored -> new ArrayList<>())
							.add(new PersistedCount(rs.getString(2), rs.getString(3), Math.max(0, rs.getInt(4))));
					}
				}
			}

			Map<UUID, PersistedIslandRecord> merged = new HashMap<>();
			for (UUID id : hashByIsland.keySet()) {
				merged.put(id, new PersistedIslandRecord(
					id,
					hashByIsland.getOrDefault(id, ""),
					syncedAtByIsland.getOrDefault(id, 0L),
					countsByIsland.getOrDefault(id, List.of())
				));
			}
			for (var entry : countsByIsland.entrySet()) {
				merged.putIfAbsent(entry.getKey(), new PersistedIslandRecord(
					entry.getKey(),
					"",
					0L,
					entry.getValue()
				));
			}
			return List.copyOf(merged.values());
		});
	}

	CompletableFuture<Void> saveBatchAsync(List<PersistedIslandRecord> batch) {
		ModuleDatabase database = currentDb();
		if (database == null || batch.isEmpty()) {
			return CompletableFuture.completedFuture(null);
		}
		List<PersistedIslandRecord> copy = List.copyOf(batch);
		long now = System.currentTimeMillis();
		return database.executeAsync(conn -> {
			boolean prevAutoCommit = conn.getAutoCommit();
			conn.setAutoCommit(false);
			try (
				PreparedStatement deleteCounts = conn.prepareStatement("DELETE FROM ogsky_island_limits WHERE island_id = ?");
				PreparedStatement insertCount = conn.prepareStatement(
					"INSERT INTO ogsky_island_limits (island_id, kind, rule_key, count, updated_at) VALUES (?, ?, ?, ?, ?)"
				);
				PreparedStatement upsertMeta = conn.prepareStatement(
					"INSERT OR REPLACE INTO ogsky_limits_meta (island_id, rules_hash, synced_at) VALUES (?, ?, ?)"
				)
			) {
				for (PersistedIslandRecord record : copy) {
					String idStr = record.islandId().toString();
					deleteCounts.setString(1, idStr);
					deleteCounts.addBatch();

					for (PersistedCount count : record.counts()) {
						if (count.count() <= 0) {
							continue;
						}
						insertCount.setString(1, idStr);
						insertCount.setString(2, count.kind());
						insertCount.setString(3, count.ruleKey());
						insertCount.setInt(4, count.count());
						insertCount.setLong(5, now);
						insertCount.addBatch();
					}

					upsertMeta.setString(1, idStr);
					upsertMeta.setString(2, record.rulesHash());
					upsertMeta.setLong(3, record.syncedAt());
					upsertMeta.addBatch();
				}
				deleteCounts.executeBatch();
				insertCount.executeBatch();
				upsertMeta.executeBatch();
				conn.commit();
			} catch (Exception ex) {
				conn.rollback();
				throw ex;
			} finally {
				conn.setAutoCommit(prevAutoCommit);
			}
		}).exceptionally(ex -> {
			OgskyCore.LOGGER.error("Failed persisting limits batch to limits.db", ex);
			return null;
		});
	}

	CompletableFuture<Void> deleteIslandAsync(UUID islandId) {
		ModuleDatabase database = currentDb();
		if (database == null) {
			return CompletableFuture.completedFuture(null);
		}
		String idStr = islandId.toString();
		return database.executeAsync(conn -> {
			boolean prevAutoCommit = conn.getAutoCommit();
			conn.setAutoCommit(false);
			try (
				PreparedStatement delCounts = conn.prepareStatement("DELETE FROM ogsky_island_limits WHERE island_id = ?");
				PreparedStatement delMeta = conn.prepareStatement("DELETE FROM ogsky_limits_meta WHERE island_id = ?")
			) {
				delCounts.setString(1, idStr);
				delCounts.executeUpdate();
				delMeta.setString(1, idStr);
				delMeta.executeUpdate();
				conn.commit();
			} catch (Exception ex) {
				conn.rollback();
				throw ex;
			} finally {
				conn.setAutoCommit(prevAutoCommit);
			}
		}).exceptionally(ex -> {
			OgskyCore.LOGGER.error("Failed deleting limits data for island {}", islandId, ex);
			return null;
		});
	}

	void reconcileOrphansAsync(Set<UUID> liveIslandIds) {
		ModuleDatabase database = currentDb();
		if (database == null) {
			return;
		}
		Set<UUID> snapshotIds = Set.copyOf(liveIslandIds);
		database.executeAsync(conn -> {
			List<String> stale = new ArrayList<>();
			try (Statement stmt = conn.createStatement();
				 ResultSet rs = stmt.executeQuery(
					 "SELECT island_id FROM ogsky_limits_meta UNION SELECT DISTINCT island_id FROM ogsky_island_limits"
				 )) {
				while (rs.next()) {
					String raw = rs.getString(1);
					UUID parsed = parseUuidQuietly(raw);
					if (parsed == null || !snapshotIds.contains(parsed)) {
						stale.add(raw);
					}
				}
			}
			if (stale.isEmpty()) {
				return;
			}
			boolean prevAutoCommit = conn.getAutoCommit();
			conn.setAutoCommit(false);
			try (
				PreparedStatement delCounts = conn.prepareStatement("DELETE FROM ogsky_island_limits WHERE island_id = ?");
				PreparedStatement delMeta = conn.prepareStatement("DELETE FROM ogsky_limits_meta WHERE island_id = ?")
			) {
				for (String id : stale) {
					delCounts.setString(1, id);
					delCounts.addBatch();
					delMeta.setString(1, id);
					delMeta.addBatch();
				}
				delCounts.executeBatch();
				delMeta.executeBatch();
				conn.commit();
			} catch (Exception ex) {
				conn.rollback();
				throw ex;
			} finally {
				conn.setAutoCommit(prevAutoCommit);
			}
		}).exceptionally(ex -> {
			OgskyCore.LOGGER.error("Failed reconciling orphan rows in limits.db", ex);
			return null;
		});
	}

	CompletableFuture<Void> flush() {
		ModuleDatabase database = currentDb();
		return database != null ? database.flush() : CompletableFuture.completedFuture(null);
	}

	private static UUID parseUuidQuietly(String raw) {
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
