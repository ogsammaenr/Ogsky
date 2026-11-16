package me.ogsammaenr.ogsky.gametest;

import io.netty.channel.embedded.EmbeddedChannel;
import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.action.IslandActionContext;
import me.ogsammaenr.ogsky.core.api.action.IslandActionDescriptor;
import me.ogsammaenr.ogsky.core.api.db.ModuleDatabase;
import me.ogsammaenr.ogsky.core.api.event.IslandPreDeleteEvent;
import me.ogsammaenr.ogsky.core.internal.OgskyCoreApiImpl;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandService;
import me.ogsammaenr.ogsky.core.internal.island.Island;
import me.ogsammaenr.ogsky.core.internal.world.OgskyDimensions;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Physical block/entity placement limits ({@code ogsky-limits}), fluid generator policies, {@code view_limits} flag, GUI, and chat fallback GameTests.
 */
public final class LimitsGameTests {
	// GameTestServer.waitUntilNextTick() spins unthrottled (>10k ticks/s) while awaiting async IOWorker chunk reads.
	@GameTest(maxTicks = 200000)
	public void limitsBlockEntityLiquidFlagGuiFallbackAndLifecycleWork(GameTestHelper helper) throws Exception {
		if (!FabricLoader.getInstance().isModLoaded("ogsky-limits")) {
			helper.succeed();
			return;
		}

		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel ogOverworld = server.getLevel(OgskyDimensions.OVERWORLD);
		helper.assertTrue(ogOverworld != null, "Expected ogsky overworld dimension");

		OgskyCoreApiImpl coreApi = (OgskyCoreApiImpl) OgskyCore.api();
		CoreIslandService islands = coreApi.coreIslands();

		Path limitsConfigPath = FabricLoader.getInstance().getConfigDir().resolve("ogsky").resolve("limits.json");
		String originalLimitsJson = Files.exists(limitsConfigPath) ? Files.readString(limitsConfigPath) : null;

		Path blueprintsRoot = coreApi.blueprints().registry().blueprintsDir();
		Path bundleDir = blueprintsRoot.resolve("limits_test");

		UUID ownerId = UUID.randomUUID();
		UUID coopId = UUID.randomUUID();
		UUID visitorId = UUID.randomUUID();
		AtomicReference<EmbeddedChannel> ownerChannel = new AtomicReference<>();
		AtomicReference<EmbeddedChannel> coopChannel = new AtomicReference<>();
		AtomicReference<EmbeddedChannel> visitorChannel = new AtomicReference<>();
		ServerPlayer owner = GameTestSupport.createOnlineMockPlayer(server, ogOverworld, ownerId, "LimitOwner", GameType.SURVIVAL, ownerChannel);
		ServerPlayer coop = GameTestSupport.createOnlineMockPlayer(server, ogOverworld, coopId, "LimitCoop", GameType.SURVIVAL, coopChannel);
		ServerPlayer visitor = GameTestSupport.createOnlineMockPlayer(server, ogOverworld, visitorId, "LimitVisitor", GameType.SURVIVAL, visitorChannel);

		AtomicReference<UUID> islandIdRef = new AtomicReference<>();
		AtomicReference<int[]> forcedChunkCenterRef = new AtomicReference<>();
		AtomicBoolean rescanCompleted = new AtomicBoolean();

		Runnable cleanup = () -> {
			GameTestSupport.closeContainersQuietly(owner, coop, visitor);
			try {
				if (coreApi.permissions().isBypassActive(visitor)) {
					coreApi.permissions().toggleBypass(visitor);
				}
				if (originalLimitsJson != null) {
					Files.writeString(limitsConfigPath, originalLimitsJson);
				} else {
					Files.deleteIfExists(limitsConfigPath);
				}
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "isadmin limits reload");
			} catch (Exception ignored) {
			}
			UUID id = islandIdRef.get();
			if (id != null) {
				islands.grid().findById(id).ifPresent(found -> {
					for (Entity e : ogOverworld.getEntitiesOfClass(Entity.class, new AABB(
						found.minX() - 4, 0, found.minZ() - 4, found.maxX() + 4, 256, found.maxZ() + 4
					))) {
						if (!(e instanceof ServerPlayer)) {
							e.discard();
						}
					}
					GameTestSupport.deleteAndCleanupIsland(server, coreApi, islands, id, ownerId);
				});
			}
			int[] forcedCenter = forcedChunkCenterRef.get();
			if (forcedCenter != null) {
				for (int dcx = -1; dcx <= 1; dcx++) {
					for (int dcz = -1; dcz <= 1; dcz++) {
						ogOverworld.setChunkForced(forcedCenter[0] + dcx, forcedCenter[1] + dcz, false);
					}
				}
			}
			GameTestSupport.deleteDirectoryRecursively(bundleDir);
			coreApi.blueprints().registry().reload();
			server.getPlayerList().remove(owner);
			server.getPlayerList().remove(coop);
			server.getPlayerList().remove(visitor);
		};

