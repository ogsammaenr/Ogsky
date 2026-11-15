package me.ogsammaenr.ogsky.core.internal.protection;

import me.ogsammaenr.ogsky.core.api.event.IslandEnterEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandExitEvent;
import me.ogsammaenr.ogsky.core.api.event.OgskyEventBus;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfig;
import me.ogsammaenr.ogsky.core.internal.flag.BuiltinFlags;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandService;
import me.ogsammaenr.ogsky.core.internal.island.Island;
import me.ogsammaenr.ogsky.core.internal.island.IslandLifecycleService;
import me.ogsammaenr.ogsky.core.internal.island.OperationResult;
import me.ogsammaenr.ogsky.core.internal.world.OgskyDimensions;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.fish.AbstractFish;
import net.minecraft.world.entity.animal.fish.WaterAnimal;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.cow.MushroomCow;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.animal.golem.SnowGolem;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.boat.ChestBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.item.ArmorStandItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.BrushItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.EndCrystalItem;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.HangingEntityItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.LeadItem;
import net.minecraft.world.item.MinecartItem;
import net.minecraft.world.item.NameTagItem;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.SolidBucketItem;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BeaconBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DragonEggBlock;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.GrindstoneBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LoomBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SmithingTableBlock;
import net.minecraft.world.level.block.SpawnerBlock;
import net.minecraft.world.level.block.StonecutterBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.TrialSpawnerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers Fabric player/entity/world protection callbacks, boundary enter/exit tracking,
 * lock &amp; ban eviction, and void-rescue handling.
 */
public final class ProtectionListeners {
	private final CoreConfig config;
	private final CoreIslandService islandService;
	private final IslandLifecycleService lifecycleService;
	private final ProtectionResolver protection;
	private final OgskyEventBus eventBus;
	private final Map<UUID, UUID> lastPlayerIsland = new ConcurrentHashMap<>();

	public ProtectionListeners(
		CoreConfig config,
		CoreIslandService islandService,
		IslandLifecycleService lifecycleService,
		ProtectionResolver protection,
		OgskyEventBus eventBus
	) {
		this.config = Objects.requireNonNull(config, "config");
		this.islandService = Objects.requireNonNull(islandService, "islandService");
		this.lifecycleService = Objects.requireNonNull(lifecycleService, "lifecycleService");
		this.protection = Objects.requireNonNull(protection, "protection");
		this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
	}

	public void registerAll() {
		PlayerBlockBreakEvents.BEFORE.register(this::onBeforeBlockBreak);
		AttackBlockCallback.EVENT.register(this::onAttackBlock);
		UseBlockCallback.EVENT.register(this::onUseBlock);
		UseEntityCallback.EVENT.register(this::onUseEntity);
		AttackEntityCallback.EVENT.register(this::onAttackEntity);
		ServerLivingEntityEvents.ALLOW_DAMAGE.register(this::onAllowDamage);
		UseItemCallback.EVENT.register(this::onUseItem);
		ServerTickEvents.END_SERVER_TICK.register(this::tickPlayers);
	}

	public void onPlayerDisconnect(UUID playerUuid) {
		lastPlayerIsland.remove(playerUuid);
		protection.clearPlayerState(playerUuid);
	}

	public void clearTracking() {
		lastPlayerIsland.clear();
		protection.clearAllState();
	}

	private boolean onBeforeBlockBreak(
		Level level,
		Player player,
		BlockPos pos,
		BlockState state,
		net.minecraft.world.level.block.entity.@Nullable BlockEntity blockEntity
	) {
		if (!protection.isProtectedDimension(level)) {
			return true;
		}
		Block block = state.getBlock();
		if (block instanceof HopperBlock) {
			return protection.checkStrictAndNotify(player, level, pos, BuiltinFlags.BREAK_BLOCKS, BuiltinFlags.BREAK_HOPPERS);
		}
		if (block instanceof SpawnerBlock || block instanceof TrialSpawnerBlock) {
			return protection.checkStrictAndNotify(player, level, pos, BuiltinFlags.BREAK_BLOCKS, BuiltinFlags.BREAK_SPAWNERS);
		}
		if (isHarvestableCrop(block)) {
			return protection.checkAndNotify(player, level, pos, BuiltinFlags.HARVEST);
		}
		return protection.checkAndNotify(player, level, pos, BuiltinFlags.BREAK_BLOCKS);
	}

