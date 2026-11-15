package me.ogsammaenr.ogsky.core.internal.world;

import me.ogsammaenr.ogsky.core.api.island.IslandService;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import me.ogsammaenr.ogsky.core.api.island.RelativeSpawn;
import me.ogsammaenr.ogsky.core.internal.flag.BuiltinFlags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.BlockUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.feature.EndPlatformFeature;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Enforces strict 1:1 inter-dimension portal routing inside {@code ogsky:overworld}, {@code ogsky:nether},
 * and {@code ogsky:end} bounded to the originating {@link IslandView}.
 */
public final class PortalRouter {
	private static final int NETHER_PORTAL_FRAME_INSET = 2;
	private static final int END_PLATFORM_INSET = 2;
	private static final ThreadLocal<@Nullable WorldBorder> ACTIVE_PORTAL_BORDER = new ThreadLocal<>();
	private static volatile @Nullable PortalRouter activeInstance;

	private final IslandService islandService;
	private volatile LazyDimensionHandler lazyDimensionHandler = LazyDimensionHandler.NOOP;
	private volatile PortalProtectionChecker protectionChecker = PortalProtectionChecker.DEFAULT;

	public PortalRouter(IslandService islandService) {
		this.islandService = Objects.requireNonNull(islandService, "islandService");
	}

	public static void bind(@Nullable PortalRouter router) {
		activeInstance = router;
	}

	public static @Nullable PortalRouter active() {
		return activeInstance;
	}

	public static @Nullable WorldBorder activePortalWorldBorder() {
		return ACTIVE_PORTAL_BORDER.get();
	}

	public void setLazyDimensionHandler(LazyDimensionHandler handler) {
		this.lazyDimensionHandler = Objects.requireNonNull(handler, "handler");
	}

	public void setProtectionChecker(PortalProtectionChecker checker) {
		this.protectionChecker = Objects.requireNonNull(checker, "checker");
	}

	public static @Nullable ResourceKey<Level> resolveNetherCounterpart(ResourceKey<Level> currentDimension) {
		Objects.requireNonNull(currentDimension, "currentDimension");
		if (currentDimension.equals(OgskyDimensions.OVERWORLD)) {
			return OgskyDimensions.NETHER;
		}
		if (currentDimension.equals(OgskyDimensions.NETHER)) {
			return OgskyDimensions.OVERWORLD;
		}
		return null;
	}

	public static @Nullable ResourceKey<Level> resolveEndCounterpart(ResourceKey<Level> currentDimension) {
		Objects.requireNonNull(currentDimension, "currentDimension");
		if (currentDimension.equals(OgskyDimensions.OVERWORLD)) {
			return OgskyDimensions.END;
		}
		if (currentDimension.equals(OgskyDimensions.END)) {
			return OgskyDimensions.OVERWORLD;
		}
		return null;
	}

	public static BlockPos clampSearchTarget(
		IslandView island,
		int x,
		int y,
		int z,
		int minY,
		int maxY
	) {
		int clampedX = Math.clamp(x, island.minX(), island.maxX());
		int clampedZ = Math.clamp(z, island.minZ(), island.maxZ());
		int safeMinY = Math.min(minY + 1, maxY);
		int clampedY = Math.clamp(y, safeMinY, maxY);
		return new BlockPos(clampedX, clampedY, clampedZ);
	}

	public static BlockPos clampInsetOrigin(
		IslandView island,
		int x,
		int y,
		int z,
		int inset,
		int minY,
		int maxY
	) {
		int clampedX;
		int clampedZ;
		if (island.halfSize() > inset) {
			clampedX = Math.clamp(x, island.minX() + inset, island.maxX() - inset);
			clampedZ = Math.clamp(z, island.minZ() + inset, island.maxZ() - inset);
		} else {
			clampedX = island.centerX();
			clampedZ = island.centerZ();
		}
		int clampedY = Math.clamp(y, Math.min(minY, maxY), Math.max(minY, maxY));
		return new BlockPos(clampedX, clampedY, clampedZ);
	}

