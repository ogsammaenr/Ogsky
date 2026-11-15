package me.ogsammaenr.ogsky.core.internal.db;

import me.ogsammaenr.ogsky.core.api.db.ModuleDatabase;
import me.ogsammaenr.ogsky.core.api.db.SchemaMigration;
import me.ogsammaenr.ogsky.core.api.db.SqlConsumer;
import me.ogsammaenr.ogsky.core.api.db.SqlFunction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * SQLite-backed {@link ModuleDatabase} serializing all connection access onto one dedicated executor thread.
 */
public final class SqliteModuleDatabase implements ModuleDatabase, AutoCloseable {
	private final String moduleId;
	private final Path databasePath;
	private final ExecutorService executor;
	private final Connection connection;
	private volatile boolean closed;

	private SqliteModuleDatabase(String moduleId, Path databasePath, ExecutorService executor, Connection connection) {
		this.moduleId = moduleId;
		this.databasePath = databasePath;
		this.executor = executor;
		this.connection = connection;
	}

	static SqliteModuleDatabase open(String moduleId, Path databasePath, List<SchemaMigration> migrations) {
		Objects.requireNonNull(moduleId, "moduleId");
		Objects.requireNonNull(databasePath, "databasePath");
		Objects.requireNonNull(migrations, "migrations");

		try {
			Class.forName("org.sqlite.JDBC");
			Path parent = databasePath.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
		} catch (ClassNotFoundException e) {
			throw new IllegalStateException("SQLite JDBC driver not found on classpath.", e);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to create SQLite directory for module " + moduleId, e);
		}

		ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "ogsky-db-" + moduleId);
			thread.setDaemon(true);
			return thread;
		});

		try {
			Connection conn = CompletableFuture.supplyAsync(() -> openAndConfigure(databasePath), executor).join();
			SqliteModuleDatabase db = new SqliteModuleDatabase(moduleId, databasePath, executor, conn);
			db.applyMigrations(migrations);
			return db;
		} catch (RuntimeException e) {
			executor.shutdownNow();
			throw e;
		}
	}

	private static Connection openAndConfigure(Path databasePath) {
		try {
			Connection conn = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
			try (Statement stmt = conn.createStatement()) {
				stmt.execute("PRAGMA journal_mode = WAL;");
				stmt.execute("PRAGMA synchronous = NORMAL;");
				stmt.execute("PRAGMA busy_timeout = 5000;");
				stmt.execute("PRAGMA foreign_keys = ON;");
			}
			return conn;
		} catch (SQLException e) {
			throw new IllegalStateException("Failed to open SQLite database at " + databasePath, e);
		}
	}

	void applyMigrations(List<SchemaMigration> migrations) {
		List<SchemaMigration> sorted = migrations.stream()
			.sorted(Comparator.comparingInt(SchemaMigration::version))
			.toList();

		Set<Integer> seen = new HashSet<>();
		for (SchemaMigration migration : sorted) {
			if (!seen.add(migration.version())) {
				throw new IllegalArgumentException(
					"Duplicate migration version " + migration.version() + " for module " + moduleId
				);
			}
		}

		executeBlocking(conn -> {
			try (Statement stmt = conn.createStatement()) {
				stmt.execute("""
					CREATE TABLE IF NOT EXISTS ogsky_schema_migrations (
						version INTEGER PRIMARY KEY,
						description TEXT NOT NULL,
						applied_at TEXT NOT NULL
					)
					""");
			}

			Set<Integer> applied = new HashSet<>();
			try (Statement stmt = conn.createStatement();
				 ResultSet rs = stmt.executeQuery("SELECT version FROM ogsky_schema_migrations")) {
				while (rs.next()) {
					applied.add(rs.getInt(1));
				}
			}

			for (SchemaMigration migration : sorted) {
				if (applied.contains(migration.version())) {
					continue;
				}
				boolean previousAutoCommit = conn.getAutoCommit();
				conn.setAutoCommit(false);
				try {
					migration.step().accept(conn);
					try (PreparedStatement insert = conn.prepareStatement(
						"INSERT INTO ogsky_schema_migrations (version, description, applied_at) VALUES (?, ?, ?)"
					)) {
						insert.setInt(1, migration.version());
						insert.setString(2, migration.description());
						insert.setString(3, Instant.now().toString());
						insert.executeUpdate();
					}
					conn.commit();
				} catch (SQLException | RuntimeException e) {
					try {
						conn.rollback();
					} catch (SQLException suppressed) {
						e.addSuppressed(suppressed);
					}
					throw e;
				} finally {
					conn.setAutoCommit(previousAutoCommit);
				}
			}
		});
	}

	@Override
	public String moduleId() {
		return moduleId;
	}

	@Override
	public Path databasePath() {
		return databasePath;
	}

	@Override
	public <T> CompletableFuture<T> queryAsync(SqlFunction<Connection, T> work) {
		Objects.requireNonNull(work, "work");
		ensureOpen();
		return CompletableFuture.supplyAsync(() -> {
			try {
				return work.apply(connection);
			} catch (SQLException e) {
				throw new CompletionException("SQLite query failed for module " + moduleId, e);
			}
		}, executor);
	}

	@Override
	public CompletableFuture<Void> executeAsync(SqlConsumer<Connection> work) {
		Objects.requireNonNull(work, "work");
		ensureOpen();
		return CompletableFuture.runAsync(() -> {
			try {
				work.accept(connection);
			} catch (SQLException e) {
				throw new CompletionException("SQLite execution failed for module " + moduleId, e);
			}
		}, executor);
	}

	@Override
	public <T> T queryBlocking(SqlFunction<Connection, T> work) {
		try {
			return queryAsync(work).get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted during SQLite query for module " + moduleId, e);
		} catch (ExecutionException e) {
			throw unwrapExecutionException(e);
		}
	}

	@Override
	public void executeBlocking(SqlConsumer<Connection> work) {
		try {
			executeAsync(work).get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted during SQLite execution for module " + moduleId, e);
		} catch (ExecutionException e) {
			throw unwrapExecutionException(e);
		}
	}

	@Override
	public CompletableFuture<Void> flush() {
		if (closed) {
			return CompletableFuture.completedFuture(null);
		}
		return CompletableFuture.runAsync(() -> { }, executor);
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		try {
			flush().get(15, TimeUnit.SECONDS);
		} catch (Exception ignored) {
			// Continue closing connection on shutdown timeout/interrupt.
		}
		closed = true;
		try {
			CompletableFuture.runAsync(() -> {
				try {
					if (!connection.isClosed()) {
						try (Statement stmt = connection.createStatement()) {
							stmt.execute("PRAGMA wal_checkpoint(TRUNCATE);");
						} catch (SQLException ignored) {
							// Best-effort checkpoint before closing.
						}
						connection.close();
					}
				} catch (SQLException e) {
					throw new CompletionException(e);
				}
			}, executor).get(10, TimeUnit.SECONDS);
		} catch (Exception ignored) {
			// Fall through to executor shutdown.
		} finally {
			executor.shutdown();
			try {
				if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
					executor.shutdownNow();
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				executor.shutdownNow();
			}
		}
	}

	private void ensureOpen() {
		if (closed) {
			throw new IllegalStateException("ModuleDatabase already closed for module: " + moduleId);
		}
	}

	private static RuntimeException unwrapExecutionException(ExecutionException e) {
		Throwable cause = e.getCause();
		if (cause instanceof CompletionException ce && ce.getCause() != null) {
			cause = ce.getCause();
		}
		if (cause instanceof RuntimeException re) {
			return re;
		}
		return new IllegalStateException("SQLite operation failed", cause);
	}
}
