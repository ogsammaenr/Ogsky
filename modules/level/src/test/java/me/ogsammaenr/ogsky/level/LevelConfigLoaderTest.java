package me.ogsammaenr.ogsky.level;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LevelConfigLoaderTest {
	@TempDir
	Path tempDir;

	@Test
	void createsDefaultsAndPreservesCustomBlockEdits() throws Exception {
		Path path = tempDir.resolve("config").resolve("ogsky").resolve("level.json");
		LevelConfig initial = LevelConfigLoader.loadOrCreate(path);

		assertTrue(Files.exists(path));
		assertEquals(100L, initial.formula.pointsPerLevel);
		assertEquals(0L, initial.formula.defaultBlockValue);
		assertEquals(100L, initial.blockValues.get("minecraft:diamond_block"));
		assertEquals(10_000, initial.blockPointLimits.get("minecraft:cobblestone"));

		// Customize values and remove one default entry; reload must respect user customization.
		initial.blockValues.put("minecraft:diamond_block", 250L);
		initial.blockValues.remove("minecraft:cobblestone");
		LevelConfigLoader.write(path, initial);

		LevelConfig reloaded = LevelConfigLoader.loadOrCreate(path);
		assertEquals(250L, reloaded.blockValues.get("minecraft:diamond_block"));
		assertFalse(reloaded.blockValues.containsKey("minecraft:cobblestone"));
	}

	@Test
	void rejectsInvalidPointsPerLevelOrNegativeBlockValues() throws Exception {
		Path path = tempDir.resolve("invalid.json");
		Files.writeString(path, """
			{
			  "formula": { "pointsPerLevel": 0, "defaultBlockValue": 0 },
			  "blockValues": { "minecraft:stone": 2 }
			}
			""");
		assertThrows(IllegalArgumentException.class, () -> LevelConfigLoader.loadOrCreate(path));

		Files.writeString(path, """
			{
			  "formula": { "pointsPerLevel": 100, "defaultBlockValue": 0 },
			  "blockValues": { "minecraft:stone": -5 }
			}
			""");
		assertThrows(IllegalArgumentException.class, () -> LevelConfigLoader.loadOrCreate(path));
	}
}
