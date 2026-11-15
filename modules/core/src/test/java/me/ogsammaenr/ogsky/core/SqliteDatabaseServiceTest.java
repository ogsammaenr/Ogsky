package me.ogsammaenr.ogsky.core;

import me.ogsammaenr.ogsky.core.api.db.ModuleDatabase;
import me.ogsammaenr.ogsky.core.api.db.SchemaMigration;
import me.ogsammaenr.ogsky.core.internal.db.SqliteDatabaseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteDatabaseServiceTest {
	@TempDir
	Path tempDir;

	@Test
	void opensSeparateModuleDatabasesWithWalAndPersistsAcrossRestart() {
		List<SchemaMigration> coreMigrations = List.of(
			SchemaMigration.sql(1, "Create kv", "CREATE TABLE kv (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
		);
		List<SchemaMigration> levelMigrations = List.of(
			SchemaMigration.sql(1, "Create scores", "CREATE TABLE scores (id TEXT PRIMARY KEY, pts INTEGER NOT NULL)")
		);

		SqliteDatabaseService firstRun = new SqliteDatabaseService();
		firstRun.registerModule("core", coreMigrations);
		firstRun.registerModule("level", levelMigrations);
		firstRun.openAllForRoot(tempDir);

		ModuleDatabase coreDb = firstRun.require("core");
		ModuleDatabase levelDb = firstRun.require("level");

		String journalMode = coreDb.queryBlocking(conn -> {
			try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("PRAGMA journal_mode;")) {
				return rs.next() ? rs.getString(1) : "";
			}
		});
		assertEquals("wal", journalMode.toLowerCase());

		coreDb.executeAsync(conn -> {
			try (PreparedStatement ps = conn.prepareStatement("INSERT INTO kv (k, v) VALUES (?, ?)")) {
				ps.setString(1, "version");
				ps.setString(2, "1");
				ps.executeUpdate();
			}
		}).join();

		levelDb.executeAsync(conn -> {
			try (PreparedStatement ps = conn.prepareStatement("INSERT INTO scores (id, pts) VALUES (?, ?)")) {
				ps.setString(1, "island-1");
				ps.setInt(2, 420);
				ps.executeUpdate();
			}
		}).join();

		firstRun.closeAll();

		assertTrue(Files.exists(tempDir.resolve("core.db")));
		assertTrue(Files.exists(tempDir.resolve("level.db")));

		// Re-open simulating server restart without optional 'level' module enabled
		SqliteDatabaseService secondRun = new SqliteDatabaseService();
		secondRun.registerModule("core", coreMigrations);
		secondRun.openAllForRoot(tempDir);

		String stored = secondRun.require("core").queryBlocking(conn -> {
			try (PreparedStatement ps = conn.prepareStatement("SELECT v FROM kv WHERE k = ?")) {
				ps.setString(1, "version");
				try (ResultSet rs = ps.executeQuery()) {
					return rs.next() ? rs.getString(1) : null;
				}
			}
		});
		assertEquals("1", stored);
		assertTrue(secondRun.find("level").isEmpty());
		assertTrue(Files.exists(tempDir.resolve("level.db")));

		secondRun.closeAll();
	}
}
