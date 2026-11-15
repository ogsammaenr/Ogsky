package me.ogsammaenr.ogsky.core.internal.island;

import me.ogsammaenr.ogsky.core.api.db.ModuleDatabase;
import me.ogsammaenr.ogsky.core.api.db.SchemaMigration;
import me.ogsammaenr.ogsky.core.api.flag.FlagRegistry;
import me.ogsammaenr.ogsky.core.api.flag.FlagType;
import me.ogsammaenr.ogsky.core.api.flag.IslandFlagDefinition;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.api.island.RelativeSpawn;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfig;
import net.minecraft.resources.Identifier;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * SQLite persistence and schema migrations for {@code core.db}.
 */
public final class CoreIslandRepository {
	public static final String MODULE_ID = "core";
	private static final String META_GRID_CELL_SIZE = "grid_cell_size";

	public static final List<SchemaMigration> MIGRATIONS = List.of(
		SchemaMigration.sql(
			1,
			"Initialize core islands metadata table",
			"""
			CREATE TABLE IF NOT EXISTS islands (
				island_id TEXT PRIMARY KEY,
				owner_uuid TEXT NOT NULL,
				center_x INTEGER NOT NULL,
				center_z INTEGER NOT NULL,
				created_at TEXT NOT NULL
			)
			""",
			"CREATE INDEX IF NOT EXISTS idx_islands_owner ON islands(owner_uuid)"
		),
		SchemaMigration.sql(
			2,
			"Add world_meta, extended island columns, members, bans, flags, world_settings, and cleanup queue",
			"""
			CREATE TABLE IF NOT EXISTS world_meta (
				meta_key TEXT PRIMARY KEY,
				meta_value TEXT NOT NULL
			)
			""",
			"ALTER TABLE islands ADD COLUMN grid_x INTEGER NOT NULL DEFAULT 0",
			"ALTER TABLE islands ADD COLUMN grid_z INTEGER NOT NULL DEFAULT 0",
			"ALTER TABLE islands ADD COLUMN half_size INTEGER NOT NULL DEFAULT 50",
			"ALTER TABLE islands ADD COLUMN bundle_id TEXT NOT NULL DEFAULT 'default'",
			"ALTER TABLE islands ADD COLUMN lifecycle_mask INTEGER NOT NULL DEFAULT 0",
			"ALTER TABLE islands ADD COLUMN rel_ow_x INTEGER NOT NULL DEFAULT 0",
			"ALTER TABLE islands ADD COLUMN rel_ow_y INTEGER NOT NULL DEFAULT 64",
			"ALTER TABLE islands ADD COLUMN rel_ow_z INTEGER NOT NULL DEFAULT 0",
			"ALTER TABLE islands ADD COLUMN rel_ow_yaw INTEGER NOT NULL DEFAULT 0",
			"ALTER TABLE islands ADD COLUMN rel_ow_pitch INTEGER NOT NULL DEFAULT 0",
			"ALTER TABLE islands ADD COLUMN rel_nether_x INTEGER NOT NULL DEFAULT 0",
			"ALTER TABLE islands ADD COLUMN rel_nether_y INTEGER NOT NULL DEFAULT 64",
			"ALTER TABLE islands ADD COLUMN rel_nether_z INTEGER NOT NULL DEFAULT 0",
			"ALTER TABLE islands ADD COLUMN rel_nether_yaw INTEGER NOT NULL DEFAULT 0",
			"ALTER TABLE islands ADD COLUMN rel_nether_pitch INTEGER NOT NULL DEFAULT 0",
			"ALTER TABLE islands ADD COLUMN rel_end_x INTEGER NOT NULL DEFAULT 0",
			"ALTER TABLE islands ADD COLUMN rel_end_y INTEGER NOT NULL DEFAULT 64",
			"ALTER TABLE islands ADD COLUMN rel_end_z INTEGER NOT NULL DEFAULT 0",
			"ALTER TABLE islands ADD COLUMN rel_end_yaw INTEGER NOT NULL DEFAULT 0",
			"ALTER TABLE islands ADD COLUMN rel_end_pitch INTEGER NOT NULL DEFAULT 0",
			"ALTER TABLE islands ADD COLUMN updated_at TEXT NOT NULL DEFAULT ''",
			"CREATE INDEX IF NOT EXISTS idx_islands_grid ON islands(grid_x, grid_z)",
			"""
			CREATE TABLE IF NOT EXISTS island_members (
				island_id TEXT NOT NULL,
				player_uuid TEXT NOT NULL,
				role INTEGER NOT NULL,
				joined_at TEXT NOT NULL,
				PRIMARY KEY (island_id, player_uuid),
				FOREIGN KEY (island_id) REFERENCES islands(island_id) ON DELETE CASCADE
			)
			""",
			"CREATE INDEX IF NOT EXISTS idx_island_members_player ON island_members(player_uuid)",
			"""
			CREATE TABLE IF NOT EXISTS island_bans (
				island_id TEXT NOT NULL,
				player_uuid TEXT NOT NULL,
				banned_by TEXT,
				banned_at TEXT NOT NULL,
				PRIMARY KEY (island_id, player_uuid),
				FOREIGN KEY (island_id) REFERENCES islands(island_id) ON DELETE CASCADE
			)
			""",
			"""
			CREATE TABLE IF NOT EXISTS island_protection_flags (
				island_id TEXT NOT NULL,
				flag_id TEXT NOT NULL,
				min_rank INTEGER NOT NULL,
				PRIMARY KEY (island_id, flag_id),
				FOREIGN KEY (island_id) REFERENCES islands(island_id) ON DELETE CASCADE
			)
			""",
			"""
			CREATE TABLE IF NOT EXISTS island_setting_flags (
				island_id TEXT NOT NULL,
				flag_id TEXT NOT NULL,
				enabled INTEGER NOT NULL,
				PRIMARY KEY (island_id, flag_id),
				FOREIGN KEY (island_id) REFERENCES islands(island_id) ON DELETE CASCADE
			)
			""",
			"""
			CREATE TABLE IF NOT EXISTS world_settings (
				flag_id TEXT PRIMARY KEY,
				enabled INTEGER NOT NULL,
				updated_at TEXT NOT NULL
			)
			""",
			"""
			CREATE TABLE IF NOT EXISTS grid_cleanup_queue (
				grid_x INTEGER NOT NULL,
				grid_z INTEGER NOT NULL,
				min_x INTEGER NOT NULL,
				max_x INTEGER NOT NULL,
				min_z INTEGER NOT NULL,
				max_z INTEGER NOT NULL,
				cursor_chunk_x INTEGER NOT NULL,
				cursor_chunk_z INTEGER NOT NULL,
				created_at TEXT NOT NULL,
				PRIMARY KEY (grid_x, grid_z)
			)
			"""
		)
	);

