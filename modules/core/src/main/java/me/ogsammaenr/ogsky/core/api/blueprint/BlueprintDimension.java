package me.ogsammaenr.ogsky.core.api.blueprint;

import me.ogsammaenr.ogsky.core.api.world.OgskyDimensionKeys;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Skyblock blueprint dimensions supported for island creation and 3D preview rendering.
 */
public enum BlueprintDimension {
	OVERWORLD("overworld", "Overworld", OgskyDimensionKeys.OVERWORLD),
	NETHER("nether", "Nether", OgskyDimensionKeys.NETHER),
	END("end", "The End", OgskyDimensionKeys.END);

	private final String key;
	private final String displayName;
	private final ResourceKey<Level> dimensionKey;

	BlueprintDimension(String key, String displayName, ResourceKey<Level> dimensionKey) {
		this.key = key;
		this.displayName = displayName;
		this.dimensionKey = dimensionKey;
	}

	public String key() {
		return this.key;
	}

	public String displayName() {
		return this.displayName;
	}

	public ResourceKey<Level> dimensionKey() {
		return this.dimensionKey;
	}

	public boolean isSupportedBy(BlueprintBundleView bundle) {
		Objects.requireNonNull(bundle, "bundle");
		return switch (this) {
			case OVERWORLD -> bundle.hasOverworld();
			case NETHER -> bundle.hasNether();
			case END -> bundle.hasEnd();
		};
	}

	public static Optional<BlueprintDimension> fromKey(@Nullable String rawKey) {
		if (rawKey == null || rawKey.isBlank()) {
			return Optional.empty();
		}
		String normalized = rawKey.trim().toLowerCase(Locale.ROOT);
		for (BlueprintDimension value : values()) {
			if (value.key.equals(normalized)) {
				return Optional.of(value);
			}
		}
		return Optional.empty();
	}
}
