package me.ogsammaenr.ogsky.warps;

import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable snapshot of an island warp entry backed by RAM state and persisted in {@code warps.db}.
 * Coordinates store horizontal offsets relative to the owning island's {@code (centerX, centerZ)}.
 */
public record IslandWarp(
	UUID islandId,
	String slug,
	String displayName,
	Identifier dimensionId,
	double relX,
	double y,
	double relZ,
	float yaw,
	float pitch,
	String categoryId,
	@Nullable String iconItemId,
	String description,
	boolean enabled,
	boolean isDefault,
	long visitCount,
	UUID createdBy,
	long createdAt,
	long updatedAt
) {
	public IslandWarp {
		Objects.requireNonNull(islandId, "islandId");
		Objects.requireNonNull(slug, "slug");
		Objects.requireNonNull(displayName, "displayName");
		Objects.requireNonNull(dimensionId, "dimensionId");
		Objects.requireNonNull(categoryId, "categoryId");
		Objects.requireNonNull(description, "description");
		Objects.requireNonNull(createdBy, "createdBy");
		visitCount = Math.max(0L, visitCount);
	}

	public double worldX(int centerX) {
		return centerX + relX;
	}

	public double worldZ(int centerZ) {
		return centerZ + relZ;
	}

	public IslandWarp withLocation(
		Identifier newDimensionId,
		double newRelX,
		double newY,
		double newRelZ,
		float newYaw,
		float newPitch,
		long now
	) {
		return new IslandWarp(
			islandId,
			slug,
			displayName,
			newDimensionId,
			newRelX,
			newY,
			newRelZ,
			newYaw,
			newPitch,
			categoryId,
			iconItemId,
			description,
			enabled,
			isDefault,
			visitCount,
			createdBy,
			createdAt,
			now
		);
	}

	public IslandWarp withCategory(String newCategoryId, long now) {
		return new IslandWarp(
			islandId,
			slug,
			displayName,
			dimensionId,
			relX,
			y,
			relZ,
			yaw,
			pitch,
			newCategoryId,
			iconItemId,
			description,
			enabled,
			isDefault,
			visitCount,
			createdBy,
			createdAt,
			now
		);
	}

	public IslandWarp withIcon(@Nullable String newIconItemId, long now) {
		return new IslandWarp(
			islandId,
			slug,
			displayName,
			dimensionId,
			relX,
			y,
			relZ,
			yaw,
			pitch,
			categoryId,
			newIconItemId,
			description,
			enabled,
			isDefault,
			visitCount,
			createdBy,
			createdAt,
			now
		);
	}

	public IslandWarp withDescription(String newDescription, long now) {
		return new IslandWarp(
			islandId,
			slug,
			displayName,
			dimensionId,
			relX,
			y,
			relZ,
			yaw,
			pitch,
			categoryId,
			iconItemId,
			newDescription,
			enabled,
			isDefault,
			visitCount,
			createdBy,
			createdAt,
			now
		);
	}

	public IslandWarp withEnabled(boolean newEnabled, long now) {
		return new IslandWarp(
			islandId,
			slug,
			displayName,
			dimensionId,
			relX,
			y,
			relZ,
			yaw,
			pitch,
			categoryId,
			iconItemId,
			description,
			newEnabled,
			isDefault,
			visitCount,
			createdBy,
			createdAt,
			now
		);
	}

	public IslandWarp withDefault(boolean newDefault, long now) {
		return new IslandWarp(
			islandId,
			slug,
			displayName,
			dimensionId,
			relX,
			y,
			relZ,
			yaw,
			pitch,
			categoryId,
			iconItemId,
			description,
			enabled,
			newDefault,
			visitCount,
			createdBy,
			createdAt,
			now
		);
	}

	public IslandWarp withIncrementedVisits() {
		return new IslandWarp(
			islandId,
			slug,
			displayName,
			dimensionId,
			relX,
			y,
			relZ,
			yaw,
			pitch,
			categoryId,
			iconItemId,
			description,
			enabled,
			isDefault,
			visitCount + 1L,
			createdBy,
			createdAt,
			updatedAt
		);
	}
}