	public record LoadedWorldState(
		int cellSize,
		boolean cellSizeLocked,
		List<Island> islands,
		Map<Identifier, Boolean> worldSettings,
		List<GridCleanupEntry> pendingCleanups
	) {
	}

	private CoreIslandRepository() {
	}

	public static LoadedWorldState loadWorldState(
		ModuleDatabase db,
		CoreConfig config,
		FlagRegistry flagRegistry
	) {
		Objects.requireNonNull(db, "db");
		Objects.requireNonNull(config, "config");
		Objects.requireNonNull(flagRegistry, "flagRegistry");

		return db.queryBlocking(conn -> {
			String lockedCellRaw = readMeta(conn, META_GRID_CELL_SIZE);
			boolean cellSizeLocked = lockedCellRaw != null;
			int activeCellSize = config.grid.cellSize;
			if (cellSizeLocked) {
				int locked = Integer.parseInt(lockedCellRaw);
				if (locked != config.grid.cellSize) {
					throw new IllegalStateException(
						"Configured grid.cellSize (" + config.grid.cellSize + ") does not match locked core.db value ("
							+ locked + "). Restore grid.cellSize to " + locked + " so existing islands do not shift."
					);
				}
				activeCellSize = locked;
			}

			Map<UUID, Island> islandsById = new HashMap<>();
			List<Island> islands = new ArrayList<>();
			try (Statement stmt = conn.createStatement();
				 ResultSet rs = stmt.executeQuery("""
					SELECT island_id, owner_uuid, grid_x, grid_z, center_x, center_z, half_size, bundle_id,
					       lifecycle_mask,
					       rel_ow_x, rel_ow_y, rel_ow_z, rel_ow_yaw, rel_ow_pitch,
					       rel_nether_x, rel_nether_y, rel_nether_z, rel_nether_yaw, rel_nether_pitch,
					       rel_end_x, rel_end_y, rel_end_z, rel_end_yaw, rel_end_pitch,
					       created_at
					FROM islands
					""")) {
				while (rs.next()) {
					UUID islandId = UUID.fromString(rs.getString("island_id"));
					UUID ownerId = UUID.fromString(rs.getString("owner_uuid"));
					int gridX = rs.getInt("grid_x");
					int gridZ = rs.getInt("grid_z");
					int centerX = rs.getInt("center_x");
					int centerZ = rs.getInt("center_z");
					short halfSize = rs.getShort("half_size");
					String bundleId = rs.getString("bundle_id");
					byte lifecycleMask = (byte) rs.getInt("lifecycle_mask");
					Instant createdAt = Instant.parse(rs.getString("created_at"));

					Island island = new Island(
						islandId,
						ownerId,
						gridX,
						gridZ,
						centerX,
						centerZ,
						halfSize,
						bundleId,
						createdAt,
						flagRegistry,
						config
					);
					island.setLifecycleMask(lifecycleMask);
					island.setOverworldSpawn(readSpawn(rs, "rel_ow"));
					island.setNetherSpawn(readSpawn(rs, "rel_nether"));
					island.setEndSpawn(readSpawn(rs, "rel_end"));
					islandsById.put(islandId, island);
					islands.add(island);
				}
			}

			loadMembers(conn, islandsById);
			loadBans(conn, islandsById);
			loadProtectionFlags(conn, islandsById, flagRegistry);
			loadSettingFlags(conn, islandsById, flagRegistry);

			Map<Identifier, Boolean> worldSettings = loadWorldSettings(conn, config, flagRegistry);
			List<GridCleanupEntry> cleanups = loadCleanupQueue(conn);

			return new LoadedWorldState(activeCellSize, cellSizeLocked, islands, worldSettings, cleanups);
		});
	}

