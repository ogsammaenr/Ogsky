package me.ogsammaenr.ogsky.core.internal.protection;

import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import me.ogsammaenr.ogsky.core.internal.flag.BuiltinFlags;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandService;
import me.ogsammaenr.ogsky.core.internal.island.Island;
import me.ogsammaenr.ogsky.core.internal.permission.LuckPermsPermissionBridge;
import me.ogsammaenr.ogsky.core.internal.world.OgskyDimensions;
import me.ogsammaenr.ogsky.core.internal.world.PortalRouter;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.DetectorRailBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core Skyblock protection evaluator bridging {@link CoreIslandService}, {@link LuckPermsPermissionBridge},
 * Fabric callbacks, and MC 26.3 Mixin guards.
 */
public final class ProtectionResolver implements PortalRouter.PortalProtectionChecker {
	private static final long DENY_MESSAGE_COOLDOWN_MS = 1000L;
	private static volatile @Nullable ProtectionResolver activeInstance;

	private final CoreIslandService islandService;
	private final LuckPermsPermissionBridge permissionBridge;
	private final Map<UUID, Long> lastDenyMessageMillis = new ConcurrentHashMap<>();

	public ProtectionResolver(CoreIslandService islandService, LuckPermsPermissionBridge permissionBridge) {
		this.islandService = Objects.requireNonNull(islandService, "islandService");
		this.permissionBridge = Objects.requireNonNull(permissionBridge, "permissionBridge");
	}

	public static void bind(@Nullable ProtectionResolver resolver) {
		activeInstance = resolver;
	}

	public static @Nullable ProtectionResolver active() {
		return activeInstance;
	}

	public boolean isProtectedDimension(Level level) {
		return !level.isClientSide() && OgskyDimensions.isSkyblockDimension(level.dimension());
	}

	public @Nullable IslandView islandAt(Level level, BlockPos pos) {
		if (!OgskyDimensions.isSkyblockDimension(level.dimension())) {
			return null;
		}
		return islandService.grid().findAt(pos.getX(), pos.getZ());
	}

	public boolean isPlayerAllowed(Player player, Level level, BlockPos pos, Identifier flagId) {
		if (!isProtectedDimension(level)) {
			return true;
		}
		boolean bypass = permissionBridge.hasBypass(player, flagId);
		return islandService.isProtectionAllowed(
			level.dimension(),
			pos.getX(),
			pos.getZ(),
			player.getUUID(),
			flagId,
			bypass
		);
	}

	public boolean isPlayerAllowedStrict(Player player, Level level, BlockPos pos, Identifier firstFlag, Identifier secondFlag) {
		return isPlayerAllowed(player, level, pos, firstFlag) && isPlayerAllowed(player, level, pos, secondFlag);
	}

	public boolean checkAndNotify(Player player, Level level, BlockPos pos, Identifier flagId) {
		if (isPlayerAllowed(player, level, pos, flagId)) {
			return true;
		}
		notifyDenied(player, "Island protection (" + flagId.getPath() + ") prevents this action.");
		return false;
	}

	public boolean checkStrictAndNotify(Player player, Level level, BlockPos pos, Identifier firstFlag, Identifier secondFlag) {
		if (!isPlayerAllowed(player, level, pos, firstFlag)) {
			notifyDenied(player, "Island protection (" + firstFlag.getPath() + ") prevents this action.");
			return false;
		}
		if (!isPlayerAllowed(player, level, pos, secondFlag)) {
			notifyDenied(player, "Island protection (" + secondFlag.getPath() + ") prevents this action.");
			return false;
		}
		return true;
	}

	@Override
	public boolean canUsePortal(IslandView island, Player player, Identifier flagId) {
		if (permissionBridge.hasBypass(player, flagId)) {
			return true;
		}
		return !island.isBanned(player.getUUID())
			&& island.roleOf(player.getUUID()) >= island.protectionRank(flagId);
	}

	public boolean canEnterIsland(IslandView island, Player player) {
		if (hasEntryBypass(player)) {
			return true;
		}
		if (island.isBanned(player.getUUID())) {
			return false;
		}
		if (island.isSettingEnabled(BuiltinFlags.ISLAND_LOCK) && island.roleOf(player.getUUID()) < IslandRole.COOP) {
			return false;
		}
		return true;
	}

	public boolean hasEntryBypass(Player player) {
		return permissionBridge.hasBypass(player, BuiltinFlags.ISLAND_LOCK);
	}

