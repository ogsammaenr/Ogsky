package me.ogsammaenr.ogsky.core.api.db;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Objects;

/**
 * Versioned schema migration step applied once per module SQLite database.
 */
public record SchemaMigration(int version, String description, SqlConsumer<Connection> step) {
	public SchemaMigration {
		if (version <= 0) {
			throw new IllegalArgumentException("Migration version must be positive: " + version);
		}
		if (description == null || description.isBlank()) {
			throw new IllegalArgumentException("Migration description must not be blank.");
		}
		Objects.requireNonNull(step, "step");
	}

	/**
	 * Convenience factory that executes one or more SQL DDL/DML statements sequentially.
	 */
	public static SchemaMigration sql(int version, String description, String... statements) {
		Objects.requireNonNull(statements, "statements");
		String[] copy = statements.clone();
		return new SchemaMigration(version, description, connection -> {
			try (Statement stmt = connection.createStatement()) {
				for (String sql : copy) {
					if (sql != null && !sql.isBlank()) {
						stmt.execute(sql);
					}
				}
			}
		});
	}
}
