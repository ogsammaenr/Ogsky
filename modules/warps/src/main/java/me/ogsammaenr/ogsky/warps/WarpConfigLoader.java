package me.ogsammaenr.ogsky.warps;

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
 * Loads or creates {@code config/ogsky/warps.json}.
 */
public final class WarpConfigLoader {
	public static final Path DEFAULT_PATH = Path.of("config", "ogsky", "warps.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private WarpConfigLoader() {
	}

	public static WarpConfig loadOrCreate() {
		return loadOrCreate(DEFAULT_PATH);
	}

	public static WarpConfig loadOrCreate(Path path) {
		try {
			if (Files.notExists(path)) {
				Files.createDirectories(path.getParent());
				WarpConfig defaults = WarpConfig.defaults();
				try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
					GSON.toJson(defaults, writer);
				}
				return defaults;
			}
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				WarpConfig loaded = GSON.fromJson(reader, WarpConfig.class);
				if (loaded == null) {
					loaded = WarpConfig.defaults();
				}
				loaded.ensureDefaultsAndValidate();
				return loaded;
			}
		} catch (Exception ex) {
			OgskyCore.LOGGER.error("Failed loading Ogsky warps config from {}: {}. Using defaults.", path, ex.toString());
			return WarpConfig.defaults();
		}
	}

	public static void save(Path path, WarpConfig config) throws IOException {
		config.ensureDefaultsAndValidate();
		Files.createDirectories(path.getParent());
		try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
			GSON.toJson(config, writer);
		}
	}
}