	public boolean isPvpAllowed(ServerPlayer attacker, ServerPlayer victim) {
		if (attacker.getUUID().equals(victim.getUUID())) {
			return true;
		}
		ServerLevel level = victim.level();
		if (!OgskyDimensions.isSkyblockDimension(level.dimension())) {
			return true;
		}
		Identifier pvpFlag = pvpFlagFor(level.dimension());
		if (permissionBridge.hasBypass(attacker, pvpFlag)) {
			return true;
		}
		return islandService.isSettingEnabledAt(level.dimension(), victim.getBlockX(), victim.getBlockZ(), pvpFlag)
			&& islandService.isSettingEnabledAt(attacker.level().dimension(), attacker.getBlockX(), attacker.getBlockZ(), pvpFlag);
	}

	public boolean canTrampleFarmland(ServerLevel level, BlockPos pos, @Nullable Entity entity) {
		if (!OgskyDimensions.isSkyblockDimension(level.dimension())) {
			return true;
		}
		if (entity instanceof Player player) {
			return isPlayerAllowed(player, level, pos, BuiltinFlags.CROP_TRAMPLE);
		}
		Island island = islandService.grid().findAt(pos.getX(), pos.getZ());
		if (island == null) {
			return islandService.isProtectionAllowed(level.dimension(), pos.getX(), pos.getZ(), new UUID(0L, 0L), BuiltinFlags.CROP_TRAMPLE, false);
		}
		return island.protectionRankBySlot(flagSlot(BuiltinFlags.CROP_TRAMPLE)) == IslandRole.VISITOR;
	}

	public boolean isSettingEnabledAt(ServerLevel level, BlockPos pos, Identifier settingFlagId) {
		if (!OgskyDimensions.isSkyblockDimension(level.dimension())) {
			return true;
		}
		return islandService.isSettingEnabledAt(level.dimension(), pos.getX(), pos.getZ(), settingFlagId);
	}

	public boolean isNaturalSpawnAllowed(ServerLevel level, BlockPos pos, MobCategory category) {
		if (!OgskyDimensions.isSkyblockDimension(level.dimension())) {
			return true;
		}
		if (category == MobCategory.MONSTER) {
			return islandService.isSettingEnabledAt(level.dimension(), pos.getX(), pos.getZ(), BuiltinFlags.MONSTER_SPAWN);
		}
		if (category != MobCategory.MISC) {
			return islandService.isSettingEnabledAt(level.dimension(), pos.getX(), pos.getZ(), BuiltinFlags.ANIMAL_SPAWN);
		}
		return true;
	}

	public boolean canExplosionBreakBlock(ServerExplosion explosion, BlockPos pos) {
		ServerLevel level = explosion.level();
		if (!OgskyDimensions.isSkyblockDimension(level.dimension())) {
			return true;
		}
		Entity sourceEntity = resolveExplosionSource(explosion);
		if (sourceEntity instanceof Creeper) {
			if (!islandService.isWorldSettingEnabled(BuiltinFlags.CREEPER_GRIEFING)) {
				return false;
			}
		} else if (sourceEntity instanceof PrimedTnt || sourceEntity instanceof MinecartTNT) {
			if (!islandService.isWorldSettingEnabled(BuiltinFlags.TNT_DAMAGE)) {
				return false;
			}
		} else if (sourceEntity instanceof WitherBoss || sourceEntity instanceof WitherSkull || sourceEntity instanceof EnderDragon) {
			if (!islandService.isWorldSettingEnabled(BuiltinFlags.WITHER_DAMAGE)) {
				return false;
			}
		}
		if (!islandService.isWorldSettingEnabled(BuiltinFlags.CHEST_DAMAGE) && isProtectedExplosionContainer(level, pos)) {
			return false;
		}
		return true;
	}

	public boolean shouldExplosionHurtEntities(ServerExplosion explosion) {
		ServerLevel level = explosion.level();
		if (!OgskyDimensions.isSkyblockDimension(level.dimension())) {
			return true;
		}
		Entity sourceEntity = resolveExplosionSource(explosion);
		if (sourceEntity instanceof Creeper) {
			return islandService.isWorldSettingEnabled(BuiltinFlags.CREEPER_DAMAGE);
		}
		return true;
	}

	public boolean canLiquidSpread(LevelAccessor levelAccessor, BlockPos targetPos, Direction direction) {
		if (!(levelAccessor instanceof ServerLevel level) || !OgskyDimensions.isSkyblockDimension(level.dimension())) {
			return true;
		}
		if (islandService.isWorldSettingEnabled(BuiltinFlags.LIQUID_FLOW_OUTSIDE_BOUNDS)) {
			return true;
		}
		BlockPos sourcePos = targetPos.relative(direction.getOpposite());
		Island sourceIsland = islandService.grid().findAt(sourcePos.getX(), sourcePos.getZ());
		Island targetIsland = islandService.grid().findAt(targetPos.getX(), targetPos.getZ());
		return sourceIsland == targetIsland;
	}

