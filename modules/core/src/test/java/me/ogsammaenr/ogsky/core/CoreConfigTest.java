package me.ogsammaenr.ogsky.core;

import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfig;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreConfigTest {
	@TempDir
	Path tempDir;

	@Test
	void createsDefaultsAndMergesPartialConfig() throws IOException {
		Path path = tempDir.resolve("ogsky/core.json");
		CoreConfig created = CoreConfigLoader.loadOrCreate(path);
		assertTrue(Files.exists(path));
		assertEquals(1024, created.grid.cellSize);
		assertEquals(50, created.island.initialHalfSize);
		assertEquals(1, created.membership.maxCoop);
		assertEquals(2, created.membership.maxTrusted);
		assertEquals("default", created.blueprints.defaultBundle);
		assertEquals(4, created.blueprints.welcomeSignLines.size());
		assertEquals("Ziyaretçi", created.roleDisplayName(IslandRole.VISITOR));

		// Partial JSON should preserve user values and restore missing defaults
		Files.writeString(path, """
			{
			  "grid": { "cellSize": 512 },
			  "island": { "initialHalfSize": 40, "maxHalfSize": 200 },
			  "blueprints": { "welcomeSignLines": ["&6Selam", "{player}"] }
			}
			""");
		CoreConfig reloaded = CoreConfigLoader.loadOrCreate(path);
		assertEquals(512, reloaded.grid.cellSize);
		assertEquals(40, reloaded.island.initialHalfSize);
		assertEquals(200, reloaded.island.maxHalfSize);
		assertEquals("Sahip", reloaded.roleDisplayName(IslandRole.OWNER));
		assertEquals(2, reloaded.admin.fallbackOpLevel);
		assertEquals(List.of("&6Selam", "{player}"), reloaded.blueprints.welcomeSignLines);
	}

	@Test
	void rejectsInvalidGridAndBoundaryInvariants() {
		CoreConfig nonMultiple16 = CoreConfig.defaults();
		nonMultiple16.grid.cellSize = 1000;
		assertThrows(IllegalArgumentException.class, nonMultiple16::ensureDefaultsAndValidate);

		CoreConfig overlapsNeighbor = CoreConfig.defaults();
		overlapsNeighbor.grid.cellSize = 1024;
		overlapsNeighbor.island.maxHalfSize = 512; // Must be strictly < 512
		assertThrows(IllegalArgumentException.class, overlapsNeighbor::ensureDefaultsAndValidate);

		CoreConfig initialExceedsMax = CoreConfig.defaults();
		initialExceedsMax.island.initialHalfSize = 100;
		initialExceedsMax.island.maxHalfSize = 80;
		assertThrows(IllegalArgumentException.class, initialExceedsMax::ensureDefaultsAndValidate);

		CoreConfig badVoidMode = CoreConfig.defaults();
		badVoidMode.voidTeleport.mode = "INVALID";
		assertThrows(IllegalArgumentException.class, badVoidMode::ensureDefaultsAndValidate);
	}
}