	public static CompletableFuture<Void> lockCellSizeAsync(ModuleDatabase db, int cellSize) {
		return db.executeAsync(conn -> {
			try (PreparedStatement ps = conn.prepareStatement(
				"INSERT OR IGNORE INTO world_meta (meta_key, meta_value) VALUES (?, ?)"
			)) {
				ps.setString(1, META_GRID_CELL_SIZE);
				ps.setString(2, Integer.toString(cellSize));
				ps.executeUpdate();
			}
		});
	}

	public static CompletableFuture<Void> upsertIslandAsync(ModuleDatabase db, Island island) {
		UUID islandId = island.islandId();
		UUID ownerId = island.ownerId();
		int gridX = island.gridX();
		int gridZ = island.gridZ();
		int centerX = island.centerX();
		int centerZ = island.centerZ();
		short halfSize = island.halfSize();
		String bundleId = island.bundleId();
		byte lifecycleMask = island.lifecycleMask();
		RelativeSpawn ow = island.overworldSpawn();
		RelativeSpawn nether = island.netherSpawn();
		RelativeSpawn end = island.endSpawn();
		String createdAt = island.createdAt().toString();
		String updatedAt = Instant.now().toString();

		return db.executeAsync(conn -> {
			try (PreparedStatement ps = conn.prepareStatement("""
				INSERT INTO islands (
					island_id, owner_uuid, grid_x, grid_z, center_x, center_z, half_size, bundle_id,
					lifecycle_mask,
					rel_ow_x, rel_ow_y, rel_ow_z, rel_ow_yaw, rel_ow_pitch,
					rel_nether_x, rel_nether_y, rel_nether_z, rel_nether_yaw, rel_nether_pitch,
					rel_end_x, rel_end_y, rel_end_z, rel_end_yaw, rel_end_pitch,
					created_at, updated_at
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT(island_id) DO UPDATE SET
					owner_uuid = excluded.owner_uuid,
					grid_x = excluded.grid_x,
					grid_z = excluded.grid_z,
					center_x = excluded.center_x,
					center_z = excluded.center_z,
					half_size = excluded.half_size,
					bundle_id = excluded.bundle_id,
					lifecycle_mask = excluded.lifecycle_mask,
					rel_ow_x = excluded.rel_ow_x,
					rel_ow_y = excluded.rel_ow_y,
					rel_ow_z = excluded.rel_ow_z,
					rel_ow_yaw = excluded.rel_ow_yaw,
					rel_ow_pitch = excluded.rel_ow_pitch,
					rel_nether_x = excluded.rel_nether_x,
					rel_nether_y = excluded.rel_nether_y,
					rel_nether_z = excluded.rel_nether_z,
					rel_nether_yaw = excluded.rel_nether_yaw,
					rel_nether_pitch = excluded.rel_nether_pitch,
					rel_end_x = excluded.rel_end_x,
					rel_end_y = excluded.rel_end_y,
					rel_end_z = excluded.rel_end_z,
					rel_end_yaw = excluded.rel_end_yaw,
					rel_end_pitch = excluded.rel_end_pitch,
					updated_at = excluded.updated_at
				""")) {
				ps.setString(1, islandId.toString());
				ps.setString(2, ownerId.toString());
				ps.setInt(3, gridX);
				ps.setInt(4, gridZ);
				ps.setInt(5, centerX);
				ps.setInt(6, centerZ);
				ps.setInt(7, halfSize);
				ps.setString(8, bundleId);
				ps.setInt(9, lifecycleMask & 0xFF);
				bindSpawn(ps, 10, ow);
				bindSpawn(ps, 15, nether);
				bindSpawn(ps, 20, end);
				ps.setString(25, createdAt);
				ps.setString(26, updatedAt);
				ps.executeUpdate();
			}
		});
	}