	public boolean canPistonMove(Level level, BlockPos pistonPos, Direction direction, boolean extending) {
		if (!(level instanceof ServerLevel serverLevel) || !OgskyDimensions.isSkyblockDimension(serverLevel.dimension())) {
			return true;
		}
		if (islandService.isWorldSettingEnabled(BuiltinFlags.PISTON_OUTSIDE_BOUNDS)) {
			return true;
		}
		PistonStructureResolver resolver = new PistonStructureResolver(level, pistonPos, direction, extending);
		if (!resolver.resolve()) {
			return true;
		}
		Island originIsland = islandService.grid().findAt(pistonPos.getX(), pistonPos.getZ());
		BlockPos armPos = pistonPos.relative(direction);
		if (islandService.grid().findAt(armPos.getX(), armPos.getZ()) != originIsland) {
			return false;
		}
		Direction pushDirection = extending ? direction : direction.getOpposite();
		for (BlockPos moved : resolver.getToPush()) {
			if (islandService.grid().findAt(moved.getX(), moved.getZ()) != originIsland) {
				return false;
			}
			BlockPos dest = moved.relative(pushDirection);
			if (islandService.grid().findAt(dest.getX(), dest.getZ()) != originIsland) {
				return false;
			}
		}
		for (BlockPos destroyed : resolver.getToDestroy()) {
			if (islandService.grid().findAt(destroyed.getX(), destroyed.getZ()) != originIsland) {
				return false;
			}
		}
		return true;
	}

	public boolean isRedstoneTickAllowed(ServerLevel level, BlockPos pos, Block block) {
		if (!OgskyDimensions.isSkyblockDimension(level.dimension()) || !isRedstoneTickBlock(block)) {
			return true;
		}
		Island island = islandService.grid().findAt(pos.getX(), pos.getZ());
		if (island == null) {
			return true;
		}
		int offlineSlot = flagSlot(BuiltinFlags.OFFLINE_REDSTONE);
		if (island.isSettingEnabledBySlot(offlineSlot)) {
			return true;
		}
		MinecraftServer server = level.getServer();
		return island.hasOnlineMember(uuid -> server.getPlayerList().getPlayer(uuid) != null);
	}

	public void notifyDenied(Player player, String message) {
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		long now = System.currentTimeMillis();
		Long previous = lastDenyMessageMillis.get(serverPlayer.getUUID());
		if (previous != null && now - previous < DENY_MESSAGE_COOLDOWN_MS) {
			return;
		}
		lastDenyMessageMillis.put(serverPlayer.getUUID(), now);
		serverPlayer.sendSystemMessage(Component.literal("[Ogsky] " + message).withStyle(ChatFormatting.RED));
	}

	public void clearPlayerState(UUID playerUuid) {
		lastDenyMessageMillis.remove(playerUuid);
		permissionBridge.clearSessionBypass(playerUuid);
	}

	public void clearAllState() {
		lastDenyMessageMillis.clear();
		permissionBridge.clearAllSessionBypass();
	}

	public static Identifier pvpFlagFor(ResourceKey<Level> dimension) {
		if (dimension.equals(OgskyDimensions.NETHER)) {
			return BuiltinFlags.PVP_NETHER;
		}
		if (dimension.equals(OgskyDimensions.END)) {
			return BuiltinFlags.PVP_END;
		}
		return BuiltinFlags.PVP_OVERWORLD;
	}

	private int flagSlot(Identifier flagId) {
		return islandService.flagSlot(flagId);
	}

	private static @Nullable Entity resolveExplosionSource(ServerExplosion explosion) {
		Entity direct = explosion.getDirectSourceEntity();
		if (direct != null) {
			return direct;
		}
		return explosion.getDamageSource().getEntity();
	}

	private static boolean isProtectedExplosionContainer(ServerLevel level, BlockPos pos) {
		if (level.getBlockEntity(pos) instanceof Container) {
			return true;
		}
		BlockState state = level.getBlockState(pos);
		Block block = state.getBlock();
		return block instanceof ChestBlock
			|| block instanceof BarrelBlock
			|| block instanceof ShulkerBoxBlock
			|| block instanceof HopperBlock
			|| block instanceof DispenserBlock
			|| block instanceof AbstractFurnaceBlock
			|| block instanceof CrafterBlock;
	}

	private static boolean isRedstoneTickBlock(Block block) {
		return block instanceof DiodeBlock
			|| block instanceof RedstoneWireBlock
			|| block instanceof RedstoneTorchBlock
			|| block instanceof ObserverBlock
			|| block instanceof PistonBaseBlock
			|| block instanceof MovingPistonBlock
			|| block instanceof DispenserBlock
			|| block instanceof CrafterBlock
			|| block instanceof HopperBlock
			|| block instanceof PoweredRailBlock
			|| block instanceof DetectorRailBlock
			|| block instanceof CopperBulbBlock;
	}
}
