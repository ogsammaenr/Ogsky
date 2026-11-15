package me.ogsammaenr.ogsky.core.internal.blueprint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.datafixers.DataFixer;
import me.ogsammaenr.ogsky.core.api.OgskyCore;
import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Discovers, validates, and eagerly compresses {@link BlueprintBundle} definitions under {@code config/ogsky/blueprints/}
 * into compact in-memory {@link CompressedBlueprint} objects during startup and reloads.
 * Never generates starter {@code .nbt} content automatically; warns cleanly when the configured default bundle
 * does not yet contain a valid {@code overworld.nbt}.
 */
public final class BlueprintBundleRegistry {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Path blueprintsDir;
	private final String defaultBundleId;
	private final Map<String, BlueprintBundle> bundles = new LinkedHashMap<>();

	public BlueprintBundleRegistry(Path blueprintsDir, String defaultBundleId) {
		this.blueprintsDir = Objects.requireNonNull(blueprintsDir, "blueprintsDir").toAbsolutePath().normalize();
		this.defaultBundleId = normalizeBundleId(defaultBundleId);
	}

	public Path blueprintsDir() {
		return this.blueprintsDir;
	}

	public String defaultBundleId() {
		return this.defaultBundleId;
	}

	public synchronized void reload() {
		reload(null);
	}

	public synchronized void reload(@Nullable DataFixer fixerUpper) {
		this.bundles.clear();

		try {
			Files.createDirectories(this.blueprintsDir);
		} catch (IOException ex) {
			OgskyCore.LOGGER.error("Failed to create Ogsky blueprints directory {}", this.blueprintsDir, ex);
			return;
		}

		List<Path> subdirs;
		try (Stream<Path> stream = Files.list(this.blueprintsDir)) {
			subdirs = stream.filter(Files::isDirectory).sorted().toList();
		} catch (IOException ex) {
			OgskyCore.LOGGER.error("Failed to scan Ogsky blueprints directory {}", this.blueprintsDir, ex);
			return;
		}

		for (Path dir : subdirs) {
			String dirName = dir.getFileName().toString().toLowerCase(Locale.ROOT);
			if (!isValidBundleId(dirName)) {
				OgskyCore.LOGGER.warn("Skipping invalid blueprint directory name '{}' under {}", dirName, this.blueprintsDir);
				continue;
			}
			BlueprintBundle bundle = loadBundleDirectory(dir, dirName, fixerUpper);
			if (bundle != null) {
				this.bundles.put(bundle.id(), bundle);
			}
		}

		BlueprintBundle defaultBundle = this.bundles.get(this.defaultBundleId);
		if (defaultBundle == null || !defaultBundle.hasOverworld()) {
			OgskyCore.LOGGER.warn(
				"Starter blueprint bundle '{}' does not have overworld.nbt under {}. Place your .nbt blueprint files there before creating islands (then run '/isadmin reload').",
				this.defaultBundleId,
				this.blueprintsDir.resolve(this.defaultBundleId)
			);
		} else {
			OgskyCore.LOGGER.info(
				"Loaded {} blueprint bundle(s) compressed in RAM; starter bundle '{}' ready (overworld=true [{} B], nether={}, end={}).",
				this.bundles.size(),
				defaultBundle.id(),
				defaultBundle.overworld().compressedByteCount(),
				defaultBundle.hasNether(),
				defaultBundle.hasEnd()
			);
		}
	}

	public synchronized Optional<BlueprintBundle> find(String bundleId) {
		if (bundleId == null || bundleId.isBlank()) {
			return Optional.empty();
		}
		return Optional.ofNullable(this.bundles.get(bundleId.trim().toLowerCase(Locale.ROOT)));
	}

	public synchronized Optional<BlueprintBundle> resolveWithFallback(@Nullable String bundleId) {
		if (bundleId != null && !bundleId.isBlank()) {
			BlueprintBundle requested = this.bundles.get(bundleId.trim().toLowerCase(Locale.ROOT));
			if (requested != null) {
				return Optional.of(requested);
			}
		}
		return Optional.ofNullable(this.bundles.get(this.defaultBundleId));
	}

