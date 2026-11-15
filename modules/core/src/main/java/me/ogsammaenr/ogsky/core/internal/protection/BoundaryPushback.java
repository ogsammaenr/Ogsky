package me.ogsammaenr.ogsky.core.internal.protection;

import me.ogsammaenr.ogsky.core.api.island.IslandView;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Computes outside-perimeter pushback positions and gentle outward knockback vectors
 * when a denied player approaches a locked or banned island from outside.
 */
public final class BoundaryPushback {
	public static final double HORIZONTAL_KNOCKBACK = 0.35D;
	public static final double VERTICAL_KNOCKBACK = 0.12D;
	public static final double BORDER_OFFSET = 0.55D;
	public static final double MAX_APPROACH_DISTANCE_SQ = 16.0D * 16.0D;

	private static final double MIN_NORMAL_EPSILON = 1.0E-4D;

	private BoundaryPushback() {
	}

	public record PushbackResult(Vec3 position, Vec3 knockback) {
	}

	/**
	 * Computes a safe outside position and outward knockback from a player's previous position outside {@code island}.
	 * Returns {@code null} if {@code prevOutsidePos} is already inside {@code island} or farther than
	 * {@link #MAX_APPROACH_DISTANCE_SQ} blocks from the boundary (e.g., direct cross-map teleport).
	 */
	public static @Nullable PushbackResult compute(
		IslandView island,
		Vec3 prevOutsidePos,
		boolean includeVerticalKnockback
	) {
		Objects.requireNonNull(island, "island");
		Objects.requireNonNull(prevOutsidePos, "prevOutsidePos");

		int prevBlockX = Mth.floor(prevOutsidePos.x);
		int prevBlockZ = Mth.floor(prevOutsidePos.z);
		if (island.contains(prevBlockX, prevBlockZ)) {
			return null;
		}

		double boxMinX = island.minX();
		double boxMaxX = island.maxX() + 1.0D;
		double boxMinZ = island.minZ();
		double boxMaxZ = island.maxZ() + 1.0D;

		double clampedX = Mth.clamp(prevOutsidePos.x, boxMinX, boxMaxX);
		double clampedZ = Mth.clamp(prevOutsidePos.z, boxMinZ, boxMaxZ);
		double distSq = (prevOutsidePos.x - clampedX) * (prevOutsidePos.x - clampedX)
			+ (prevOutsidePos.z - clampedZ) * (prevOutsidePos.z - clampedZ);
		if (distSq > MAX_APPROACH_DISTANCE_SQ) {
			return null;
		}

		boolean west = prevBlockX < island.minX();
		boolean east = prevBlockX > island.maxX();
		boolean north = prevBlockZ < island.minZ();
		boolean south = prevBlockZ > island.maxZ();

		double rawDx = 0.0D;
		double targetX = prevOutsidePos.x;
		if (west) {
			rawDx = -Math.max(MIN_NORMAL_EPSILON, boxMinX - prevOutsidePos.x);
			targetX = Math.min(prevOutsidePos.x, boxMinX - BORDER_OFFSET);
		} else if (east) {
			rawDx = Math.max(MIN_NORMAL_EPSILON, prevOutsidePos.x - boxMaxX);
			targetX = Math.max(prevOutsidePos.x, boxMaxX + BORDER_OFFSET);
		}

		double rawDz = 0.0D;
		double targetZ = prevOutsidePos.z;
		if (north) {
			rawDz = -Math.max(MIN_NORMAL_EPSILON, boxMinZ - prevOutsidePos.z);
			targetZ = Math.min(prevOutsidePos.z, boxMinZ - BORDER_OFFSET);
		} else if (south) {
			rawDz = Math.max(MIN_NORMAL_EPSILON, prevOutsidePos.z - boxMaxZ);
			targetZ = Math.max(prevOutsidePos.z, boxMaxZ + BORDER_OFFSET);
		}

		double len = Math.hypot(rawDx, rawDz);
		if (len <= 0.0D) {
			return null;
		}
		double nx = rawDx / len;
		double nz = rawDz / len;

		Vec3 safePos = new Vec3(targetX, prevOutsidePos.y, targetZ);
		double ky = includeVerticalKnockback ? VERTICAL_KNOCKBACK : 0.0D;
		Vec3 knockback = new Vec3(nx * HORIZONTAL_KNOCKBACK, ky, nz * HORIZONTAL_KNOCKBACK);
		return new PushbackResult(safePos, knockback);
	}
}
