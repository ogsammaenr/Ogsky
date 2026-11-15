package me.ogsammaenr.ogsky.core.internal.world;

import me.ogsammaenr.ogsky.core.api.OgskyCore;
import net.minecraft.core.registries.Registries;
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
	public static final ResourceKey<Level> OVERWORLD = ResourceKey.create(Registries.DIMENSION, OgskyCore.id("overworld"));
	public static final ResourceKey<Level> NETHER = ResourceKey.create(Registries.DIMENSION, OgskyCore.id("nether"));
	public static final ResourceKey<Level> END = ResourceKey.create(Registries.DIMENSION, OgskyCore.id("end"));
	public static final List<ResourceKey<Level>> ALL = List.of(OVERWORLD, NETHER, END);

	private OgskyDimensions() {
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

	public static @Nullable ServerLevel getLevel(MinecraftServer server, ResourceKey<Level> dimension) {
		Objects.requireNonNull(server, "server");
		Objects.requireNonNull(dimension, "dimension");
		return server.getLevel(dimension);
	}
}
