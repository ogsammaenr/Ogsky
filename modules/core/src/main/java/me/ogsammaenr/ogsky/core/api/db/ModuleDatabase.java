package me.ogsammaenr.ogsky.core.api.db;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.concurrent.CompletableFuture;

/**
 * Isolated per-module SQLite database handle backed by a serialized writer executor.
 */
public interface ModuleDatabase {
	/**
	 * Unique database module identifier matching the {@code <moduleId>.db} filename.
	 */
	String moduleId();

	/**
	 * Path to this module's SQLite file inside the active world's {@code ogsky/data/} directory.
	 */
	Path databasePath();

	/**
	 * Executes a read query asynchronously on the dedicated {@code ogsky-db-<moduleId>} thread.
	 * Marshal world mutations or event dispatches back onto {@code server.execute(...)}.
	 */
	<T> CompletableFuture<T> queryAsync(SqlFunction<Connection, T> work);

	/**
	 * Executes a write statement or transaction asynchronously on the dedicated {@code ogsky-db-<moduleId>} thread.
	 */
	CompletableFuture<Void> executeAsync(SqlConsumer<Connection> work);

	/**
	 * Synchronously executes a query on the DB thread. Intended for startup load or unit tests only;
	 * never call on the server tick thread during live gameplay.
	 */
	<T> T queryBlocking(SqlFunction<Connection, T> work);

	/**
	 * Synchronously executes a write on the DB thread. Intended for startup/shutdown or unit tests only;
	 * never call on the server tick thread during live gameplay.
	 */
	void executeBlocking(SqlConsumer<Connection> work);

	/**
	 * Waits until all previously submitted async database tasks have completed.
	 */
	CompletableFuture<Void> flush();
}
