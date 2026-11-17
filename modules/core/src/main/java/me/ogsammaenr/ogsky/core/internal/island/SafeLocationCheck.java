package me.ogsammaenr.ogsky.core.internal.island;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Strict anti-trap landing safety validator for Skyblock teleport targets.
 */
public final class SafeLocationCheck {
	private static final double MIN_GROUND_PROBE_DEPTH = 0.55D;
	private static final double GROUND_PROBE_HALF_WIDTH = 0.15D;
	private static final double THIN_SUPPORT_THRESHOLD = 0.15D;

	private SafeLocationCheck() {
	}

	/**
	 * Loads the chunk(s) overlapping {@code (x, y, z)} and returns {@code true} only if a standing
	 * player fits without block suffocation, rests above non-hazardous solid collision support, and
	 * avoids lava, fire, portals, drowning water, cactus, powder snow, or void traps.
	 */
	public static boolean isLocationSafe(ServerLevel level, double x, double y, double z) {
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
			return false;
		}
		AABB playerBox = EntityTypes.PLAYER.getDimensions().makeBoundingBox(x, y, z);
		if (y < level.getMinY() + 1.0D || playerBox.maxY > level.getMaxY() - 1.0D) {
			return false;
		}

		ensureChunksLoaded(level, playerBox);

		if (!level.noBlockCollision(null, playerBox)) {
			return false;
		}

		AABB groundProbe = new AABB(
			x - GROUND_PROBE_HALF_WIDTH,
			y - MIN_GROUND_PROBE_DEPTH,
			z - GROUND_PROBE_HALF_WIDTH,
			x + GROUND_PROBE_HALF_WIDTH,
			y + 0.02D,
			z + GROUND_PROBE_HALF_WIDTH
		);
		if (level.noBlockCollision(null, groundProbe)) {
			return false;
		}

		int minX = Mth.floor(playerBox.minX);
		int maxX = Mth.floor(playerBox.maxX);
		int minBodyY = Mth.floor(playerBox.minY);
		int maxBodyY = Mth.floor(playerBox.maxY);
		int minZ = Mth.floor(playerBox.minZ);
		int maxZ = Mth.floor(playerBox.maxZ);
		int headMinY = Mth.floor(y + 1.0D);

		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int bx = minX; bx <= maxX; bx++) {
			for (int bz = minZ; bz <= maxZ; bz++) {
				for (int by = minBodyY; by <= maxBodyY; by++) {
					cursor.set(bx, by, bz);
					BlockState state = level.getBlockState(cursor);
					if (isHazardous(state)) {
						return false;
					}
					if (by >= headMinY && state.getFluidState().is(FluidTags.WATER)) {
						return false;
					}
				}
			}
		}

		int supportY = Mth.floor(y - 0.05D);
		boolean thinSupport = (y - supportY) <= THIN_SUPPORT_THRESHOLD;
		int footCenterX = Mth.floor(x);
		int footCenterZ = Mth.floor(z);

		for (int bx = minX; bx <= maxX; bx++) {
			for (int bz = minZ; bz <= maxZ; bz++) {
				cursor.set(bx, supportY, bz);
				BlockState supportState = level.getBlockState(cursor);
				if (isHazardous(supportState)) {
					return false;
				}
				if (thinSupport && bx == footCenterX && bz == footCenterZ) {
					cursor.set(bx, supportY - 1, bz);
					BlockState underSupport = level.getBlockState(cursor);
					if (underSupport.isAir() || isHazardous(underSupport)) {
						return false;
					}
				}
			}
		}

		return true;
	}

	private static void ensureChunksLoaded(ServerLevel level, AABB box) {
		int minCx = SectionPos.blockToSectionCoord(box.minX - 0.1D);
		int maxCx = SectionPos.blockToSectionCoord(box.maxX + 0.1D);
		int minCz = SectionPos.blockToSectionCoord(box.minZ - 0.1D);
		int maxCz = SectionPos.blockToSectionCoord(box.maxZ + 0.1D);
		for (int cx = minCx; cx <= maxCx; cx++) {
			for (int cz = minCz; cz <= maxCz; cz++) {
				level.getChunk(cx, cz);
			}
		}
	}

	private static boolean isHazardous(BlockState state) {
		if (state.isAir()) {
			return false;
		}
		if (state.getFluidState().is(FluidTags.LAVA)) {
			return true;
		}
		return state.is(Blocks.LAVA)
			|| state.is(Blocks.LAVA_CAULDRON)
			|| state.is(BlockTags.FIRE)
			|| state.is(BlockTags.CAMPFIRES)
			|| state.is(BlockTags.PORTALS)
			|| state.is(Blocks.CACTUS)
			|| state.is(Blocks.MAGMA_BLOCK)
			|| state.is(Blocks.POWDER_SNOW)
			|| state.is(Blocks.POWDER_SNOW_CAULDRON)
			|| state.is(Blocks.SWEET_BERRY_BUSH)
			|| state.is(Blocks.WITHER_ROSE)
			|| state.is(Blocks.POINTED_DRIPSTONE)
			|| state.is(Blocks.COBWEB);
	}
}