	public static CompletableFuture<Void> deleteIslandAsync(ModuleDatabase db, UUID islandId) {
		return db.executeAsync(conn -> {
			try (PreparedStatement ps = conn.prepareStatement("DELETE FROM islands WHERE island_id = ?")) {
				ps.setString(1, islandId.toString());
				ps.executeUpdate();
			}
		});
	}

	public static CompletableFuture<Void> upsertMemberAsync(ModuleDatabase db, UUID islandId, UUID playerUuid, byte role) {
		String now = Instant.now().toString();
		return db.executeAsync(conn -> {
			try (PreparedStatement ps = conn.prepareStatement("""
				INSERT INTO island_members (island_id, player_uuid, role, joined_at)
				VALUES (?, ?, ?, ?)
				ON CONFLICT(island_id, player_uuid) DO UPDATE SET role = excluded.role
				""")) {
				ps.setString(1, islandId.toString());
				ps.setString(2, playerUuid.toString());
				ps.setInt(3, role);
				ps.setString(4, now);
				ps.executeUpdate();
			}
		});
	}

	public static CompletableFuture<Void> deleteMemberAsync(ModuleDatabase db, UUID islandId, UUID playerUuid) {
		return db.executeAsync(conn -> {
			try (PreparedStatement ps = conn.prepareStatement(
				"DELETE FROM island_members WHERE island_id = ? AND player_uuid = ?"
			)) {
				ps.setString(1, islandId.toString());
				ps.setString(2, playerUuid.toString());
				ps.executeUpdate();
			}
		});
	}

	public static CompletableFuture<Void> upsertBanAsync(ModuleDatabase db, UUID islandId, UUID playerUuid, UUID bannedBy) {
		String now = Instant.now().toString();
		return db.executeAsync(conn -> {
			try (PreparedStatement ps = conn.prepareStatement("""
				INSERT INTO island_bans (island_id, player_uuid, banned_by, banned_at)
				VALUES (?, ?, ?, ?)
				ON CONFLICT(island_id, player_uuid) DO UPDATE SET
					banned_by = excluded.banned_by,
					banned_at = excluded.banned_at
				""")) {
				ps.setString(1, islandId.toString());
				ps.setString(2, playerUuid.toString());
				ps.setString(3, bannedBy == null ? null : bannedBy.toString());
				ps.setString(4, now);
				ps.executeUpdate();
			}
		});
	}

	public static CompletableFuture<Void> deleteBanAsync(ModuleDatabase db, UUID islandId, UUID playerUuid) {
		return db.executeAsync(conn -> {
			try (PreparedStatement ps = conn.prepareStatement(
				"DELETE FROM island_bans WHERE island_id = ? AND player_uuid = ?"
			)) {
				ps.setString(1, islandId.toString());
				ps.setString(2, playerUuid.toString());
				ps.executeUpdate();
			}
		});
	}

	public static CompletableFuture<Void> saveProtectionFlagAsync(
		ModuleDatabase db,
		UUID islandId,
		Identifier flagId,
		byte minRank
	) {
		return db.executeAsync(conn -> {
			try (PreparedStatement ps = conn.prepareStatement("""
				INSERT INTO island_protection_flags (island_id, flag_id, min_rank)
				VALUES (?, ?, ?)
				ON CONFLICT(island_id, flag_id) DO UPDATE SET min_rank = excluded.min_rank
				""")) {
				ps.setString(1, islandId.toString());
				ps.setString(2, flagId.toString());
				ps.setInt(3, minRank);
				ps.executeUpdate();
			}
		});
	}

