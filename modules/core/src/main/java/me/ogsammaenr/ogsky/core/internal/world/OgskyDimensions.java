package me.ogsammaenr.ogsky.core.internal.world;

import me.ogsammaenr.ogsky.core.api.world.OgskyDimensionKeys;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Canonical {@link ResourceKey} identifiers for Ogsky's dedicated 1:1 Skyblock dimensions.
 */
public final class OgskyDimensions {
	public static final ResourceKey<Level> OVERWORLD = OgskyDimensionKeys.OVERWORLD;
	public static final ResourceKey<Level> NETHER = OgskyDimensionKeys.NETHER;
	public static final ResourceKey<Level> END = OgskyDimensionKeys.END;
	public static final List<ResourceKey<Level>> ALL = OgskyDimensionKeys.ALL;

	private OgskyDimensions() {
	}

	public static boolean isSkyblockDimension(ResourceKey<Level> dimension) {
		return OgskyDimensionKeys.isSkyblockDimension(dimension);
	}

	public static String dimensionName(ResourceKey<Level> dimension) {
		return OgskyDimensionKeys.dimensionName(dimension);
	}

	public static @Nullable ServerLevel getLevel(MinecraftServer server, ResourceKey<Level> dimension) {
		Objects.requireNonNull(server, "server");
		Objects.requireNonNull(dimension, "dimension");
		return server.getLevel(dimension);
	}
}
