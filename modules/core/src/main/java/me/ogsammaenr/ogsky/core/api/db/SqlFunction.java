package me.ogsammaenr.ogsky.core.api.db;

import java.sql.SQLException;

/**
 * Function variant permitted to throw {@link SQLException}.
 */
@FunctionalInterface
public interface SqlFunction<T, R> {
	R apply(T value) throws SQLException;
}