	public static CompletableFuture<Void> saveSettingFlagAsync(
		ModuleDatabase db,
		UUID islandId,
		Identifier flagId,
		boolean enabled
	) {
		return db.executeAsync(conn -> {
			try (PreparedStatement ps = conn.prepareStatement("""
				INSERT INTO island_setting_flags (island_id, flag_id, enabled)
				VALUES (?, ?, ?)
				ON CONFLICT(island_id, flag_id) DO UPDATE SET enabled = excluded.enabled
				""")) {
				ps.setString(1, islandId.toString());
				ps.setString(2, flagId.toString());
				ps.setInt(3, enabled ? 1 : 0);
				ps.executeUpdate();
			}
		});
	}

	public static CompletableFuture<Void> saveWorldSettingAsync(
		ModuleDatabase db,
		Identifier flagId,
		boolean enabled
	) {
		String now = Instant.now().toString();
		return db.executeAsync(conn -> {
			try (PreparedStatement ps = conn.prepareStatement("""
				INSERT INTO world_settings (flag_id, enabled, updated_at)
				VALUES (?, ?, ?)
				ON CONFLICT(flag_id) DO UPDATE SET
					enabled = excluded.enabled,
					updated_at = excluded.updated_at
				""")) {
				ps.setString(1, flagId.toString());
				ps.setInt(2, enabled ? 1 : 0);
				ps.setString(3, now);
				ps.executeUpdate();
			}
		});
	}

	public static CompletableFuture<Void> upsertCleanupEntryAsync(ModuleDatabase db, GridCleanupEntry entry) {
		return db.executeAsync(conn -> {
			try (PreparedStatement ps = conn.prepareStatement("""
				INSERT INTO grid_cleanup_queue (
					grid_x, grid_z, min_x, max_x, min_z, max_z, cursor_chunk_x, cursor_chunk_z, created_at
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT(grid_x, grid_z) DO UPDATE SET
					cursor_chunk_x = excluded.cursor_chunk_x,
					cursor_chunk_z = excluded.cursor_chunk_z
				""")) {
				ps.setInt(1, entry.gridX());
				ps.setInt(2, entry.gridZ());
				ps.setInt(3, entry.minX());
				ps.setInt(4, entry.maxX());
				ps.setInt(5, entry.minZ());
				ps.setInt(6, entry.maxZ());
				ps.setInt(7, entry.cursorChunkX());
				ps.setInt(8, entry.cursorChunkZ());
				ps.setString(9, entry.createdAt().toString());
				ps.executeUpdate();
			}
		});
	}

	public static CompletableFuture<Void> deleteCleanupEntryAsync(ModuleDatabase db, int gridX, int gridZ) {
		return db.executeAsync(conn -> {
			try (PreparedStatement ps = conn.prepareStatement(
				"DELETE FROM grid_cleanup_queue WHERE grid_x = ? AND grid_z = ?"
			)) {
				ps.setInt(1, gridX);
				ps.setInt(2, gridZ);
				ps.executeUpdate();
			}
		});
	}