	private InteractionResult onAttackBlock(
		Player player,
		Level level,
		InteractionHand hand,
		BlockPos pos,
		net.minecraft.core.Direction direction
	) {
		if (!protection.isProtectedDimension(level)) {
			return InteractionResult.PASS;
		}
		BlockState state = level.getBlockState(pos);
		Block block = state.getBlock();
		if (block instanceof NoteBlock) {
			return protection.checkAndNotify(player, level, pos, BuiltinFlags.REDSTONE)
				? InteractionResult.PASS
				: InteractionResult.FAIL;
		}
		if (block instanceof DragonEggBlock) {
			return protection.checkAndNotify(player, level, pos, BuiltinFlags.BREAK_BLOCKS)
				? InteractionResult.PASS
				: InteractionResult.FAIL;
		}
		BlockPos frontPos = pos.relative(direction);
		if (level.getBlockState(frontPos).is(BlockTags.FIRE)) {
			return protection.checkAndNotify(player, level, frontPos, BuiltinFlags.BREAK_BLOCKS)
				? InteractionResult.PASS
				: InteractionResult.FAIL;
		}
		return InteractionResult.PASS;
	}

	private InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hitResult) {
		if (!protection.isProtectedDimension(level)) {
			return InteractionResult.PASS;
		}
		BlockPos pos = hitResult.getBlockPos();
		BlockState state = level.getBlockState(pos);
		ItemStack held = player.getItemInHand(hand);

		if (state.getBlock() instanceof TntBlock && (held.getItem() instanceof FlintAndSteelItem || held.getItem() instanceof FireChargeItem)) {
			return protection.checkAndNotify(player, level, pos, BuiltinFlags.FLINT_AND_STEEL)
				? InteractionResult.PASS
				: InteractionResult.FAIL;
		}

		Identifier blockFlag = classifyUseBlockFlag(level, pos, state);
		if (blockFlag != null && !player.isSecondaryUseActive()) {
			return protection.checkAndNotify(player, level, pos, blockFlag)
				? InteractionResult.PASS
				: InteractionResult.FAIL;
		}

		Item heldItem = held.getItem();
		BlockPos targetPos = state.canBeReplaced() ? pos : pos.relative(hitResult.getDirection());

		if (heldItem instanceof BucketItem || heldItem instanceof SolidBucketItem || held.is(Items.MILK_BUCKET)) {
			boolean ok = protection.checkAndNotify(player, level, pos, BuiltinFlags.BUCKET)
				&& protection.checkAndNotify(player, level, targetPos, BuiltinFlags.BUCKET);
			return ok ? InteractionResult.PASS : InteractionResult.FAIL;
		}
		if (heldItem instanceof FlintAndSteelItem || heldItem instanceof FireChargeItem) {
			return protection.checkAndNotify(player, level, targetPos, BuiltinFlags.FLINT_AND_STEEL)
				? InteractionResult.PASS
				: InteractionResult.FAIL;
		}
		if (heldItem instanceof ArmorStandItem) {
			return protection.checkAndNotify(player, level, targetPos, BuiltinFlags.ARMOR_STAND)
				? InteractionResult.PASS
				: InteractionResult.FAIL;
		}
		if (heldItem instanceof HangingEntityItem) {
			return protection.checkAndNotify(player, level, targetPos, BuiltinFlags.ITEM_FRAME)
				? InteractionResult.PASS
				: InteractionResult.FAIL;
		}
		if (heldItem instanceof EndCrystalItem) {
			if (level.dimension().equals(OgskyDimensions.END)
				&& state.is(Blocks.BEDROCK)
				&& !islandService.isWorldSettingEnabled(BuiltinFlags.END_DRAGON_RESPAWN)) {
				protection.notifyDenied(player, "Ender Dragon respawning is disabled in ogsky:end.");
				return InteractionResult.FAIL;
			}
			return protection.checkAndNotify(player, level, targetPos, BuiltinFlags.PLACE_BLOCKS)
				? InteractionResult.PASS
				: InteractionResult.FAIL;
		}
		if (heldItem instanceof BlockItem
			|| heldItem instanceof SpawnEggItem
			|| heldItem instanceof BoatItem
			|| heldItem instanceof MinecartItem
			|| heldItem instanceof BoneMealItem
			|| held.is(ItemTags.HOES)
			|| held.is(ItemTags.SHOVELS)
			|| held.is(ItemTags.AXES)
			|| heldItem instanceof ShearsItem
			|| heldItem instanceof BrushItem) {
			return protection.checkAndNotify(player, level, targetPos, BuiltinFlags.PLACE_BLOCKS)
				? InteractionResult.PASS
				: InteractionResult.FAIL;
		}
		return InteractionResult.PASS;
	}

	private InteractionResult onUseEntity(
		Player player,
		Level level,
		InteractionHand hand,
		Entity entity,
		@Nullable EntityHitResult hitResult
	) {
		if (!protection.isProtectedDimension(level)) {
			return InteractionResult.PASS;
		}
		BlockPos pos = entity.blockPosition();
		ItemStack held = player.getItemInHand(hand);
		Identifier flag = classifyUseEntityFlag(entity, held);
		if (flag == null) {
			return InteractionResult.PASS;
		}
		return protection.checkAndNotify(player, level, pos, flag)
			? InteractionResult.PASS
			: InteractionResult.FAIL;
	}

	private InteractionResult onAttackEntity(
		Player player,
		Level level,
		InteractionHand hand,
		Entity entity,
		@Nullable EntityHitResult hitResult
	) {
		if (!protection.isProtectedDimension(level) || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}
		return isEntityAttackAllowed(serverPlayer, entity)
			? InteractionResult.PASS
			: InteractionResult.FAIL;
	}

	private boolean onAllowDamage(net.minecraft.world.entity.LivingEntity entity, DamageSource source, float amount) {
		Level level = entity.level();
		if (!protection.isProtectedDimension(level)) {
			return true;
		}
		if (source.getEntity() instanceof ServerPlayer attacker) {
			return isEntityAttackAllowed(attacker, entity);
		}
		if (source.is(DamageTypeTags.IS_EXPLOSION)
			&& (source.getEntity() instanceof Creeper || source.getDirectEntity() instanceof Creeper)
			&& !islandService.isWorldSettingEnabled(BuiltinFlags.CREEPER_DAMAGE)) {
			return false;
		}
		return true;
	}

	private InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
		if (!protection.isProtectedDimension(level)) {
			return InteractionResult.PASS;
		}
		ItemStack held = player.getItemInHand(hand);
		Item item = held.getItem();
		BlockPos pos = player.blockPosition();
		if (item instanceof EnderpearlItem || held.is(Items.CHORUS_FRUIT)) {
			return protection.checkAndNotify(player, level, pos, BuiltinFlags.ENDER_PEARL)
				? InteractionResult.PASS
				: InteractionResult.FAIL;
		}
		if (item instanceof BucketItem || item instanceof SolidBucketItem || held.is(Items.MILK_BUCKET)) {
			return protection.checkAndNotify(player, level, pos, BuiltinFlags.BUCKET)
				? InteractionResult.PASS
				: InteractionResult.FAIL;
		}
		if (item instanceof FlintAndSteelItem || item instanceof FireChargeItem) {
			return protection.checkAndNotify(player, level, pos, BuiltinFlags.FLINT_AND_STEEL)
				? InteractionResult.PASS
				: InteractionResult.FAIL;
		}
		if (item instanceof SpawnEggItem) {
			return protection.checkAndNotify(player, level, pos, BuiltinFlags.PLACE_BLOCKS)
				? InteractionResult.PASS
				: InteractionResult.FAIL;
		}
		return InteractionResult.PASS;
	}

	private void tickPlayers(MinecraftServer server) {
		for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
			ServerLevel level = player.level();
			ResourceKey<Level> dimension = level.dimension();
			if (!OgskyDimensions.isSkyblockDimension(dimension)) {
				UUID previousId = lastPlayerIsland.remove(player.getUUID());
				if (previousId != null) {
					eventBus.publish(new IslandExitEvent(previousId, player.getUUID()));
				}
				continue;
			}

			if (handleVoidRescue(server, player, level, dimension)) {
				continue;
			}

			Island currentIsland = islandService.grid().findAt(player.getBlockX(), player.getBlockZ());
			if (currentIsland != null && !protection.canEnterIsland(currentIsland, player)) {
				String denyMsg = currentIsland.isBanned(player.getUUID())
					? "You are banned from this island."
					: "This island is locked to visitors.";
				protection.notifyDenied(player, denyMsg);
				evictPlayer(server, player, currentIsland.islandId());
				UUID previousId = lastPlayerIsland.remove(player.getUUID());
				if (previousId != null) {
					eventBus.publish(new IslandExitEvent(previousId, player.getUUID()));
				}
				continue;
			}

			UUID currentId = currentIsland == null ? null : currentIsland.islandId();
			UUID previousId = lastPlayerIsland.get(player.getUUID());
			if (!Objects.equals(previousId, currentId)) {
				if (previousId != null) {
					eventBus.publish(new IslandExitEvent(previousId, player.getUUID()));
					Island prevIsland = islandService.grid().findById(previousId).orElse(null);
					if (prevIsland != null && prevIsland.isSettingEnabledBySlot(islandService.flagSlot(BuiltinFlags.ENTER_EXIT_MESSAGES))) {
						player.sendSystemMessage(Component.literal("[Ogsky] Left island boundary.").withStyle(ChatFormatting.GRAY));
					}
				}
				if (currentId != null) {
					boolean adminBypass = protection.hasEntryBypass(player);
					boolean allowedByBus = protection.canEnterIsland(currentIsland, player)
						&& (player.getUUID().equals(currentIsland.ownerId())
							|| eventBus.publishCancellable(new IslandEnterEvent(currentId, player.getUUID(), adminBypass)));
					if (!allowedByBus) {
						protection.notifyDenied(player, "You cannot enter this island.");
						evictPlayer(server, player, currentId);
						lastPlayerIsland.remove(player.getUUID());
						continue;
					}
					lastPlayerIsland.put(player.getUUID(), currentId);
					if (currentIsland.isSettingEnabledBySlot(islandService.flagSlot(BuiltinFlags.ENTER_EXIT_MESSAGES))) {
						player.sendSystemMessage(Component.literal("[Ogsky] Entered island.").withStyle(ChatFormatting.GREEN));
					}
				} else {
					lastPlayerIsland.remove(player.getUUID());
				}
			}
		}
	}

	private boolean handleVoidRescue(MinecraftServer server, ServerPlayer player, ServerLevel level, ResourceKey<Level> dim) {
		if (player.isSpectator() || player.getAbilities().flying) {
			return false;
		}
		if (player.getY() >= level.getMinY() - 2) {
			return false;
		}
		if (!islandService.isWorldSettingEnabled(BuiltinFlags.VOID_TELEPORT)) {
			return false;
		}
		player.resetFallDistance();
		if ("ISLAND_HOME".equalsIgnoreCase(config.voidTeleport.mode)) {
			Optional<Island> primary = islandService.grid().findPrimaryIsland(player.getUUID());
			if (primary.isPresent()) {
				ResourceKey<Level> targetDim = dim;
				if ((dim.equals(OgskyDimensions.NETHER) && !primary.get().isNetherReady())
					|| (dim.equals(OgskyDimensions.END) && !primary.get().isEndReady())) {
					targetDim = OgskyDimensions.OVERWORLD;
				}
				OperationResult<Void> result = lifecycleService.teleportHome(server, player, primary.get(), targetDim);
				if (result.success()) {
					player.sendSystemMessage(Component.literal("[Ogsky] Rescued from the void.").withStyle(ChatFormatting.YELLOW));
					return true;
				}
			}
		}
		player.teleport(TeleportTransition.createDefault(player, TeleportTransition.DO_NOTHING));
		player.sendSystemMessage(Component.literal("[Ogsky] Rescued from the void.").withStyle(ChatFormatting.YELLOW));
		return true;
	}

	private void evictPlayer(MinecraftServer server, ServerPlayer player, UUID deniedIslandId) {
		Optional<Island> primary = islandService.grid().findPrimaryIsland(player.getUUID());
		if (primary.isPresent() && !primary.get().islandId().equals(deniedIslandId)) {
			OperationResult<Void> home = lifecycleService.teleportHome(server, player, primary.get(), OgskyDimensions.OVERWORLD);
			if (home.success()) {
				return;
			}
		}
		player.teleport(TeleportTransition.createDefault(player, TeleportTransition.DO_NOTHING));
	}

	private boolean isEntityAttackAllowed(ServerPlayer attacker, Entity target) {
		if (target instanceof ServerPlayer victim) {
			if (protection.isPvpAllowed(attacker, victim)) {
				return true;
			}
			protection.notifyDenied(attacker, "PvP is disabled on this island.");
			return false;
		}
		Identifier flag = classifyAttackEntityFlag(target);
		if (flag == null) {
			return true;
		}
		return protection.checkAndNotify(attacker, target.level(), target.blockPosition(), flag);
	}

	private static @Nullable Identifier classifyUseBlockFlag(Level level, BlockPos pos, BlockState state) {
		Block block = state.getBlock();
		if (block instanceof HopperBlock) {
			return BuiltinFlags.HOPPER_USE;
		}
		if (block instanceof AbstractFurnaceBlock) {
			return BuiltinFlags.FURNACE;
		}
		if (block instanceof DoorBlock || block instanceof FenceGateBlock) {
			return BuiltinFlags.DOOR;
		}
		if (block instanceof TrapDoorBlock) {
			return BuiltinFlags.TRAPDOOR;
		}
		if (block instanceof ButtonBlock || block instanceof LeverBlock || block instanceof BellBlock) {
			return BuiltinFlags.BUTTON_LEVER;
		}
		if (block instanceof RepeaterBlock
			|| block instanceof ComparatorBlock
			|| block instanceof DaylightDetectorBlock
			|| block instanceof NoteBlock
			|| block instanceof CopperBulbBlock) {
			return BuiltinFlags.REDSTONE;
		}
		if (block instanceof BedBlock || block instanceof RespawnAnchorBlock) {
			return BuiltinFlags.BED;
		}
		if (block instanceof AnvilBlock) {
			return BuiltinFlags.ANVIL;
		}
		if (block instanceof BeaconBlock) {
			return BuiltinFlags.BEACON;
		}
		if (block instanceof BrewingStandBlock || block instanceof AbstractCauldronBlock) {
			return BuiltinFlags.BREWING;
		}
		if (block instanceof EnchantingTableBlock
			|| block instanceof GrindstoneBlock
			|| block instanceof StonecutterBlock
			|| block instanceof LoomBlock
			|| block instanceof SmithingTableBlock) {
			return BuiltinFlags.ENCHANTING;
		}
		if (block instanceof SweetBerryBushBlock || block instanceof CaveVines) {
			return BuiltinFlags.HARVEST;
		}
		if (block instanceof ChestBlock
			|| block instanceof BarrelBlock
			|| block instanceof ShulkerBoxBlock
			|| block instanceof DispenserBlock
			|| block instanceof CrafterBlock
			|| block instanceof ChiseledBookShelfBlock
			|| block instanceof DecoratedPotBlock
			|| block instanceof ComposterBlock
			|| block instanceof CakeBlock
			|| block instanceof JukeboxBlock
			|| block instanceof LecternBlock
			|| level.getBlockEntity(pos) instanceof Container) {
			return BuiltinFlags.CONTAINER;
		}
		return null;
	}

	private static @Nullable Identifier classifyUseEntityFlag(Entity entity, ItemStack held) {
		if (entity instanceof ArmorStand) {
			return BuiltinFlags.ARMOR_STAND;
		}
		if (entity instanceof ItemFrame || entity instanceof GlowItemFrame || entity instanceof Painting) {
			return BuiltinFlags.ITEM_FRAME;
		}
		if (entity instanceof MinecartHopper) {
			return BuiltinFlags.HOPPER_USE;
		}
		if (entity instanceof MinecartChest || entity instanceof ChestBoat) {
			return BuiltinFlags.CONTAINER;
		}
		if (entity instanceof AbstractVillager) {
			return BuiltinFlags.TRADING;
		}
		if (held.getItem() instanceof ShearsItem
			&& (entity instanceof Sheep || entity instanceof MushroomCow || entity instanceof SnowGolem)) {
			return BuiltinFlags.SHEAR_ENTITY;
		}
		if (held.is(Items.BUCKET) && entity instanceof Animal) {
			return BuiltinFlags.BUCKET;
		}
		if (entity instanceof Animal animal && animal.isFood(held)) {
			return BuiltinFlags.BREEDING;
		}
		if (held.getItem() instanceof LeadItem || held.getItem() instanceof NameTagItem) {
			return BuiltinFlags.MOUNT_ENTITY;
		}
		if (entity instanceof AbstractHorse
			|| entity instanceof AbstractBoat
			|| entity instanceof AbstractMinecart
			|| entity instanceof Pig
			|| entity instanceof Strider
			|| entity instanceof HappyGhast
			|| entity instanceof TamableAnimal) {
			return BuiltinFlags.MOUNT_ENTITY;
		}
		return null;
	}

	private static @Nullable Identifier classifyAttackEntityFlag(Entity target) {
		if (target instanceof ArmorStand) {
			return BuiltinFlags.ARMOR_STAND;
		}
		if (target instanceof ItemFrame || target instanceof GlowItemFrame || target instanceof Painting) {
			return BuiltinFlags.ITEM_FRAME;
		}
		if (target instanceof VehicleEntity) {
			return BuiltinFlags.BREAK_BLOCKS;
		}
		if (target instanceof AbstractVillager || target instanceof IronGolem) {
			return BuiltinFlags.HURT_VILLAGERS;
		}
		if (target instanceof Enemy) {
			return BuiltinFlags.HURT_MONSTERS;
		}
		if (target instanceof Animal
			|| target instanceof WaterAnimal
			|| target instanceof AmbientCreature
			|| target instanceof AbstractFish
			|| target instanceof SnowGolem
			|| target instanceof Allay) {
			return BuiltinFlags.HURT_ANIMALS;
		}
		return null;
	}

	private static boolean isHarvestableCrop(Block block) {
		return block instanceof CropBlock
			|| block instanceof CocoaBlock
			|| block instanceof SweetBerryBushBlock
			|| block instanceof NetherWartBlock
			|| block instanceof CaveVines;
	}
}