	public @Nullable TeleportTransition routeNetherPortal(
		ServerLevel currentLevel,
		Entity entity,
		BlockPos portalEntryPos
	) {
		ResourceKey<Level> targetDimension = resolveNetherCounterpart(currentLevel.dimension());
		if (targetDimension == null) {
			return null;
		}
		IslandView island = resolveAuthorizedIsland(currentLevel.dimension(), entity, portalEntryPos);
		if (island == null) {
			return null;
		}

		MinecraftServer server = currentLevel.getServer();
		ServerLevel targetLevel = server.getLevel(targetDimension);
		if (targetLevel == null) {
			return null;
		}

		int minSearchY = targetLevel.getMinY() + 1;
		int maxSearchY = Math.max(minSearchY, targetLevel.getMaxY() - 5);
		BlockPos searchTarget = clampSearchTarget(
			island,
			Mth.floor(entity.getX()),
			Mth.floor(entity.getY()),
			Mth.floor(entity.getZ()),
			minSearchY,
			maxSearchY
		);

		this.lazyDimensionHandler.prepareDimension(server, island, targetDimension, targetLevel, searchTarget);

		WorldBorder islandBorder = new IslandPortalWorldBorder(targetLevel.getWorldBorder(), island);
		boolean toNether = targetDimension.equals(OgskyDimensions.NETHER);
		Optional<BlockPos> existingPortal = targetLevel.getPortalForcer().findClosestPortalPosition(searchTarget, toNether, islandBorder);

		BlockUtil.FoundRectangle exitPortal;
		TeleportTransition.PostTeleportTransition postTransition;
		if (existingPortal.isPresent()) {
			BlockPos pos = existingPortal.get();
			BlockState portalState = targetLevel.getBlockState(pos);
			exitPortal = BlockUtil.getLargestRectangleAround(
				pos,
				portalState.getValue(BlockStateProperties.HORIZONTAL_AXIS),
				21,
				Direction.Axis.Y,
				21,
				blockPos -> targetLevel.getBlockState(blockPos) == portalState
			);
			postTransition = TeleportTransition.PLAY_PORTAL_SOUND.then(targetEntity -> targetEntity.placePortalTicket(pos));
		} else {
			if (entity.isSpectator()) {
				return null;
			}
			int maxPlaceableY = Math.min(targetLevel.getMaxY(), targetLevel.getMinY() + targetLevel.getLogicalHeight() - 1);
			int minCreateY = Math.max(targetLevel.getMinY() + 1, 70);
			int maxCreateY = Math.max(minCreateY, maxPlaceableY - 9);
			BlockPos createOrigin = clampInsetOrigin(
				island,
				searchTarget.getX(),
				searchTarget.getY(),
				searchTarget.getZ(),
				NETHER_PORTAL_FRAME_INSET,
				minCreateY,
				maxCreateY
			);
			Direction.Axis sourceAxis = currentLevel.getBlockState(portalEntryPos)
				.getOptionalValue(NetherPortalBlock.AXIS)
				.orElse(Direction.Axis.X);

			Optional<BlockUtil.FoundRectangle> createdPortal;
			ACTIVE_PORTAL_BORDER.set(islandBorder);
			try {
				createdPortal = targetLevel.getPortalForcer().createPortal(createOrigin, sourceAxis);
			} finally {
				ACTIVE_PORTAL_BORDER.remove();
			}
			if (createdPortal.isEmpty()) {
				return null;
			}
			exitPortal = createdPortal.get();
			postTransition = TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET);
		}

