package me.ogsammaenr.ogsky.core.api.db;

import java.util.List;
import java.util.Optional;

/**
 * Central lifecycle manager for per-module SQLite databases under {@code <world>/ogsky/data/<moduleId>.db}.
 */
public interface DatabaseService {
	/**
	 * Registers a module's schema migrations. The database file is opened and migrated when a world starts
	 * (or immediately if a world is already active).
	 */
	void registerModule(String moduleId, List<SchemaMigration> migrations);

	Optional<ModuleDatabase> find(String moduleId);

	default ModuleDatabase require(String moduleId) {
		return find(moduleId).orElseThrow(() ->
			new IllegalStateException("Module database is not open for module: " + moduleId)
		);
	}
}