		GameTestSupport.runWithCleanup(cleanup, () -> {
			Files.createDirectories(limitsConfigPath.getParent());
			Files.writeString(limitsConfigPath, """
				{
				  "enforce": true,
				  "countExistingOnFirstAccess": true,
				  "autoScanOnBlueprintComplete": true,
				  "defaultViewLimitsRank": "MEMBER",
				  "bypassPermission": "ogsky.limits.bypass",
				  "notifyCooldownMillis": 0,
				  "scanChunksPerTick": 8,
				  "scanMaxMillisPerTick": 4,
				  "scanIncludeUnloadedChunks": true,
				  "dbFlushIntervalTicks": 5,
				  "liquidGeneratorPolicy": "CANCEL",
				  "countBaseMultiBlockPartsOnly": true,
				  "blockLimits": {
				    "minecraft:hopper": 2,
				    "#minecraft:shulker_boxes": 2,
				    "minecraft:red_shulker_box": 1,
				    "#minecraft:beds": 1,
				    "minecraft:oak_door": 1,
				    "minecraft:piston": 2,
				    "minecraft:sticky_piston": 2,
				    "minecraft:obsidian": 1,
				    "minecraft:cobblestone": 2
				  },
				  "blockGroups": {
				    "piston_family": {
				      "displayName": "Total Pistons",
				      "icon": "minecraft:piston",
				      "limit": 2,
				      "entries": ["minecraft:piston", "minecraft:sticky_piston"]
				    }
				  },
				  "entityLimits": {
				    "minecraft:armor_stand": 1,
				    "minecraft:iron_golem": 1,
				    "minecraft:item_frame": 1,
				    "#minecraft:boat": 1
				  },
				  "entityGroups": {
				    "farm_animals": {
				      "displayName": "Farm Animals",
				      "icon": "minecraft:wheat",
				      "limit": 2,
				      "entries": ["minecraft:cow", "minecraft:sheep"]
				    }
				  }
				}
				""");
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "isadmin limits reload");

			GameTestSupport.synthesizeSimpleBlueprintBundle(ogOverworld, bundleDir, "limits_test", "Limits Test", 5);
			coreApi.blueprints().registry().reload();

			var created = coreApi.islands().createIsland(server, owner, ownerId, "limits_test");
			helper.assertTrue(created.success() && created.value() != null, "Expected test island creation: " + created.message());
			islandIdRef.set(created.value().islandId());
			coreApi.lifecycle().resizeIsland(islandIdRef.get(), 16);

			helper.startSequence()
				.thenIdle(2)
				.thenWaitUntil(() -> {
					Island isl = islands.grid().findById(islandIdRef.get()).orElse(null);
					helper.assertTrue(isl != null && !isl.isPasting() && isl.isOverworldReady(), "Expected limits test island ready");
				})
				.thenExecute(GameTestSupport.guarded(cleanup, () -> {
					Island isl = islands.grid().findById(islandIdRef.get()).orElseThrow();
					int baseChunkX = isl.centerX() >> 4;
					int baseChunkZ = isl.centerZ() >> 4;
					forcedChunkCenterRef.set(new int[] { baseChunkX, baseChunkZ });
					for (int dcx = -1; dcx <= 1; dcx++) {
						for (int dcz = -1; dcz <= 1; dcz++) {
							ogOverworld.setChunkForced(baseChunkX + dcx, baseChunkZ + dcz, true);
						}
					}
				}))
				.thenWaitUntil(() -> {
					Island isl = islands.grid().findById(islandIdRef.get()).orElseThrow();
					int cx = isl.centerX();
					int cy = coreApi.config().island.defaultSpawnY;
					int cz = isl.centerZ();
					helper.assertTrue(
						ogOverworld.isPositionEntityTicking(new BlockPos(cx, cy, cz))
							&& ogOverworld.isPositionEntityTicking(new BlockPos(cx - 8, cy, cz - 8))
							&& ogOverworld.isPositionEntityTicking(new BlockPos(cx + 8, cy, cz - 8))
							&& ogOverworld.isPositionEntityTicking(new BlockPos(cx - 8, cy, cz + 8))
							&& ogOverworld.isPositionEntityTicking(new BlockPos(cx + 8, cy, cz + 8)),
						"Expected forced 3x3 island chunks to be entity-ticking"
					);
				})
				.thenExecute(GameTestSupport.guarded(cleanup, () -> {
					Island isl = islands.grid().findById(islandIdRef.get()).orElseThrow();
					int cx = isl.centerX();
					int cy = coreApi.config().island.defaultSpawnY;
					int cz = isl.centerZ();

					// Build flat stone base inside island; note stone is NOT in blockLimits!
					for (int dx = -8; dx <= 8; dx++) {
						for (int dz = -8; dz <= 8; dz++) {
							ogOverworld.setBlock(new BlockPos(cx + dx, cy - 1, cz + dz), Blocks.STONE.defaultBlockState(), 18);
							ogOverworld.setBlock(new BlockPos(cx + dx, cy, cz + dz), Blocks.AIR.defaultBlockState(), 18);
						}
					}
					owner.teleport(new TeleportTransition(ogOverworld, new Vec3(cx + 0.5, cy, cz + 0.5), Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.DO_NOTHING));
					coop.teleport(new TeleportTransition(ogOverworld, new Vec3(cx + 0.5, cy, cz + 0.5), Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.DO_NOTHING));
					visitor.teleport(new TeleportTransition(ogOverworld, new Vec3(cx + 0.5, cy, cz + 0.5), Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.DO_NOTHING));

					// Ensure COOP role is assigned for flag checks
					coreApi.team().addCoop(isl, ownerId, coopId, false);

					// 1. Exact + Tag multi-bucket block limits (shulker_boxes=2, red_shulker_box=1)
					BlockPos shulker1Pos = new BlockPos(cx + 1, cy, cz);
					BlockPos shulker2Pos = new BlockPos(cx + 2, cy, cz);
					BlockPos shulker3Pos = new BlockPos(cx + 3, cy, cz);
					BlockPos shulker4Pos = new BlockPos(cx + 4, cy, cz);

					owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DYED_SHULKER_BOX.red(), 2));
					owner.gameMode.useItemOn(owner, ogOverworld, owner.getMainHandItem(), InteractionHand.MAIN_HAND,
						new BlockHitResult(Vec3.atCenterOf(shulker1Pos.below()), Direction.UP, shulker1Pos.below(), false));
					helper.assertTrue(ogOverworld.getBlockState(shulker1Pos).is(Blocks.DYED_SHULKER_BOX.red()), "1st red shulker should place");
					helper.assertTrue(owner.getMainHandItem().getCount() == 1, "1st red shulker consumes 1 item");

					owner.gameMode.useItemOn(owner, ogOverworld, owner.getMainHandItem(), InteractionHand.MAIN_HAND,
						new BlockHitResult(Vec3.atCenterOf(shulker2Pos.below()), Direction.UP, shulker2Pos.below(), false));
					helper.assertTrue(ogOverworld.getBlockState(shulker2Pos).isAir(), "2nd red shulker blocked by exact limit 1/1");
					helper.assertTrue(owner.getMainHandItem().getCount() == 1, "Blocked red shulker item preserved in hand");

					owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DYED_SHULKER_BOX.blue(), 1));
					owner.gameMode.useItemOn(owner, ogOverworld, owner.getMainHandItem(), InteractionHand.MAIN_HAND,
						new BlockHitResult(Vec3.atCenterOf(shulker3Pos.below()), Direction.UP, shulker3Pos.below(), false));
					helper.assertTrue(ogOverworld.getBlockState(shulker3Pos).is(Blocks.DYED_SHULKER_BOX.blue()), "Blue shulker fits within #shulker_boxes 2/2");

					owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DYED_SHULKER_BOX.white(), 1));
					owner.gameMode.useItemOn(owner, ogOverworld, owner.getMainHandItem(), InteractionHand.MAIN_HAND,
						new BlockHitResult(Vec3.atCenterOf(shulker4Pos.below()), Direction.UP, shulker4Pos.below(), false));
					helper.assertTrue(ogOverworld.getBlockState(shulker4Pos).isAir(), "White shulker blocked because #shulker_boxes tag cap is 2/2");

					// 2. Custom tagless blockGroup (piston_family=2) + break decrement
					BlockPos piston1Pos = new BlockPos(cx - 1, cy, cz);
					BlockPos piston2Pos = new BlockPos(cx - 2, cy, cz);
					BlockPos piston3Pos = new BlockPos(cx - 3, cy, cz);
					owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PISTON, 2));
					owner.gameMode.useItemOn(owner, ogOverworld, owner.getMainHandItem(), InteractionHand.MAIN_HAND,
						new BlockHitResult(Vec3.atCenterOf(piston1Pos.below()), Direction.UP, piston1Pos.below(), false));
					owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICKY_PISTON, 1));
					owner.gameMode.useItemOn(owner, ogOverworld, owner.getMainHandItem(), InteractionHand.MAIN_HAND,
						new BlockHitResult(Vec3.atCenterOf(piston2Pos.below()), Direction.UP, piston2Pos.below(), false));
					helper.assertTrue(ogOverworld.getBlockState(piston1Pos).is(Blocks.PISTON) && ogOverworld.getBlockState(piston2Pos).is(Blocks.STICKY_PISTON),
						"1 piston + 1 sticky_piston allowed within piston_family=2");

					owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PISTON, 1));
					owner.gameMode.useItemOn(owner, ogOverworld, owner.getMainHandItem(), InteractionHand.MAIN_HAND,
						new BlockHitResult(Vec3.atCenterOf(piston3Pos.below()), Direction.UP, piston3Pos.below(), false));
					helper.assertTrue(ogOverworld.getBlockState(piston3Pos).isAir(), "3rd piston blocked by custom blockGroup piston_family=2");

					helper.assertTrue(owner.gameMode.destroyBlock(piston2Pos), "Owner breaks sticky_piston cleanly");
					owner.gameMode.useItemOn(owner, ogOverworld, owner.getMainHandItem(), InteractionHand.MAIN_HAND,
						new BlockHitResult(Vec3.atCenterOf(piston3Pos.below()), Direction.UP, piston3Pos.below(), false));
					helper.assertTrue(ogOverworld.getBlockState(piston3Pos).is(Blocks.PISTON), "Breaking sticky_piston frees piston_family slot");

					// 3. Base-only double blocks (#beds=1, oak_door=1)
					BlockPos bedPos = new BlockPos(cx, cy, cz + 2);
					BlockPos bedBlockedPos = new BlockPos(cx + 2, cy, cz + 2);
					owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BED.red(), 2));
					owner.gameMode.useItemOn(owner, ogOverworld, owner.getMainHandItem(), InteractionHand.MAIN_HAND,
						new BlockHitResult(Vec3.atCenterOf(bedPos.below()), Direction.UP, bedPos.below(), false));
					helper.assertTrue(ogOverworld.getBlockState(bedPos).is(Blocks.BED.red()), "Single red bed fits within #beds=1");
					owner.gameMode.useItemOn(owner, ogOverworld, owner.getMainHandItem(), InteractionHand.MAIN_HAND,
						new BlockHitResult(Vec3.atCenterOf(bedBlockedPos.below()), Direction.UP, bedBlockedPos.below(), false));
					helper.assertTrue(ogOverworld.getBlockState(bedBlockedPos).isAir(), "2nd bed blocked at #beds=1");

					BlockPos doorPos = new BlockPos(cx - 2, cy, cz + 2);
					BlockPos doorBlockedPos = new BlockPos(cx - 3, cy, cz + 2);
					owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.OAK_DOOR, 2));
					owner.gameMode.useItemOn(owner, ogOverworld, owner.getMainHandItem(), InteractionHand.MAIN_HAND,
						new BlockHitResult(Vec3.atCenterOf(doorPos.below()), Direction.UP, doorPos.below(), false));
					helper.assertTrue(ogOverworld.getBlockState(doorPos).is(Blocks.OAK_DOOR), "Single oak door fits within oak_door=1");
					owner.gameMode.useItemOn(owner, ogOverworld, owner.getMainHandItem(), InteractionHand.MAIN_HAND,
						new BlockHitResult(Vec3.atCenterOf(doorBlockedPos.below()), Direction.UP, doorBlockedPos.below(), false));
					helper.assertTrue(ogOverworld.getBlockState(doorBlockedPos).isAir(), "2nd oak door blocked at oak_door=1");

					// 4. Liquid generator CANCEL policy (obsidian=1 reached -> adjacent water+lava must NOT turn lava into obsidian)
					ogOverworld.setBlock(new BlockPos(cx + 5, cy, cz + 4), Blocks.OBSIDIAN.defaultBlockState(), 3);
					BlockPos lavaGenPos = new BlockPos(cx + 3, cy, cz + 4);
					BlockPos waterGenPos = new BlockPos(cx + 4, cy, cz + 4);
					ogOverworld.setBlock(waterGenPos, Blocks.WATER.defaultBlockState(), 3);
					ogOverworld.setBlock(lavaGenPos, Blocks.LAVA.defaultBlockState(), 3);
					helper.assertTrue(!ogOverworld.getBlockState(lavaGenPos).is(Blocks.OBSIDIAN),
						"Obsidian generator must be cancelled when obsidian=1/1 limit is already reached");
					ogOverworld.setBlock(lavaGenPos, Blocks.AIR.defaultBlockState(), 18);
					ogOverworld.setBlock(waterGenPos, Blocks.AIR.defaultBlockState(), 18);

					// 5. Entity pre-spawn limits (ArmorStand, Boat, ItemFrame, IronGolem, Cow breeding) + Dispenser
					BlockPos standPos = new BlockPos(cx + 1, cy, cz - 2);
					BlockPos standBlockedPos = new BlockPos(cx + 2, cy, cz - 2);
					owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.ARMOR_STAND, 2));
					owner.gameMode.useItemOn(owner, ogOverworld, owner.getMainHandItem(), InteractionHand.MAIN_HAND,
						new BlockHitResult(Vec3.atCenterOf(standPos.below()), Direction.UP, standPos.below(), false));
					int stand1Count = ogOverworld.getEntitiesOfClass(ArmorStand.class, new AABB(cx - 6, cy - 2, cz - 6, cx + 6, cy + 4, cz + 6)).size();
					helper.assertTrue(
						stand1Count == 1,
						"1st ArmorStand allowed (1/1)"
					);
					int standsInHand = owner.getMainHandItem().getCount();
					owner.gameMode.useItemOn(owner, ogOverworld, owner.getMainHandItem(), InteractionHand.MAIN_HAND,
						new BlockHitResult(Vec3.atCenterOf(standBlockedPos.below()), Direction.UP, standBlockedPos.below(), false));
					helper.assertTrue(
						ogOverworld.getEntitiesOfClass(ArmorStand.class, new AABB(cx - 6, cy - 2, cz - 6, cx + 6, cy + 4, cz + 6)).size() == 1
							&& owner.getMainHandItem().getCount() == standsInHand,
						"2nd ArmorStand blocked pre-spawn and item kept in hand"
					);

					// Dispenser ArmorStand spawn blocked pre-spawn and item kept inside dispenser
					BlockPos dispenserPos = new BlockPos(cx + 4, cy, cz - 2);
					ogOverworld.setBlock(dispenserPos, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.WEST), 3);
					DispenserBlockEntity dispenserBe = (DispenserBlockEntity) ogOverworld.getBlockEntity(dispenserPos);
					helper.assertTrue(dispenserBe != null, "Expected DispenserBlockEntity");
					dispenserBe.setItem(0, new ItemStack(Items.ARMOR_STAND, 1));
					ogOverworld.setBlock(dispenserPos.east(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);

					// ItemFrame pre-spawn (wall stone)
					BlockPos frameWall = new BlockPos(cx, cy, cz - 4);
					BlockPos frameWall2 = new BlockPos(cx + 1, cy, cz - 4);
					ogOverworld.setBlock(frameWall, Blocks.STONE.defaultBlockState(), 3);
					ogOverworld.setBlock(frameWall2, Blocks.STONE.defaultBlockState(), 3);
					owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.ITEM_FRAME, 2));
					owner.gameMode.useItemOn(owner, ogOverworld, owner.getMainHandItem(), InteractionHand.MAIN_HAND,
						new BlockHitResult(Vec3.atCenterOf(frameWall), Direction.SOUTH, frameWall, false));
					int framesAfterOne = owner.getMainHandItem().getCount();
					owner.gameMode.useItemOn(owner, ogOverworld, owner.getMainHandItem(), InteractionHand.MAIN_HAND,
						new BlockHitResult(Vec3.atCenterOf(frameWall2), Direction.SOUTH, frameWall2, false));
					helper.assertTrue(
						ogOverworld.getEntitiesOfClass(ItemFrame.class, new AABB(cx - 6, cy - 2, cz - 6, cx + 6, cy + 4, cz + 6)).size() == 1
							&& owner.getMainHandItem().getCount() == framesAfterOne,
						"2nd ItemFrame blocked pre-spawn and item preserved"
					);

					// Boat pre-spawn (#minecraft:boat = 1) in wide-open southern stone zone (dz = +6..+8)
					owner.teleport(new TeleportTransition(ogOverworld, new Vec3(cx + 0.5, cy, cz + 8.0), Vec3.ZERO, 180.0F, 45.0F, TeleportTransition.DO_NOTHING));
					owner.setYRot(180.0F);
					owner.setXRot(45.0F);
					owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.OAK_BOAT, 2));
					owner.gameMode.useItem(owner, ogOverworld, owner.getMainHandItem(), InteractionHand.MAIN_HAND);
					int boatsAfterOneCount = ogOverworld.getEntitiesOfClass(Boat.class, new AABB(cx - 12, cy - 2, cz - 12, cx + 12, cy + 6, cz + 12)).size();
					int boatItemsAfterOne = owner.getMainHandItem().getCount();
					helper.assertTrue(
						boatsAfterOneCount == 1 && boatItemsAfterOne == 1,
						"Expected 1st Boat placed cleanly (boats=" + boatsAfterOneCount + ", hand=" + boatItemsAfterOne + ")"
					);
					owner.teleport(new TeleportTransition(ogOverworld, new Vec3(cx - 3.5, cy, cz + 8.0), Vec3.ZERO, 180.0F, 45.0F, TeleportTransition.DO_NOTHING));
					owner.setYRot(180.0F);
					owner.setXRot(45.0F);
					owner.gameMode.useItem(owner, ogOverworld, owner.getMainHandItem(), InteractionHand.MAIN_HAND);
					int boatsAfterTwoCount = ogOverworld.getEntitiesOfClass(Boat.class, new AABB(cx - 12, cy - 2, cz - 12, cx + 12, cy + 6, cz + 12)).size();
					helper.assertTrue(
						boatsAfterTwoCount == 1 && owner.getMainHandItem().getCount() == boatItemsAfterOne,
						"2nd Boat blocked pre-spawn (#minecraft:boat=1) and item kept (boats=" + boatsAfterTwoCount + ", hand=" + owner.getMainHandItem().getCount() + ")"
					);
					owner.teleport(new TeleportTransition(ogOverworld, new Vec3(cx + 0.5, cy, cz + 0.5), Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.DO_NOTHING));

					// IronGolem structure summon (iron_golem=1)
					BlockPos golem1Base = new BlockPos(cx - 5, cy, cz - 4);
					ogOverworld.setBlock(golem1Base, Blocks.IRON_BLOCK.defaultBlockState(), 3);
					ogOverworld.setBlock(golem1Base.above(), Blocks.IRON_BLOCK.defaultBlockState(), 3);
					ogOverworld.setBlock(golem1Base.above().west(), Blocks.IRON_BLOCK.defaultBlockState(), 3);
					ogOverworld.setBlock(golem1Base.above().east(), Blocks.IRON_BLOCK.defaultBlockState(), 3);
					owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CARVED_PUMPKIN, 2));
					owner.gameMode.useItemOn(owner, ogOverworld, owner.getMainHandItem(), InteractionHand.MAIN_HAND,
						new BlockHitResult(Vec3.atCenterOf(golem1Base.above()), Direction.UP, golem1Base.above(), false));
					helper.assertTrue(
						ogOverworld.getEntitiesOfClass(IronGolem.class, new AABB(cx - 10, cy - 2, cz - 10, cx + 10, cy + 8, cz + 10)).size() == 1,
						"1st IronGolem structure summon allowed (1/1)"
					);

					BlockPos golem2Base = new BlockPos(cx - 5, cy, cz - 1);
					ogOverworld.setBlock(golem2Base, Blocks.IRON_BLOCK.defaultBlockState(), 3);
					ogOverworld.setBlock(golem2Base.above(), Blocks.IRON_BLOCK.defaultBlockState(), 3);
					ogOverworld.setBlock(golem2Base.above().north(), Blocks.IRON_BLOCK.defaultBlockState(), 3);
					ogOverworld.setBlock(golem2Base.above().south(), Blocks.IRON_BLOCK.defaultBlockState(), 3);
					owner.gameMode.useItemOn(owner, ogOverworld, owner.getMainHandItem(), InteractionHand.MAIN_HAND,
						new BlockHitResult(Vec3.atCenterOf(golem2Base.above()), Direction.UP, golem2Base.above(), false));
					helper.assertTrue(
						ogOverworld.getEntitiesOfClass(IronGolem.class, new AABB(cx - 10, cy - 2, cz - 10, cx + 10, cy + 8, cz + 10)).size() == 1,
						"2nd IronGolem structure summon blocked pre-spawn (1/1)"
					);

					// Animal breeding limit (farm_animals=2) & UNLOADED_TO_CHUNK preservation
					Cow cow1 = EntityTypes.COW.create(ogOverworld, EntitySpawnReason.SPAWN_ITEM_USE);
					Cow cow2 = EntityTypes.COW.create(ogOverworld, EntitySpawnReason.SPAWN_ITEM_USE);
					helper.assertTrue(cow1 != null && cow2 != null, "Created Cow entities");
					cow1.setPos(cx + 0.5, cy, cz - 1.5);
					cow2.setPos(cx + 1.5, cy, cz - 1.5);
					ogOverworld.addFreshEntity(cow1);
					ogOverworld.addFreshEntity(cow2);
					cow1.setAge(0);
					cow2.setAge(0);
					cow1.spawnChildFromBreeding(ogOverworld, cow2);
					helper.assertTrue(
						ogOverworld.getEntitiesOfClass(Cow.class, new AABB(cx - 6, cy - 2, cz - 6, cx + 6, cy + 4, cz + 6)).size() == 2,
						"Breeding blocked at farm_animals=2/2"
					);

					// Discard 1 cow (DESTROYED -> decrements) vs UNLOADED_TO_CHUNK (must keep count)
					cow2.discard();
					Cow cow3 = EntityTypes.COW.create(ogOverworld, EntitySpawnReason.SPAWN_ITEM_USE);
					helper.assertTrue(cow3 != null, "Created replacement Cow");
					cow3.setPos(cx + 1.5, cy, cz - 1.5);
					helper.assertTrue(ogOverworld.addFreshEntity(cow3), "Destroying cow frees slot in farm_animals");

					// Verify UNLOADED_TO_CHUNK preserves ArmorStand counter (still cannot place 2nd stand)
					ArmorStand existingStand = ogOverworld.getEntitiesOfClass(ArmorStand.class, new AABB(cx - 6, cy - 2, cz - 6, cx + 6, cy + 4, cz + 6)).getFirst();
					existingStand.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
					BlockPos standAfterUnload = new BlockPos(cx + 3, cy, cz - 2);
					owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.ARMOR_STAND, 1));
					owner.gameMode.useItemOn(owner, ogOverworld, owner.getMainHandItem(), InteractionHand.MAIN_HAND,
						new BlockHitResult(Vec3.atCenterOf(standAfterUnload.below()), Direction.UP, standAfterUnload.below(), false));
					helper.assertTrue(
						ogOverworld.getEntitiesOfClass(ArmorStand.class, new AABB(cx - 6, cy - 2, cz - 6, cx + 6, cy + 4, cz + 6)).isEmpty()
							&& owner.getMainHandItem().getCount() == 1,
						"UNLOADED_TO_CHUNK must NOT decrement entity limit counter"
					);
				}))
				.thenIdle(6) // let dispenser redstone tick and periodic DB flush run
				.thenExecute(GameTestSupport.guarded(cleanup, () -> {
					Island isl = islands.grid().findById(islandIdRef.get()).orElseThrow();
					int cx = isl.centerX();
					int cy = coreApi.config().island.defaultSpawnY;
					int cz = isl.centerZ();
					BlockPos dispenserPos = new BlockPos(cx + 4, cy, cz - 2);
					DispenserBlockEntity dispenserBe = (DispenserBlockEntity) ogOverworld.getBlockEntity(dispenserPos);
					helper.assertTrue(
						dispenserBe != null && !dispenserBe.getItem(0).isEmpty() && dispenserBe.getItem(0).is(Items.ARMOR_STAND)
							&& ogOverworld.getEntitiesOfClass(ArmorStand.class, new AABB(cx - 6, cy - 2, cz - 6, cx + 6, cy + 4, cz + 6)).isEmpty(),
						"Expected dispenser ArmorStand spawn blocked while item kept in dispenser"
					);

					// 6. Verify /isadmin limits rescan resets live counters cleanly after unloaded entity left memory
					CommandSourceStack rescanSource = server.createCommandSourceStack().withSource(new CommandSource() {
						@Override
						public void sendSystemMessage(Component component) {
							if (component.getString().contains("Limit rescan completed")) {
								rescanCompleted.set(true);
							}
						}

						@Override
						public boolean acceptsSuccess() {
							return true;
						}

						@Override
						public boolean acceptsFailure() {
							return true;
						}

						@Override
						public boolean shouldInformAdmins() {
							return false;
						}
					});
					server.getCommands().performPrefixedCommand(rescanSource, "isadmin limits rescan LimitOwner");
				}))
				.thenWaitUntil(() -> helper.assertTrue(rescanCompleted.get(), "Expected /isadmin limits rescan to complete"))
				.thenExecute(GameTestSupport.guarded(cleanup, () -> {
					// 7. Verify ogsky_limits:view_limits flag, bypass, GUI vs chat fallback
					int coopDenied = 0;
					try {
						coopDenied = server.getCommands().getDispatcher().execute("is limits", coop.createCommandSourceStack());
					} catch (Exception ignored) {
					}
					helper.assertTrue(coopDenied == 0, "Expected /is limits denied for COOP when view_limits defaults to MEMBER");

					server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "is flag view_limits coop");
					GameTestSupport.drainOutboundPackets(coopChannel.get());
					int coopAllowed = 0;
					try {
						coopAllowed = server.getCommands().getDispatcher().execute("is limits", coop.createCommandSourceStack());
					} catch (Exception ex) {
						throw new RuntimeException(ex);
					}
					helper.assertTrue(coopAllowed > 0, "Expected /is limits allowed for COOP after setting view_limits=coop");

					if (FabricLoader.getInstance().isModLoaded("ogsky-panel")) {
						helper.assertTrue(
							coop.containerMenu instanceof ChestMenu limitsMenu && limitsMenu.slots.size() == 90,
							"Expected /is limits to open 6-row PAGED_GRID GUI when ogsky-panel is loaded"
						);
						ChestMenu limitsMenu = (ChestMenu) coop.containerMenu;
						// Slot 2 filter toggle: All -> Blocks Only -> Entities Only
						limitsMenu.clicked(2, 0, ContainerInput.PICKUP, coop);
						helper.assertTrue(
							limitsMenu.getSlot(2).getItem().get(DataComponents.CUSTOM_NAME).getString().contains("Blocks Only"),
							"Expected /is limits slot 2 filter toggle to switch to Blocks Only"
						);
						// Slot 47 sort toggle: Highest Usage -> Alphabetical
						limitsMenu.clicked(47, 0, ContainerInput.PICKUP, coop);
						helper.assertTrue(
							limitsMenu.getSlot(47).getItem().get(DataComponents.CUSTOM_NAME).getString().contains("Alphabetical"),
							"Expected /is limits slot 47 sort toggle to switch to Alphabetical"
						);
						coop.closeContainer();

						// Verify /is panel slot 8 summary shows Limits for owner
						server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "is panel");
					} else {
						// Chat fallback (-Pmodules=core,limits)
						helper.assertTrue(!coreApi.guis().isAvailable(), "Expected GuiService unavailable when ogsky-panel is absent");
						List<String> coopChats = GameTestSupport.drainOutboundPackets(coopChannel.get()).stream()
							.filter(ClientboundSystemChatPacket.class::isInstance)
							.map(ClientboundSystemChatPacket.class::cast)
							.map(p -> p.content().getString())
							.toList();
						helper.assertTrue(
							coopChats.stream().anyMatch(m -> m.contains("Island Limits: LimitOwner")),
							"Expected /is limits chat fallback header in -Pmodules=core,limits"
						);
						IslandActionDescriptor action = coreApi.actions()
							.find(Identifier.fromNamespaceAndPath("ogsky_limits", "island_limits"))
							.orElseThrow();
						action.handler().execute(new IslandActionContext(owner, islandIdRef.get()));
					}
				}))
				.thenWaitUntil(() -> {
					if (FabricLoader.getInstance().isModLoaded("ogsky-panel")) {
						helper.assertTrue(owner.containerMenu instanceof ChestMenu, "Expected /is panel open for owner");
					}
				})
				.thenExecute(GameTestSupport.guarded(cleanup, () -> {
					if (FabricLoader.getInstance().isModLoaded("ogsky-panel")) {
						ItemLore ownerSummaryLore = owner.containerMenu.getSlot(8).getItem().get(DataComponents.LORE);
						helper.assertTrue(
							ownerSummaryLore != null && ownerSummaryLore.lines().stream().anyMatch(l -> l.getString().contains("Limits:")),
							"Expected /is panel slot 8 summary to show Limits line for authorized viewer"
						);
						owner.closeContainer();

						// Enable admin bypass on visitor -> can view limits AND bypass hopper cap!
						if (!coreApi.permissions().isBypassActive(visitor)) {
							coreApi.permissions().toggleBypass(visitor);
						}
						int visitorBypassLimits = 0;
						try {
							visitorBypassLimits = server.getCommands().getDispatcher().execute("is limits", visitor.createCommandSourceStack());
						} catch (Exception ex) {
							throw new RuntimeException(ex);
						}
						helper.assertTrue(
							visitorBypassLimits > 0 && visitor.containerMenu instanceof ChestMenu,
							"Expected bypass admin visitor to bypass view_limits flag"
						);
						visitor.closeContainer();
					}

					// Bypass allows hopper placement past cap 2/2 (set hopper limit filled first)
					Island isl = islands.grid().findById(islandIdRef.get()).orElseThrow();
					int cx = isl.centerX();
					int cy = coreApi.config().island.defaultSpawnY;
					int cz = isl.centerZ();
					owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.HOPPER, 3));
					for (int h = 0; h < 3; h++) {
						BlockPos hp = new BlockPos(cx - 7 + h, cy, cz + 4);
						owner.gameMode.useItemOn(owner, ogOverworld, owner.getMainHandItem(), InteractionHand.MAIN_HAND,
							new BlockHitResult(Vec3.atCenterOf(hp.below()), Direction.UP, hp.below(), false));
					}
					helper.assertTrue(
						ogOverworld.getBlockState(new BlockPos(cx - 7, cy, cz + 4)).is(Blocks.HOPPER)
							&& ogOverworld.getBlockState(new BlockPos(cx - 6, cy, cz + 4)).is(Blocks.HOPPER)
							&& ogOverworld.getBlockState(new BlockPos(cx - 5, cy, cz + 4)).isAir(),
						"3rd hopper blocked at hopper=2/2"
					);
					if (!coreApi.permissions().isBypassActive(visitor)) {
						coreApi.permissions().toggleBypass(visitor);
					}
					visitor.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.HOPPER, 1));
					BlockPos bypassHopperPos = new BlockPos(cx - 5, cy, cz + 4);
					visitor.gameMode.useItemOn(visitor, ogOverworld, visitor.getMainHandItem(), InteractionHand.MAIN_HAND,
						new BlockHitResult(Vec3.atCenterOf(bypassHopperPos.below()), Direction.UP, bypassHopperPos.below(), false));
					helper.assertTrue(
						ogOverworld.getBlockState(bypassHopperPos).is(Blocks.HOPPER),
						"Player with active bypass can place hopper past limit"
					);

					// 8. Verify limits.db persistence & island deletion cleanup
					ModuleDatabase limitsDb = coreApi.databases().require("limits");
					limitsDb.flush().join();
					int beforeDeleteRows = limitsDb.queryBlocking(conn -> {
						try (Statement st = conn.createStatement();
							 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM ogsky_island_limits WHERE island_id = '" + islandIdRef.get() + "'")) {
							rs.next();
							return rs.getInt(1);
						}
					});
					helper.assertTrue(beforeDeleteRows > 0, "Expected persisted rows in limits.db before island delete");

					Island deletedTarget = islands.grid().findById(islandIdRef.get()).orElseThrow();
					int dGridX = deletedTarget.gridX();
					int dGridZ = deletedTarget.gridZ();
					int dMinX = deletedTarget.minX();
					int dMaxX = deletedTarget.maxX();
					int dMinZ = deletedTarget.minZ();
					int dMaxZ = deletedTarget.maxZ();
					coreApi.lifecycle().deleteIsland(null, islandIdRef.get(), ownerId, IslandPreDeleteEvent.DeleteReason.ADMIN);
					GameTestSupport.clearLoadedBounds(server, dMinX, dMaxX, dMinZ, dMaxZ);
					GameTestSupport.cleanupTestIsland(coreApi, islands, islandIdRef.get(), dGridX, dGridZ);
					limitsDb.flush().join();
					int afterDeleteRows = limitsDb.queryBlocking(conn -> {
						try (Statement st = conn.createStatement();
							 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM ogsky_island_limits WHERE island_id = '" + islandIdRef.get() + "'")) {
							rs.next();
							return rs.getInt(1);
						}
					});
					helper.assertTrue(afterDeleteRows == 0, "Expected limits.db rows cleaned up on island deletion");

					cleanup.run();
				}))
				.thenSucceed();
		});
	}
}