	public synchronized List<BlueprintBundle> listAll() {
		return this.bundles.values().stream()
			.sorted(Comparator.comparingInt((BlueprintBundle b) -> b.metadata().order).thenComparing(BlueprintBundle::id))
			.toList();
	}

	public StructureTemplate decompressTemplate(HolderGetter<Block> blockLookup, CompressedBlueprint blueprint) throws IOException {
		Objects.requireNonNull(blockLookup, "blockLookup");
		Objects.requireNonNull(blueprint, "blueprint");
		return blueprint.toStructureTemplate(blockLookup);
	}

	private @Nullable BlueprintBundle loadBundleDirectory(Path dir, String dirName, @Nullable DataFixer fixerUpper) {
		BlueprintBundleMetadata metadata = null;
		Path manifest = dir.resolve("bundle.json");
		boolean hasManifest = Files.isRegularFile(manifest);
		if (hasManifest) {
			try {
				metadata = GSON.fromJson(Files.readString(manifest), BlueprintBundleMetadata.class);
			} catch (Exception ex) {
				OgskyCore.LOGGER.error("Invalid bundle.json in {}: {}", dir, ex.getMessage(), ex);
				return null;
			}
		}
		if (metadata == null) {
			metadata = new BlueprintBundleMetadata();
		}
		metadata.normalizeDefaults(dirName);
		if (!isValidBundleId(metadata.id)) {
			OgskyCore.LOGGER.warn("Skipping bundle in {} with invalid metadata id '{}'", dir, metadata.id);
			return null;
		}

		CompressedBlueprint overworld = loadCompressedNbt(dir, metadata.id, metadata.overworld.file, fixerUpper);
		CompressedBlueprint nether = loadCompressedNbt(dir, metadata.id, metadata.nether.file, fixerUpper);
		CompressedBlueprint end = loadCompressedNbt(dir, metadata.id, metadata.end.file, fixerUpper);
		if (overworld == null && nether == null && end == null && !hasManifest) {
			return null;
		}
		return new BlueprintBundle(metadata.id, dir, metadata, overworld, nether, end);
	}

	private static @Nullable CompressedBlueprint loadCompressedNbt(
		Path bundleDir,
		String bundleId,
		String relativeFile,
		@Nullable DataFixer fixerUpper
	) {
		Path resolved = resolveExistingNbt(bundleDir, relativeFile);
		if (resolved == null) {
			return null;
		}
		try {
			return CompressedBlueprint.fromFile(resolved, fixerUpper);
		} catch (Exception ex) {
			OgskyCore.LOGGER.error(
				"Failed to read/compress blueprint NBT '{}' for bundle '{}': {}",
				resolved,
				bundleId,
				ex.getMessage(),
				ex
			);
			return null;
		}
	}

	private static @Nullable Path resolveExistingNbt(Path bundleDir, String relativeFile) {
		if (relativeFile == null || relativeFile.isBlank()) {
			return null;
		}
		Path resolved = bundleDir.resolve(relativeFile).normalize();
		if (!resolved.startsWith(bundleDir)) {
			OgskyCore.LOGGER.warn("Rejected out-of-bundle blueprint path '{}' in {}", relativeFile, bundleDir);
			return null;
		}
		return Files.isRegularFile(resolved) ? resolved : null;
	}

	private static String normalizeBundleId(String bundleId) {
		Objects.requireNonNull(bundleId, "bundleId");
		String normalized = bundleId.trim().toLowerCase(Locale.ROOT);
		if (!isValidBundleId(normalized)) {
			throw new IllegalArgumentException("Invalid bundle id: " + bundleId);
		}
		return normalized;
	}

	public static boolean isValidBundleId(String bundleId) {
		if (bundleId == null || bundleId.isEmpty() || bundleId.length() > 64) {
			return false;
		}
		for (int i = 0; i < bundleId.length(); i++) {
			char c = bundleId.charAt(i);
			boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
			if (!ok) {
				return false;
			}
		}
		return true;
	}
}