		return createNetherDimensionTransition(currentLevel, entity, portalEntryPos, exitPortal, targetLevel, postTransition);
	}

	public @Nullable TeleportTransition routeEndPortal(
		ServerLevel currentLevel,
		Entity entity,
		BlockPos portalEntryPos
	) {
		ResourceKey<Level> targetDimension = resolveEndCounterpart(currentLevel.dimension());
		if (targetDimension == null) {
			return null;
		}
		IslandView island = resolveAuthorizedIsland(currentLevel.dimension(), entity, portalEntryPos);
		if (island == null) {
			return null;
		}

		MinecraftServer server = currentLevel.getServer();
		ServerLevel targetLevel = server.getLevel(targetDimension);
		if (targetLevel == null) {
			return null;
		}

		boolean fromEnd = currentLevel.dimension().equals(OgskyDimensions.END);
		if (!fromEnd) {
			RelativeSpawn initialEndSpawn = island.endSpawn();
			BlockPos initialTarget = clampInsetOrigin(
				island,
				initialEndSpawn.blockX(island.centerX()),
				initialEndSpawn.blockY(),
				initialEndSpawn.blockZ(island.centerZ()),
				END_PLATFORM_INSET,
				targetLevel.getMinY() + 2,
				targetLevel.getMaxY() - 3
			);

			this.lazyDimensionHandler.prepareDimension(server, island, OgskyDimensions.END, targetLevel, initialTarget);

			RelativeSpawn endSpawn = island.endSpawn();
			BlockPos targetBlock = clampInsetOrigin(
				island,
				endSpawn.blockX(island.centerX()),
				endSpawn.blockY(),
				endSpawn.blockZ(island.centerZ()),
				END_PLATFORM_INSET,
				targetLevel.getMinY() + 2,
				targetLevel.getMaxY() - 3
			);

			if (!targetLevel.getBlockState(targetBlock.below()).isSolid()) {
				EndPlatformFeature.createEndPlatform(targetLevel, targetBlock, true);
			}

			Vec3 spawnPos = Vec3.atBottomCenterOf(targetBlock);
			float yaw = endSpawn.yaw() == 0.0F ? Direction.WEST.toYRot() : endSpawn.yaw();
			return new TeleportTransition(
				targetLevel,
				spawnPos,
				Vec3.ZERO,
				yaw,
				endSpawn.pitch(),
				Set.of(),
				TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET)
			);
		}

		RelativeSpawn overworldSpawn = island.overworldSpawn();
		BlockPos targetBlock = clampSearchTarget(
			island,
			overworldSpawn.blockX(island.centerX()),
			overworldSpawn.blockY(),
			overworldSpawn.blockZ(island.centerZ()),
			targetLevel.getMinY() + 1,
			targetLevel.getMaxY() - 3
		);
		Vec3 spawnPos = Vec3.atBottomCenterOf(targetBlock);
		return new TeleportTransition(
			targetLevel,
			spawnPos,
			Vec3.ZERO,
			overworldSpawn.yaw(),
			overworldSpawn.pitch(),
			Set.of(),
			TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET)
		);
	}

	private @Nullable IslandView resolveAuthorizedIsland(
		ResourceKey<Level> currentDimension,
		Entity entity,
		BlockPos portalEntryPos
	) {
		Optional<IslandView> maybeIsland = this.islandService.findAt(currentDimension, portalEntryPos.getX(), portalEntryPos.getZ());
		if (maybeIsland.isEmpty()) {
			return null;
		}
		IslandView island = maybeIsland.get();
		if (island.isDeleting()) {
			return null;
		}
		if (entity instanceof Player player && !this.protectionChecker.canUsePortal(island, player, BuiltinFlags.PORTAL_USE)) {
			return null;
		}
		return island;
	}

	private static TeleportTransition createNetherDimensionTransition(
		ServerLevel sourceLevel,
		Entity entity,
		BlockPos portalEntryPos,
		BlockUtil.FoundRectangle exitPortal,
		ServerLevel targetLevel,
		TeleportTransition.PostTeleportTransition postTransition
	) {
		BlockState entryState = sourceLevel.getBlockState(portalEntryPos);
		Direction.Axis sourceAxis;
		Vec3 offset;
		if (entryState.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)) {
			sourceAxis = entryState.getValue(BlockStateProperties.HORIZONTAL_AXIS);
			BlockUtil.FoundRectangle portalArea = BlockUtil.getLargestRectangleAround(
				portalEntryPos,
				sourceAxis,
				21,
				Direction.Axis.Y,
				21,
				pos -> sourceLevel.getBlockState(pos) == entryState
			);
			offset = entity.getRelativePortalPosition(sourceAxis, portalArea);
		} else {
			sourceAxis = Direction.Axis.X;
			offset = new Vec3(0.5D, 0.0D, 0.0D);
		}

		BlockPos bottomLeft = exitPortal.minCorner;
		BlockState targetState = targetLevel.getBlockState(bottomLeft);
		Direction.Axis targetAxis = targetState.getOptionalValue(BlockStateProperties.HORIZONTAL_AXIS).orElse(Direction.Axis.X);
		double width = exitPortal.axis1Size;
		double height = exitPortal.axis2Size;
		EntityDimensions dimensions = entity.getDimensions(entity.getPose());
		int outputRotation = sourceAxis == targetAxis ? 0 : 90;
		double offsetRight = dimensions.width() / 2.0D + (width - dimensions.width()) * offset.x();
		double offsetUp = (height - dimensions.height()) * offset.y();
		double offsetForward = 0.5D + offset.z();
		boolean xAligned = targetAxis == Direction.Axis.X;
		Vec3 targetPos = new Vec3(
			bottomLeft.getX() + (xAligned ? offsetRight : offsetForward),
			bottomLeft.getY() + offsetUp,
			bottomLeft.getZ() + (xAligned ? offsetForward : offsetRight)
		);
		Vec3 collisionFreePos = PortalShape.findCollisionFreePosition(targetPos, targetLevel, entity, dimensions);
		return new TeleportTransition(
			targetLevel,
			collisionFreePos,
			Vec3.ZERO,
			outputRotation,
			0.0F,
			Relative.union(Relative.DELTA, Relative.ROTATION),
			postTransition
		);
	}

	@FunctionalInterface
	public interface LazyDimensionHandler {
		LazyDimensionHandler NOOP = (server, island, targetDimension, targetLevel, targetOrigin) -> {};

		void prepareDimension(
			MinecraftServer server,
			IslandView island,
			ResourceKey<Level> targetDimension,
			ServerLevel targetLevel,
			BlockPos targetOrigin
		);
	}

	@FunctionalInterface
	public interface PortalProtectionChecker {
		PortalProtectionChecker DEFAULT = (island, player, flagId) ->
			!island.isBanned(player.getUUID())
				&& island.roleOf(player.getUUID()) >= island.protectionRank(flagId);

		boolean canUsePortal(IslandView island, Player player, Identifier flagId);
	}

	private static final class IslandPortalWorldBorder extends WorldBorder {
		private final double minX;
		private final double maxX;
		private final double minZ;
		private final double maxZ;

		private IslandPortalWorldBorder(WorldBorder delegate, IslandView island) {
			double left = Math.max(delegate.getMinX(), island.minX());
			double right = Math.min(delegate.getMaxX(), island.maxX() + 1.0D);
			double top = Math.max(delegate.getMinZ(), island.minZ());
			double bottom = Math.min(delegate.getMaxZ(), island.maxZ() + 1.0D);
			if (left > right) {
				this.minX = island.centerX();
				this.maxX = island.centerX() + 1.0D;
			} else {
				this.minX = left;
				this.maxX = right;
			}
			if (top > bottom) {
				this.minZ = island.centerZ();
				this.maxZ = island.centerZ() + 1.0D;
			} else {
				this.minZ = top;
				this.maxZ = bottom;
			}
		}

		@Override
		public double getMinX(float deltaPartialTick) {
			return this.minX;
		}

		@Override
		public double getMaxX(float deltaPartialTick) {
			return this.maxX;
		}

		@Override
		public double getMinZ(float deltaPartialTick) {
			return this.minZ;
		}

		@Override
		public double getMaxZ(float deltaPartialTick) {
			return this.maxZ;
		}
	}
}
