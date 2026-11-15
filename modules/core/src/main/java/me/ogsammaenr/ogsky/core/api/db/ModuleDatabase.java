package me.ogsammaenr.ogsky.core.api.db;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.concurrent.CompletableFuture;

/**
 * Isolated per-module SQLite database handle backed by a serialized writer executor.
 */
public interface ModuleDatabase {
	String moduleId();

	Path databasePath();

	<T> CompletableFuture<T> queryAsync(SqlFunction<Connection, T> work);

	CompletableFuture<Void> executeAsync(SqlConsumer<Connection> work);

	<T> T queryBlocking(SqlFunction<Connection, T> work);

	void executeBlocking(SqlConsumer<Connection> work);

	/**
	 * Waits until all previously submitted async database tasks have completed.
	 */
	CompletableFuture<Void> flush();
}
