package me.ogsammaenr.ogsky.level;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Loads, validates, and creates {@code config/ogsky/level.json}.
 */
public final class LevelConfigLoader {
	private static final Gson GSON = new GsonBuilder()
		.setPrettyPrinting()
		.disableHtmlEscaping()
		.create();

	private LevelConfigLoader() {
	}

	public static LevelConfig loadOrCreate(Path configPath) {
		Objects.requireNonNull(configPath, "configPath");
		try {
			if (Files.notExists(configPath)) {
				LevelConfig config = LevelConfig.defaults();
				write(configPath, config);
				return config;
			}

			LevelConfig loaded;
			try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
				loaded = GSON.fromJson(reader, LevelConfig.class);
			}
			if (loaded == null) {
				loaded = LevelConfig.defaults();
				write(configPath, loaded);
				return loaded;
			}

			String beforeCanonical = GSON.toJson(loaded);
			loaded.ensureDefaultsAndValidate();
			String afterCanonical = GSON.toJson(loaded);
			if (!beforeCanonical.equals(afterCanonical)) {
				write(configPath, loaded);
			}
			return loaded;
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to load Ogsky level config at " + configPath, e);
		} catch (JsonParseException e) {
			throw new IllegalStateException("Malformed JSON in Ogsky level config at " + configPath, e);
		}
	}

	public static void write(Path configPath, LevelConfig config) throws IOException {
		Objects.requireNonNull(configPath, "configPath");
		Objects.requireNonNull(config, "config");
		config.ensureDefaultsAndValidate();
		Path parent = configPath.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
			GSON.toJson(config, writer);
		}
	}
}
