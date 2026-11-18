package me.ogsammaenr.ogsky.border;

import me.ogsammaenr.ogsky.core.internal.OgskyCoreApiImpl;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BorderRepositoryTest {
	@TempDir
	Path tempDir;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void persistsAndReloadsPlayerBorderPreferences() {
		OgskyCoreApiImpl core = new OgskyCoreApiImpl();
		core.databases().registerModule(BorderRepository.MODULE_ID, BorderRepository.MIGRATIONS);
		core.sqliteDatabases().openAllForRoot(tempDir);

		try {
			BorderRepository repo = new BorderRepository(core.databases());
			UUID playerA = UUID.randomUUID();
			UUID playerB = UUID.randomUUID();

			repo.upsertAsync(new PlayerBorderPreferences(playerA, true, BorderColor.GREEN, Instant.now())).join();
			repo.upsertAsync(new PlayerBorderPreferences(playerB, false, BorderColor.RED, Instant.now())).join();

			Map<UUID, PlayerBorderPreferences> loaded = repo.loadAllBlocking(BorderColor.BLUE);
			assertEquals(2, loaded.size());
			assertTrue(loaded.get(playerA).enabled());
			assertEquals(BorderColor.GREEN, loaded.get(playerA).color());
			assertFalse(loaded.get(playerB).enabled());
			assertEquals(BorderColor.RED, loaded.get(playerB).color());

			// Overwrite existing preference
			repo.upsertAsync(loaded.get(playerA).withEnabled(false).withColor(BorderColor.BLUE)).join();
			Map<UUID, PlayerBorderPreferences> reloaded = repo.loadAllBlocking(BorderColor.BLUE);
			assertFalse(reloaded.get(playerA).enabled());
			assertEquals(BorderColor.BLUE, reloaded.get(playerA).color());
		} finally {
			core.sqliteDatabases().closeAll();
		}
	}
}
