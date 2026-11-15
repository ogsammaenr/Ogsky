package me.ogsammaenr.ogsky.core.internal.config;

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
 * Loads, validates, and creates {@code config/ogsky/core.json}.
 */
public final class CoreConfigLoader {
	private static final Gson GSON = new GsonBuilder()
		.setPrettyPrinting()
		.disableHtmlEscaping()
		.create();

	private CoreConfigLoader() {
	}

	public static CoreConfig loadOrCreate(Path configPath) {
		Objects.requireNonNull(configPath, "configPath");
		try {
			if (Files.notExists(configPath)) {
				CoreConfig config = CoreConfig.defaults();
				write(configPath, config);
				return config;
			}

			CoreConfig loaded;
			try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
				loaded = GSON.fromJson(reader, CoreConfig.class);
			}
			if (loaded == null) {
				loaded = CoreConfig.defaults();
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
			throw new UncheckedIOException("Failed to load Ogsky config at " + configPath, e);
		} catch (JsonParseException e) {
			throw new IllegalStateException("Malformed JSON in Ogsky config at " + configPath, e);
		}
	}

	public static void write(Path configPath, CoreConfig config) throws IOException {
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
