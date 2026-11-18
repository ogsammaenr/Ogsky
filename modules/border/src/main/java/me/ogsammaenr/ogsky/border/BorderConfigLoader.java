package me.ogsammaenr.ogsky.border;

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
 * Loads or creates {@code config/ogsky/border.json}.
 */
public final class BorderConfigLoader {
	public static final Path DEFAULT_PATH = Path.of("config", "ogsky", "border.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private BorderConfigLoader() {
	}

	public static BorderConfig loadOrCreate() {
		return loadOrCreate(DEFAULT_PATH);
	}

	public static BorderConfig loadOrCreate(Path path) {
		try {
			if (Files.notExists(path)) {
				Files.createDirectories(path.getParent());
				BorderConfig defaults = BorderConfig.defaults();
				try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
					GSON.toJson(defaults, writer);
				}
				return defaults;
			}
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				BorderConfig loaded = GSON.fromJson(reader, BorderConfig.class);
				if (loaded == null) {
					loaded = BorderConfig.defaults();
				}
				loaded.ensureDefaultsAndValidate();
				return loaded;
			}
		} catch (Exception ex) {
			OgskyCore.LOGGER.error("Failed loading Ogsky border config from {}: {}. Using defaults.", path, ex.toString());
			return BorderConfig.defaults();
		}
	}

	public static void save(Path path, BorderConfig config) throws IOException {
		config.ensureDefaultsAndValidate();
		Files.createDirectories(path.getParent());
		try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
			GSON.toJson(config, writer);
		}
	}
}
