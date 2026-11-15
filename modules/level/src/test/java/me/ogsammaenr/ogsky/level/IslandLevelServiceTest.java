package me.ogsammaenr.ogsky.level;

import me.ogsammaenr.ogsky.core.api.event.IslandDeletedEvent;
import me.ogsammaenr.ogsky.core.internal.OgskyCoreApiImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IslandLevelServiceTest {
	@TempDir
	Path tempDir;

	@Test
	void savesQueriesAndCleansUpOnIslandDeletedEvent() {
		OgskyCoreApiImpl core = new OgskyCoreApiImpl();
		core.databases().registerModule(IslandLevelService.MODULE_DB_ID, IslandLevelService.MIGRATIONS);
		core.sqliteDatabases().openAllForRoot(tempDir);

		try {
			IslandLevelService service = new IslandLevelService(core);
			core.events().subscribe(
				IslandDeletedEvent.class,
				event -> service.deleteIslandLevel(event.islandId()).join()
			);

			UUID islandId = UUID.randomUUID();
			service.saveLevel(islandId, 15L, 1500L).join();
			assertEquals(Optional.of(15L), service.findLevel(islandId).join());

			core.events().publish(new IslandDeletedEvent(islandId));
			assertTrue(service.findLevel(islandId).join().isEmpty());
		} finally {
			core.sqliteDatabases().closeAll();
		}
	}
}
