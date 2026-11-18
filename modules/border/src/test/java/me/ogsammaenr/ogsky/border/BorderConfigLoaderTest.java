package me.ogsammaenr.ogsky.border;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BorderConfigLoaderTest {
	@TempDir
	Path tempDir;

	@Test
	void createsDefaultsAndPreservesCustomValues() throws Exception {
		Path path = tempDir.resolve("config").resolve("ogsky").resolve("border.json");
		BorderConfig initial = BorderConfigLoader.loadOrCreate(path);

		assertTrue(Files.exists(path));
		assertTrue(initial.enabled);
		assertFalse(initial.defaultEnabledForPlayers);
		assertEquals("BLUE", initial.defaultColor);
		assertTrue(initial.allowColorSelection);
		assertTrue(initial.allowInOverworld);
		assertTrue(initial.allowInNether);
		assertTrue(initial.allowInEnd);
		assertEquals(0, initial.warningBlocks);
		assertEquals(0, initial.warningTime);

		initial.defaultEnabledForPlayers = true;
		initial.defaultColor = "green";
		initial.warningBlocks = 4;
		initial.allowInEnd = false;
		BorderConfigLoader.save(path, initial);

		BorderConfig reloaded = BorderConfigLoader.loadOrCreate(path);
		assertTrue(reloaded.defaultEnabledForPlayers);
		assertEquals("GREEN", reloaded.defaultColor);
		assertEquals(4, reloaded.warningBlocks);
		assertFalse(reloaded.allowInEnd);
	}

	@Test
	void sanitizesInvalidValuesAndCorruptJson() throws Exception {
		Path invalidPath = tempDir.resolve("invalid-border.json");
		Files.writeString(invalidPath, """
			{
			  "enabled": true,
			  "defaultColor": "purple",
			  "warningBlocks": -5,
			  "warningTime": 999
			}
			""");
		BorderConfig sanitized = BorderConfigLoader.loadOrCreate(invalidPath);
		assertEquals("BLUE", sanitized.defaultColor);
		assertEquals(0, sanitized.warningBlocks);
		assertEquals(60, sanitized.warningTime);

		Path corruptPath = tempDir.resolve("corrupt-border.json");
		Files.writeString(corruptPath, "{ invalid json");
		BorderConfig fallback = BorderConfigLoader.loadOrCreate(corruptPath);
		assertTrue(fallback.enabled);
		assertEquals("BLUE", fallback.defaultColor);
	}
}
