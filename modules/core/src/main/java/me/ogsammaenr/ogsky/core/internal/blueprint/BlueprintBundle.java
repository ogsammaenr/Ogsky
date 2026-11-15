package me.ogsammaenr.ogsky.core.internal.blueprint;

import me.ogsammaenr.ogsky.core.api.island.IslandView;
import me.ogsammaenr.ogsky.core.api.island.RelativeSpawn;
import me.ogsammaenr.ogsky.core.internal.world.OgskyDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Validated in-memory descriptor for a per-island {@code .nbt} blueprint bundle.
 */
public record BlueprintBundle(
	String id,
	Path directory,
	BlueprintBundleMetadata metadata,
	@Nullable Path overworldFile,
	@Nullable Path netherFile,
	@Nullable Path endFile
) {
	public BlueprintBundle {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(directory, "directory");
		Objects.requireNonNull(metadata, "metadata");
	}

	public boolean hasOverworld() {
		return overworldFile != null;
	}

	public boolean hasNether() {
		return netherFile != null;
	}

	public boolean hasEnd() {
		return endFile != null;
	}

	public @Nullable Path fileFor(ResourceKey<Level> dimension) {
		Objects.requireNonNull(dimension, "dimension");
		if (dimension.equals(OgskyDimensions.OVERWORLD)) {
			return overworldFile;
		}
		if (dimension.equals(OgskyDimensions.NETHER)) {
			return netherFile;
		}
		if (dimension.equals(OgskyDimensions.END)) {
			return endFile;
		}
		return null;
	}

	public BlueprintBundleMetadata.DimensionSpec specFor(ResourceKey<Level> dimension) {
		Objects.requireNonNull(dimension, "dimension");
		if (dimension.equals(OgskyDimensions.OVERWORLD)) {
			return metadata.overworld;
		}
		if (dimension.equals(OgskyDimensions.NETHER)) {
			return metadata.nether;
		}
		if (dimension.equals(OgskyDimensions.END)) {
			return metadata.end;
		}
		throw new IllegalArgumentException("Unsupported blueprint dimension: " + dimension.identifier());
	}

	public BlockPos computePasteOrigin(
		IslandView island,
		ResourceKey<Level> dimension,
		Vec3i templateSize,
		int defaultSpawnY,
		int minLevelY,
		int maxLevelY
	) {
		BlueprintBundleMetadata.DimensionSpec spec = specFor(dimension);
		int originX = island.centerX() + spec.offsetX;
		int originZ = island.centerZ() + spec.offsetZ;
		if (spec.centerHorizontally) {
			originX -= templateSize.getX() / 2;
			originZ -= templateSize.getZ() / 2;
		}
		int maxAllowedOriginY = Math.max(minLevelY, maxLevelY - Math.max(1, templateSize.getY()) + 1);
		int originY = Math.clamp(defaultSpawnY + spec.offsetY, minLevelY, maxAllowedOriginY);
		return new BlockPos(originX, originY, originZ);
	}

	public RelativeSpawn computeSpawn(IslandView island, ResourceKey<Level> dimension, int defaultSpawnY) {
		BlueprintBundleMetadata.DimensionSpec spec = specFor(dimension);
		int clampedX = Math.clamp(island.centerX() + spec.spawnOffsetX, island.minX(), island.maxX());
		int clampedZ = Math.clamp(island.centerZ() + spec.spawnOffsetZ, island.minZ(), island.maxZ());
		int spawnY = defaultSpawnY + spec.spawnOffsetY;
		return RelativeSpawn.fromAbsolute(
			island.centerX(),
			island.centerZ(),
			clampedX + 0.5D,
			spawnY,
			clampedZ + 0.5D,
			spec.spawnYaw,
			spec.spawnPitch
		);
	}
}
