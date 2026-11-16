package me.ogsammaenr.ogsky.limits;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.ogsammaenr.ogsky.core.api.OgskyCore;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads or creates {@code config/ogsky/limits.json}.
 */
public final class LimitConfigLoader {
	public static final Path DEFAULT_PATH = Path.of("config", "ogsky", "limits.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private LimitConfigLoader() {
	}

	public static LimitConfig loadOrCreate() {
		return loadOrCreate(DEFAULT_PATH);
	}

	public static LimitConfig loadOrCreate(Path path) {
		try {
			if (Files.notExists(path)) {
				Files.createDirectories(path.getParent());
				LimitConfig defaults = LimitConfig.defaults();
				try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
					GSON.toJson(defaults, writer);
				}
				return defaults;
			}
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				LimitConfig loaded = GSON.fromJson(reader, LimitConfig.class);
				if (loaded == null) {
					loaded = LimitConfig.defaults();
				}
				loaded.ensureDefaultsAndValidate();
				return loaded;
			}
		} catch (Exception ex) {
			OgskyCore.LOGGER.error("Failed loading Ogsky limits config from {}: {}. Using defaults.", path, ex.toString());
			return LimitConfig.defaults();
		}
	}

	public static void save(Path path, LimitConfig config) throws IOException {
		config.ensureDefaultsAndValidate();
		Files.createDirectories(path.getParent());
		try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
			GSON.toJson(config, writer);
		}
	}
}
