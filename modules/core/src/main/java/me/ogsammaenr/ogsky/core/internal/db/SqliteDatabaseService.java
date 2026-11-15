package me.ogsammaenr.ogsky.core.internal.db;

import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.db.DatabaseService;
import me.ogsammaenr.ogsky.core.api.db.ModuleDatabase;
import me.ogsammaenr.ogsky.core.api.db.SchemaMigration;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Manages isolated per-module SQLite databases stored at {@code <dataRoot>/<moduleId>.db}.
 */
public final class SqliteDatabaseService implements DatabaseService {
	private static final Pattern VALID_MODULE_ID = Pattern.compile("^[a-z0-9_-]+$");

	private final Map<String, List<SchemaMigration>> schemas = new ConcurrentHashMap<>();
	private final Map<String, SqliteModuleDatabase> openDatabases = new ConcurrentHashMap<>();
	private final Object lifecycleLock = new Object();
	private volatile Path activeDataRoot;

	@Override
	public void registerModule(String moduleId, List<SchemaMigration> migrations) {
		validateModuleId(moduleId);
		Objects.requireNonNull(migrations, "migrations");
		List<SchemaMigration> copy = List.copyOf(migrations);

		synchronized (lifecycleLock) {
			List<SchemaMigration> previous = schemas.putIfAbsent(moduleId, copy);
			if (previous != null) {
				throw new IllegalStateException("Database schema already registered for module: " + moduleId);
			}
			if (activeDataRoot != null) {
				openSingle(moduleId, copy, activeDataRoot);
			}
		}
	}

	@Override
	public Optional<ModuleDatabase> find(String moduleId) {
		Objects.requireNonNull(moduleId, "moduleId");
		return Optional.ofNullable(openDatabases.get(moduleId));
	}

	/**
	 * Opens and migrates all registered module databases under {@code dataRoot}.
	 */
	public void openAllForRoot(Path dataRoot) {
		Objects.requireNonNull(dataRoot, "dataRoot");
		synchronized (lifecycleLock) {
			if (activeDataRoot != null) {
				closeAll();
			}
			activeDataRoot = dataRoot;
			for (Map.Entry<String, List<SchemaMigration>> entry : schemas.entrySet()) {
				openSingle(entry.getKey(), entry.getValue(), dataRoot);
			}
			OgskyCore.LOGGER.info("Opened {} Ogsky SQLite module database(s) at {}", openDatabases.size(), dataRoot);
		}
	}

	/**
	 * Flushes and closes all currently open module databases.
	 */
	public void closeAll() {
		synchronized (lifecycleLock) {
			RuntimeException firstFailure = null;
			for (Map.Entry<String, SqliteModuleDatabase> entry : openDatabases.entrySet()) {
				try {
					entry.getValue().close();
				} catch (RuntimeException e) {
					if (firstFailure == null) {
						firstFailure = e;
					} else {
						firstFailure.addSuppressed(e);
					}
				}
			}
			openDatabases.clear();
			activeDataRoot = null;
			if (firstFailure != null) {
				throw firstFailure;
			}
		}
	}

	private void openSingle(String moduleId, List<SchemaMigration> migrations, Path dataRoot) {
		Path dbPath = dataRoot.resolve(moduleId + ".db");
		SqliteModuleDatabase db = SqliteModuleDatabase.open(moduleId, dbPath, migrations);
		openDatabases.put(moduleId, db);
	}

	private static void validateModuleId(String moduleId) {
		if (moduleId == null || !VALID_MODULE_ID.matcher(moduleId).matches()) {
			throw new IllegalArgumentException(
				"Invalid moduleId '" + moduleId + "'. Expected lowercase alphanumeric, '_' or '-'."
			);
		}
	}
}