	private static String readMeta(Connection conn, String key) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement("SELECT meta_value FROM world_meta WHERE meta_key = ?")) {
			ps.setString(1, key);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getString(1) : null;
			}
		}
	}

	private static RelativeSpawn readSpawn(ResultSet rs, String prefix) throws SQLException {
		return new RelativeSpawn(
			rs.getShort(prefix + "_x"),
			rs.getShort(prefix + "_y"),
			rs.getShort(prefix + "_z"),
			(byte) rs.getInt(prefix + "_yaw"),
			(byte) rs.getInt(prefix + "_pitch")
		);
	}

	private static void bindSpawn(PreparedStatement ps, int startIndex, RelativeSpawn spawn) throws SQLException {
		ps.setInt(startIndex, spawn.relX());
		ps.setInt(startIndex + 1, spawn.relY());
		ps.setInt(startIndex + 2, spawn.relZ());
		ps.setInt(startIndex + 3, spawn.yawQuantized());
		ps.setInt(startIndex + 4, spawn.pitchQuantized());
	}

	private static void loadMembers(Connection conn, Map<UUID, Island> islandsById) throws SQLException {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT island_id, player_uuid, role FROM island_members")) {
			while (rs.next()) {
				UUID islandId = UUID.fromString(rs.getString(1));
				Island island = islandsById.get(islandId);
				if (island == null) {
					continue;
				}
				UUID playerId = UUID.fromString(rs.getString(2));
				byte role = (byte) rs.getInt(3);
				if (role == IslandRole.COOP) {
					// COOP memberships are strictly session-only and never survive restarts.
					continue;
				}
				if (IslandRole.isValidMemberRole(role)) {
					island.setMemberRole(playerId, role);
				}
			}
		}
	}

	private static void loadBans(Connection conn, Map<UUID, Island> islandsById) throws SQLException {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT island_id, player_uuid FROM island_bans")) {
			while (rs.next()) {
				UUID islandId = UUID.fromString(rs.getString(1));
				Island island = islandsById.get(islandId);
				if (island != null) {
					island.addBan(UUID.fromString(rs.getString(2)));
				}
			}
		}
	}

	private static void loadProtectionFlags(
		Connection conn,
		Map<UUID, Island> islandsById,
		FlagRegistry flagRegistry
	) throws SQLException {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT island_id, flag_id, min_rank FROM island_protection_flags")) {
			while (rs.next()) {
				Island island = islandsById.get(UUID.fromString(rs.getString(1)));
				if (island == null) {
					continue;
				}
				Identifier flagId = Identifier.tryParse(rs.getString(2));
				if (flagId == null) {
					continue;
				}
				var def = flagRegistry.find(flagId);
				if (def.isPresent() && def.get().type() == FlagType.PROTECTION) {
					byte rank = (byte) rs.getInt(3);
					if (IslandRole.isValidThreshold(rank)) {
						island.setProtectionRankBySlot(flagRegistry.slotOf(flagId), rank);
					}
				}
			}
		}
	}

	private static void loadSettingFlags(
		Connection conn,
		Map<UUID, Island> islandsById,
		FlagRegistry flagRegistry
	) throws SQLException {
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT island_id, flag_id, enabled FROM island_setting_flags")) {
			while (rs.next()) {
				Island island = islandsById.get(UUID.fromString(rs.getString(1)));
				if (island == null) {
					continue;
				}
				Identifier flagId = Identifier.tryParse(rs.getString(2));
				if (flagId == null) {
					continue;
				}
				var def = flagRegistry.find(flagId);
				if (def.isPresent() && def.get().type() == FlagType.SETTING) {
					island.setSettingEnabledBySlot(flagRegistry.slotOf(flagId), rs.getInt(3) != 0);
				}
			}
		}
	}

	private static Map<Identifier, Boolean> loadWorldSettings(
		Connection conn,
		CoreConfig config,
		FlagRegistry flagRegistry
	) throws SQLException {
		Map<Identifier, Boolean> result = new HashMap<>();
		for (IslandFlagDefinition def : flagRegistry.listByType(FlagType.WORLD_SETTING)) {
			boolean defaultVal = config.flags.resolveWorldSettingDefault(def.id().toString(), def.defaultBoolValue());
			result.put(def.id(), defaultVal);
		}
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("SELECT flag_id, enabled FROM world_settings")) {
			while (rs.next()) {
				Identifier flagId = Identifier.tryParse(rs.getString(1));
				if (flagId == null) {
					continue;
				}
				var def = flagRegistry.find(flagId);
				if (def.isPresent() && def.get().type() == FlagType.WORLD_SETTING) {
					result.put(flagId, rs.getInt(2) != 0);
				}
			}
		}
		return result;
	}

	private static List<GridCleanupEntry> loadCleanupQueue(Connection conn) throws SQLException {
		List<GridCleanupEntry> entries = new ArrayList<>();
		try (Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery("""
				SELECT grid_x, grid_z, min_x, max_x, min_z, max_z, cursor_chunk_x, cursor_chunk_z, created_at
				FROM grid_cleanup_queue
				""")) {
			while (rs.next()) {
				entries.add(new GridCleanupEntry(
					rs.getInt("grid_x"),
					rs.getInt("grid_z"),
					rs.getInt("min_x"),
					rs.getInt("max_x"),
					rs.getInt("min_z"),
					rs.getInt("max_z"),
					rs.getInt("cursor_chunk_x"),
					rs.getInt("cursor_chunk_z"),
					Instant.parse(rs.getString("created_at"))
				));
			}
		}
		return entries;
	}
}
