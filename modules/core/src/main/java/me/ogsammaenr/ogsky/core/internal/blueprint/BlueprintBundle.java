package me.ogsammaenr.ogsky.core.internal.blueprint;

import me.ogsammaenr.ogsky.core.api.blueprint.BlueprintBundleView;
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
 * Stores eagerly loaded, GZIP-compressed {@link CompressedBlueprint} payloads directly in RAM.
 */
public record BlueprintBundle(
	String id,
	Path directory,
	BlueprintBundleMetadata metadata,
	@Nullable CompressedBlueprint overworld,
	@Nullable CompressedBlueprint nether,
	@Nullable CompressedBlueprint end
) {
	public BlueprintBundle {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(directory, "directory");
		Objects.requireNonNull(metadata, "metadata");
	}

	public boolean hasOverworld() {
		return overworld != null;
	}

	public boolean hasNether() {
		return nether != null;
	}

	public boolean hasEnd() {
		return end != null;
	}

	public BlueprintBundleView toView() {
		return new BlueprintBundleView(
			id,
			metadata.displayName,
			metadata.description,
			metadata.iconItem,
			metadata.requiresPermission,
			metadata.permissionNode,
			metadata.order,
			hasOverworld(),
			hasNether(),
			hasEnd()
		);
	}

	public @Nullable CompressedBlueprint blueprintFor(ResourceKey<Level> dimension) {
		Objects.requireNonNull(dimension, "dimension");
		if (dimension.equals(OgskyDimensions.OVERWORLD)) {
			return overworld;
		}
		if (dimension.equals(OgskyDimensions.NETHER)) {
			return nether;
		}
		if (dimension.equals(OgskyDimensions.END)) {
			return end;
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
		CompressedBlueprint blueprint,
		int defaultAnchorY,
		int minLevelY,
		int maxLevelY
	) {
		Objects.requireNonNull(blueprint, "blueprint");
		return computePasteOrigin(
			island,
			dimension,
			blueprint.size(),
			blueprint.bedrockOffset(),
			defaultAnchorY,
			minLevelY,
			maxLevelY
		);
	}

	public BlockPos computePasteOrigin(
		IslandView island,
		ResourceKey<Level> dimension,
		Vec3i templateSize,
		@Nullable BlockPos bedrockOffset,
		int defaultAnchorY,
		int minLevelY,
		int maxLevelY
	) {
		BlueprintBundleMetadata.DimensionSpec spec = specFor(dimension);
		int maxAllowedOriginY = Math.max(minLevelY, maxLevelY - Math.max(1, templateSize.getY()) + 1);
		int originX;
		int originY;
		int originZ;
		if (bedrockOffset != null) {
			originX = (island.centerX() + spec.offsetX) - bedrockOffset.getX();
			originZ = (island.centerZ() + spec.offsetZ) - bedrockOffset.getZ();
			originY = Math.clamp((defaultAnchorY + spec.offsetY) - bedrockOffset.getY(), minLevelY, maxAllowedOriginY);
		} else {
			originX = island.centerX() + spec.offsetX;
			originZ = island.centerZ() + spec.offsetZ;
			if (spec.centerHorizontally) {
				originX -= templateSize.getX() / 2;
				originZ -= templateSize.getZ() / 2;
			}
			originY = Math.clamp(defaultAnchorY + spec.offsetY, minLevelY, maxAllowedOriginY);
		}
		return new BlockPos(originX, originY, originZ);
	}

	public RelativeSpawn computeSpawn(
		IslandView island,
		ResourceKey<Level> dimension,
		@Nullable CompressedBlueprint blueprint,
		BlockPos pasteOrigin,
		int defaultSpawnY
	) {
		if (blueprint != null && blueprint.spawnMarker() != null) {
			CompressedBlueprint.SpawnMarker marker = blueprint.spawnMarker();
			BlockPos worldMarkerPos = pasteOrigin.offset(marker.blockPos());
			int clampedX = Math.clamp(worldMarkerPos.getX(), island.minX(), island.maxX());
			int clampedZ = Math.clamp(worldMarkerPos.getZ(), island.minZ(), island.maxZ());
			return RelativeSpawn.fromAbsolute(
				island.centerX(),
				island.centerZ(),
				clampedX + 0.5D,
				worldMarkerPos.getY(),
				clampedZ + 0.5D,
				marker.yaw(),
				marker.pitch()
			);
		}
		return computeFallbackSpawn(island, dimension, defaultSpawnY);
	}

	public RelativeSpawn computeFallbackSpawn(IslandView island, ResourceKey<Level> dimension, int defaultSpawnY) {
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
