package me.ogsammaenr.ogsky.core.api.db;

import java.sql.SQLException;

/**
 * Consumer variant permitted to throw {@link SQLException}.
 */
@FunctionalInterface
public interface SqlConsumer<T> {
	void accept(T value) throws SQLException;
}
