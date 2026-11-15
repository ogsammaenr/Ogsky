package me.ogsammaenr.ogsky.core.api.world;

import me.ogsammaenr.ogsky.core.api.OgskyCore;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Objects;

/**
 * Public read-only {@link ResourceKey} constants for Ogsky's dedicated 1:1 Skyblock dimensions.
 */
public final class OgskyDimensionKeys {
	public static final ResourceKey<Level> OVERWORLD = ResourceKey.create(Registries.DIMENSION, OgskyCore.id("overworld"));
	public static final ResourceKey<Level> NETHER = ResourceKey.create(Registries.DIMENSION, OgskyCore.id("nether"));
	public static final ResourceKey<Level> END = ResourceKey.create(Registries.DIMENSION, OgskyCore.id("end"));
	public static final List<ResourceKey<Level>> ALL = List.of(OVERWORLD, NETHER, END);

	private OgskyDimensionKeys() {
	}

	public static boolean isSkyblockDimension(ResourceKey<Level> dimension) {
		Objects.requireNonNull(dimension, "dimension");
		return dimension.equals(OVERWORLD) || dimension.equals(NETHER) || dimension.equals(END);
	}

	public static String dimensionName(ResourceKey<Level> dimension) {
		Objects.requireNonNull(dimension, "dimension");
		if (dimension.equals(OVERWORLD)) {
			return "overworld";
		}
		if (dimension.equals(NETHER)) {
			return "nether";
		}
		if (dimension.equals(END)) {
			return "end";
		}
		return dimension.identifier().toString();
	}
}
