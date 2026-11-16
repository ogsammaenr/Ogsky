package me.ogsammaenr.ogsky.gametest;

import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.event.IslandEnterEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandExitEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandPreDeleteEvent;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.internal.OgskyCoreApiImpl;
import me.ogsammaenr.ogsky.core.internal.flag.BuiltinFlags;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandService;
import me.ogsammaenr.ogsky.core.internal.island.Island;
import me.ogsammaenr.ogsky.core.internal.island.OperationResult;
import me.ogsammaenr.ogsky.core.internal.island.PackedGridMap;
import me.ogsammaenr.ogsky.core.internal.protection.ProtectionResolver;
import me.ogsammaenr.ogsky.core.internal.world.OgskyDimensions;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Island lifecycle, team operations, Brigadier commands, permission checks, player protections, and world-setting GameTests.
 */
public final class IslandProtectionGameTests {
	@GameTest
	public void islandLifecycleAndTeamOperationsWork(GameTestHelper helper) throws Exception {
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel overworld = server.getLevel(OgskyDimensions.OVERWORLD);
		helper.assertTrue(overworld != null, "Expected ogsky:overworld dimension");

		OgskyCoreApiImpl coreApi = (OgskyCoreApiImpl) OgskyCore.api();
		CoreIslandService islands = coreApi.coreIslands();
		Path bundleA = coreApi.blueprints().registry().blueprintsDir().resolve("test_lifecycle_a");
		Path bundleB = coreApi.blueprints().registry().blueprintsDir().resolve("test_lifecycle_b");
		Files.createDirectories(bundleA);
		Files.createDirectories(bundleB);

		UUID ownerId = UUID.randomUUID();
		UUID memberId = UUID.randomUUID();
		Island createdIsland = null;
		int gridX = 0;
		int gridZ = 0;

		try {
			// Synthesize two distinct starter structures in temporary test bundles (never touching 'default').
			BlockPos scratchOrigin = new BlockPos(8192, 120, 8192);
			for (int dx = 0; dx < 2; dx++) {
				for (int dz = 0; dz < 2; dz++) {
					overworld.setBlock(scratchOrigin.offset(dx, 0, dz), Blocks.STONE.defaultBlockState(), 18);
				}
			}
			StructureTemplate tmplA = new StructureTemplate();
			tmplA.fillFromWorld(overworld, scratchOrigin, new Vec3i(2, 1, 2), false, List.of());
			NbtIo.writeCompressed(tmplA.save(new CompoundTag()), bundleA.resolve("overworld.nbt"));

			for (int dx = 0; dx < 2; dx++) {
				for (int dz = 0; dz < 2; dz++) {
					overworld.setBlock(scratchOrigin.offset(dx, 0, dz), Blocks.OAK_PLANKS.defaultBlockState(), 18);
				}
			}
			StructureTemplate tmplB = new StructureTemplate();
			tmplB.fillFromWorld(overworld, scratchOrigin, new Vec3i(2, 1, 2), false, List.of());
			NbtIo.writeCompressed(tmplB.save(new CompoundTag()), bundleB.resolve("overworld.nbt"));

			for (int dx = 0; dx < 2; dx++) {
				for (int dz = 0; dz < 2; dz++) {
					overworld.setBlock(scratchOrigin.offset(dx, 0, dz), Blocks.AIR.defaultBlockState(), 818);
				}
			}

			coreApi.blueprints().registry().reload();

			// 1. Default create fails cleanly when no starter overworld.nbt is provided
			helper.assertTrue(
				!coreApi.lifecycle().createIsland(server, null, ownerId, null).success(),
				"createIsland should fail when default starter bundle has no overworld.nbt"
			);

			// 2. Create island using test_lifecycle_a
			OperationResult<Island> createResult = coreApi.lifecycle().createIsland(server, null, ownerId, "test_lifecycle_a");
			helper.assertTrue(createResult.success(), "Expected createIsland to succeed: " + createResult.message());
			createdIsland = createResult.value();
			gridX = createdIsland.gridX();
			gridZ = createdIsland.gridZ();
			for (int t = 0; t < 16 && createdIsland.isPasting(); t++) {
				coreApi.blueprints().tick(server);
			}
			helper.assertTrue(createdIsland.isOverworldReady(), "Expected overworld ready after paste");
			coreApi.lifecycle().resizeIsland(createdIsland.islandId(), 16);

			// 3. Team workflow: invite -> accept -> promote -> transfer -> coop disconnect clear
			helper.assertTrue(coreApi.team().inviteMember(createdIsland, ownerId, memberId).success(), "Expected invite to succeed");
			helper.assertTrue(coreApi.team().acceptInvite(memberId).success(), "Expected acceptInvite to succeed");
			helper.assertTrue(coreApi.team().promoteMember(createdIsland, ownerId, memberId, false).success(), "Expected promote to succeed");
			helper.assertTrue(coreApi.team().transferOwnership(createdIsland, ownerId, memberId, false).success(), "Expected transfer to succeed");
			helper.assertTrue(createdIsland.ownerId().equals(memberId), "Expected new ownerId after transfer");

			UUID coopId = UUID.randomUUID();
			helper.assertTrue(coreApi.team().addCoop(createdIsland, memberId, coopId, false).success(), "Expected addCoop to succeed");
			coreApi.team().onPlayerDisconnect(server, memberId);
			helper.assertTrue(createdIsland.roleOf(coopId) == IslandRole.VISITOR, "Expected COOP cleared on owner disconnect");

			// 4. Reset island to test_lifecycle_b: drains ChunkCleanupQueue -> pastes bundleB
			OperationResult<Island> resetResult = coreApi.lifecycle().resetIsland(server, memberId, createdIsland.islandId(), "test_lifecycle_b", false);
			helper.assertTrue(resetResult.success(), "Expected resetIsland to succeed: " + resetResult.message());
			for (int t = 0; t < 64 && createdIsland.isPasting(); t++) {
				coreApi.blueprints().tick(server);
			}
			helper.assertTrue(!createdIsland.isPasting() && createdIsland.isOverworldReady(), "Expected reset paste completion");
			helper.assertTrue(
				overworld.getBlockState(new BlockPos(createdIsland.centerX(), coreApi.coreIslands().config().island.defaultSpawnY, createdIsland.centerZ())).is(Blocks.OAK_PLANKS),
				"Expected OAK_PLANKS pasted after reset to test_lifecycle_b"
			);

			// 5. Delete island cleanly
			OperationResult<Void> deleteResult = coreApi.lifecycle().deleteIsland(
				server,
				createdIsland.islandId(),
				memberId,
				IslandPreDeleteEvent.DeleteReason.PLAYER_DELETE
			);
			helper.assertTrue(deleteResult.success(), "Expected deleteIsland to succeed");
			for (int t = 0; t < 64 && !islands.grid().pendingCleanups().isEmpty(); t++) {
				coreApi.blueprints().tick(server);
			}
			helper.assertTrue(islands.grid().pendingCleanups().isEmpty(), "Expected cleanup queue drained after deleteIsland");
			helper.assertTrue(
				overworld.getBlockState(new BlockPos(createdIsland.centerX(), coreApi.coreIslands().config().island.defaultSpawnY, createdIsland.centerZ())).isAir(),
				"Expected reset blocks cleared after deleteIsland cleanup"
			);
		} finally {
			if (createdIsland != null) {
				GameTestSupport.cleanupTestIsland(coreApi, islands, createdIsland.islandId(), gridX, gridZ);
			}
			GameTestSupport.deleteDirectoryRecursively(bundleA);
			GameTestSupport.deleteDirectoryRecursively(bundleB);
			coreApi.blueprints().registry().reload();
		}

		helper.succeed();
	}

	@GameTest
	public void commandsPermissionsAndPlayerProtectionsWork(GameTestHelper helper) throws Exception {
		OgskyCoreApiImpl coreApi = (OgskyCoreApiImpl) OgskyCore.api();
		CoreIslandService islands = coreApi.coreIslands();
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel ogOverworld = server.getLevel(OgskyDimensions.OVERWORLD);
		helper.assertTrue(ogOverworld != null, "Expected ogsky:overworld to be loaded");

		Path cmdBundleDir = coreApi.blueprints().registry().blueprintsDir().resolve("test_cmd");
		Files.createDirectories(cmdBundleDir);

		UUID ownerId = UUID.randomUUID();
		UUID visitorId = UUID.randomUUID();
		ServerPlayer owner = GameTestSupport.createOnlineMockPlayer(server, ogOverworld, ownerId, "ogsky_owner", GameType.SURVIVAL);
		ServerPlayer visitor = GameTestSupport.createOnlineMockPlayer(server, ogOverworld, visitorId, "ogsky_visitor", GameType.SURVIVAL);
		Island createdIsland = null;
		Island visitorIsland = null;
		int gridX = 0;
		int gridZ = 0;

		List<UUID> enteredIslands = new ArrayList<>();
		List<UUID> exitedIslands = new ArrayList<>();
		var enterSub = coreApi.events().subscribe(IslandEnterEvent.class, event -> {
			if (event.playerId().equals(visitorId)) {
				enteredIslands.add(event.islandId());
			}
		});
		var exitSub = coreApi.events().subscribe(IslandExitEvent.class, event -> {
			if (event.playerId().equals(visitorId)) {
				exitedIslands.add(event.islandId());
			}
		});

		try {
			// Prepare temporary test_cmd bundle (default remains empty and untouched for user starter setup).
			BlockPos scratchOrigin = new BlockPos(8400, 120, 8400);
			for (int dx = 0; dx < 2; dx++) {
				for (int dz = 0; dz < 2; dz++) {
					ogOverworld.setBlock(scratchOrigin.offset(dx, 0, dz), Blocks.STONE.defaultBlockState(), 18);
				}
			}
			StructureTemplate tmpl = new StructureTemplate();
			tmpl.fillFromWorld(ogOverworld, scratchOrigin, new Vec3i(2, 1, 2), false, List.of());
			NbtIo.writeCompressed(tmpl.save(new CompoundTag()), cmdBundleDir.resolve("overworld.nbt"));
			for (int dx = 0; dx < 2; dx++) {
				for (int dz = 0; dz < 2; dz++) {
					ogOverworld.setBlock(scratchOrigin.offset(dx, 0, dz), Blocks.AIR.defaultBlockState(), 818);
				}
			}
			coreApi.blueprints().registry().reload();

			// Non-OP player cannot execute /isadmin commands
			coreApi.lifecycle().setWorldSetting(BuiltinFlags.TNT_DAMAGE, false);
			server.getCommands().performPrefixedCommand(visitor.createCommandSourceStack(), "/isadmin worldflag tnt_damage true");
			helper.assertTrue(
				!islands.isWorldSettingEnabled(BuiltinFlags.TNT_DAMAGE),
				"Non-OP player must not mutate world flags via /isadmin"
			);

			// /is create test_cmd
			server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "/is create test_cmd");
			createdIsland = islands.grid().findPrimaryIsland(ownerId).orElse(null);
			helper.assertTrue(createdIsland != null, "Expected /is create to register island");
			gridX = createdIsland.gridX();
			gridZ = createdIsland.gridZ();
			for (int t = 0; t < 16 && createdIsland.isPasting(); t++) {
				coreApi.blueprints().tick(server);
			}
			helper.assertTrue(createdIsland.isOverworldReady(), "Expected overworld paste completion");

			int cx = createdIsland.centerX();
			int cy = coreApi.coreIslands().config().island.defaultSpawnY;
			int cz = createdIsland.centerZ();

			// Place test stone, chest, hopper, farmland inside owner's island
			BlockPos stonePos = new BlockPos(cx, cy, cz);
			BlockPos chestPos = new BlockPos(cx + 1, cy + 1, cz);
			BlockPos hopperPos = new BlockPos(cx, cy + 1, cz + 1);
			BlockPos farmPos = new BlockPos(cx - 1, cy + 1, cz);
			ogOverworld.setBlock(stonePos, Blocks.STONE.defaultBlockState(), 18);
			ogOverworld.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 18);
			ogOverworld.setBlock(hopperPos, Blocks.HOPPER.defaultBlockState(), 18);
			ogOverworld.setBlock(farmPos, Blocks.FARMLAND.defaultBlockState(), 18);

			// Visitor cannot break stone or open chest, whereas owner can
			helper.assertTrue(
				!PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(ogOverworld, visitor, stonePos, ogOverworld.getBlockState(stonePos), null),
				"Visitor must be blocked from breaking island stone"
			);
			helper.assertTrue(
				PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(ogOverworld, owner, stonePos, ogOverworld.getBlockState(stonePos), null),
				"Owner must be allowed to break island stone"
			);
			InteractionResult chestUse = UseBlockCallback.EVENT.invoker().interact(
				visitor,
				ogOverworld,
				InteractionHand.MAIN_HAND,
				new BlockHitResult(Vec3.atCenterOf(chestPos), Direction.UP, chestPos, false)
			);
			helper.assertTrue(chestUse == InteractionResult.FAIL, "Visitor must be blocked from opening island chest");

			// Hopper check: raise hopper rank vs break_blocks
			server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "/is flag break_blocks coop");
			server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "/is flag break_hoppers member");
			coreApi.team().addCoop(createdIsland, ownerId, visitorId, false);
			helper.assertTrue(
				PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(ogOverworld, visitor, stonePos, ogOverworld.getBlockState(stonePos), null),
				"Coop visitor should break stone when break_blocks=coop"
			);
			helper.assertTrue(
				!PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(ogOverworld, visitor, hopperPos, ogOverworld.getBlockState(hopperPos), null),
				"Coop visitor must still be denied breaking hopper when break_hoppers=member"
			);
			coreApi.team().removeCoop(server, createdIsland, ownerId, visitorId, false);

			// PvP check: disabled by default, enabled when pvp_overworld=true
			visitor.teleport(new TeleportTransition(ogOverworld, new Vec3(cx + 2.5, cy + 1, cz + 0.5), Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.DO_NOTHING));
			owner.teleport(new TeleportTransition(ogOverworld, new Vec3(cx + 0.5, cy + 1, cz + 0.5), Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.DO_NOTHING));
			helper.assertTrue(
				AttackEntityCallback.EVENT.invoker().interact(visitor, ogOverworld, InteractionHand.MAIN_HAND, owner, null) == InteractionResult.FAIL,
				"PvP must be blocked while pvp_overworld is false"
			);
			server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "/is flag pvp_overworld true");
			helper.assertTrue(
				AttackEntityCallback.EVENT.invoker().interact(visitor, ogOverworld, InteractionHand.MAIN_HAND, owner, null) == InteractionResult.PASS,
				"PvP should be allowed once owner sets pvp_overworld=true"
			);

			// Drop & pickup mixins
			server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "/is flag drop_item member");
			server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "/is flag pickup_item member");
			visitor.getInventory().setItem(0, new ItemStack(Items.STICK, 1));
			visitor.getInventory().setSelectedSlot(0);
			visitor.drop(true);
			helper.assertTrue(!visitor.getInventory().getItem(0).isEmpty(), "Visitor drop must be cancelled when drop_item=member");

			ItemEntity droppedItem = new ItemEntity(ogOverworld, cx + 2.5, cy + 1, cz + 0.5, new ItemStack(Items.DIAMOND, 1));
			droppedItem.setNoPickUpDelay();
			ogOverworld.addFreshEntity(droppedItem);
			droppedItem.playerTouch(visitor);
			helper.assertTrue(droppedItem.isAlive() && visitor.getInventory().countItem(Items.DIAMOND) == 0, "Visitor pickup must be blocked when pickup_item=member");
			droppedItem.discard();

			// Farmland trample mixin
			((net.minecraft.world.level.block.FarmlandBlock) Blocks.FARMLAND).turnToBaseBlock(
				visitor,
				ogOverworld.getBlockState(farmPos),
				ogOverworld,
				farmPos
			);
			helper.assertTrue(ogOverworld.getBlockState(farmPos).is(Blocks.FARMLAND), "Visitor must not trample farmland when crop_trample=member");

			// Admin bypass via /isadmin bypass
			CommandSourceStack visitorAsAdminSource = server.createCommandSourceStack().withEntity(visitor);
			server.getCommands().performPrefixedCommand(visitorAsAdminSource, "/isadmin bypass");
			helper.assertTrue(
				PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(ogOverworld, visitor, hopperPos, ogOverworld.getBlockState(hopperPos), null),
				"Admin bypass toggle should permit visitor to break protected hopper"
			);
			server.getCommands().performPrefixedCommand(visitorAsAdminSource, "/isadmin bypass");
			helper.assertTrue(
				!PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(ogOverworld, visitor, hopperPos, ogOverworld.getBlockState(hopperPos), null),
				"Disabling admin bypass should re-enforce hopper protection"
			);

			// Boundary enter event + /is lock eviction when already inside + exit event
			ServerTickEvents.END_SERVER_TICK.invoker().onEndTick(server);
			helper.assertTrue(enteredIslands.contains(createdIsland.islandId()), "Expected IslandEnterEvent on entering island");
			server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "/is lock true");
			ServerTickEvents.END_SERVER_TICK.invoker().onEndTick(server);
			helper.assertTrue(!createdIsland.contains(visitor.getBlockX(), visitor.getBlockZ()) || visitor.level() != ogOverworld, "Expected locked island to evict visitor");
			helper.assertTrue(exitedIslands.contains(createdIsland.islandId()), "Expected IslandExitEvent after eviction");

			// External approach to locked island: create a second island for visitor first, then walk across owner's border from outside
			server.getCommands().performPrefixedCommand(visitor.createCommandSourceStack(), "/is create test_cmd");
			visitorIsland = islands.grid().findPrimaryIsland(visitorId).orElse(null);
			helper.assertTrue(visitorIsland != null, "Expected visitor primary island for pushback vs home verification");
			for (int t = 0; t < 16 && visitorIsland.isPasting(); t++) {
				coreApi.blueprints().tick(server);
			}
			double outsideX = createdIsland.minX() - 0.8D;
			visitor.teleport(new TeleportTransition(ogOverworld, new Vec3(outsideX, cy + 1.0D, cz + 0.5D), Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.DO_NOTHING));
			ServerTickEvents.END_SERVER_TICK.invoker().onEndTick(server); // records lastOutsidePos
			visitor.teleport(new TeleportTransition(ogOverworld, new Vec3(createdIsland.minX() + 0.4D, cy + 1.0D, cz + 0.5D), Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.DO_NOTHING));
			ServerTickEvents.END_SERVER_TICK.invoker().onEndTick(server); // triggers boundary pushback
			helper.assertTrue(
				!createdIsland.contains(visitor.getBlockX(), visitor.getBlockZ()),
				"Expected external approach to push visitor outside locked island boundary"
			);
			helper.assertTrue(
				!visitorIsland.contains(visitor.getBlockX(), visitor.getBlockZ())
					&& Math.abs(visitor.getX() - outsideX) < 3.0D,
				"Expected boundary pushback to keep visitor near approached border rather than teleporting to visitor's own island"
			);
			helper.assertTrue(
				visitor.getDeltaMovement().x < 0.0D,
				"Expected outward West knockback when stopped at West border, got " + visitor.getDeltaMovement()
			);
			// Clean up visitor's secondary test island cleanly before continuing
			int visGridX = visitorIsland.gridX();
			int visGridZ = visitorIsland.gridZ();
			UUID visIslandId = visitorIsland.islandId();
			int visMinX = visitorIsland.minX();
			int visMaxX = visitorIsland.maxX();
			int visMinZ = visitorIsland.minZ();
			int visMaxZ = visitorIsland.maxZ();
			coreApi.lifecycle().deleteIsland(null, visIslandId, visitorId, IslandPreDeleteEvent.DeleteReason.ADMIN);
			GameTestSupport.clearLoadedBounds(server, visMinX, visMaxX, visMinZ, visMaxZ);
			GameTestSupport.cleanupTestIsland(coreApi, islands, visIslandId, visGridX, visGridZ);
			visitorIsland = null;

			// Void rescue
			server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "/is flag pvp_overworld false");
			owner.teleport(new TeleportTransition(ogOverworld, new Vec3(cx + 0.5, ogOverworld.getMinY() - 5.0, cz + 0.5), Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.DO_NOTHING));
			ServerTickEvents.END_SERVER_TICK.invoker().onEndTick(server);
			helper.assertTrue(owner.getY() >= ogOverworld.getMinY(), "Expected VOID_TELEPORT to rescue falling owner");

			// /isadmin setrange & delete
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "/isadmin setrange ogsky_owner 64");
			helper.assertTrue(createdIsland.halfSize() == 64, "Expected /isadmin setrange to resize island halfSize to 64");
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "/isadmin setrange ogsky_owner 16");
			helper.assertTrue(createdIsland.halfSize() == 16, "Expected /isadmin setrange to shrink island halfSize to 16");
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "/isadmin delete ogsky_owner");
			helper.assertTrue(islands.grid().findPrimaryIsland(ownerId).isEmpty(), "Expected /isadmin delete to unregister island");
			for (int t = 0; t < 512 && !islands.grid().pendingCleanups().isEmpty(); t++) {
				coreApi.blueprints().tick(server);
			}
			helper.assertTrue(islands.grid().pendingCleanups().isEmpty(), "Expected cleanup queue drained after /isadmin delete");
			helper.assertTrue(ogOverworld.getBlockState(stonePos).isAir(), "Expected island blocks cleared after /isadmin delete cleanup");
		} finally {
			enterSub.unsubscribe();
			exitSub.unsubscribe();
			server.getPlayerList().remove(owner);
			server.getPlayerList().remove(visitor);
			coreApi.protectionListeners().onPlayerDisconnect(ownerId);
			coreApi.protectionListeners().onPlayerDisconnect(visitorId);
			if (visitorIsland != null) {
				GameTestSupport.cleanupTestIsland(coreApi, islands, visitorIsland.islandId(), visitorIsland.gridX(), visitorIsland.gridZ());
			}
			if (createdIsland != null) {
				GameTestSupport.cleanupTestIsland(coreApi, islands, createdIsland.islandId(), gridX, gridZ);
				GameTestSupport.clearBlockPosIfLoaded(ogOverworld, new BlockPos(createdIsland.centerX(), coreApi.coreIslands().config().island.defaultSpawnY, createdIsland.centerZ()));
			}
			coreApi.lifecycle().setWorldSetting(BuiltinFlags.TNT_DAMAGE, false);
			coreApi.lifecycle().setWorldSetting(BuiltinFlags.CHEST_DAMAGE, false);
			GameTestSupport.deleteDirectoryRecursively(cmdBundleDir);
			coreApi.blueprints().registry().reload();
		}

		helper.succeed();
	}

	@GameTest
	public void worldSettingsExplosionsLiquidPistonAndOfflineRedstoneWork(GameTestHelper helper) {
		OgskyCoreApiImpl coreApi = (OgskyCoreApiImpl) OgskyCore.api();
		CoreIslandService islands = coreApi.coreIslands();
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel ogOverworld = server.getLevel(OgskyDimensions.OVERWORLD);
		helper.assertTrue(ogOverworld != null, "Expected ogsky:overworld to be loaded");

		long cell = islands.grid().allocateNextCell();
		int gridX = PackedGridMap.unpackX(cell);
		int gridZ = PackedGridMap.unpackZ(cell);
		int cxCell = islands.grid().centerCoordinate(gridX);
		int czCell = islands.grid().centerCoordinate(gridZ);

		UUID ownerId = UUID.randomUUID();
		Island island = new Island(
			UUID.randomUUID(),
			ownerId,
			gridX,
			gridZ,
			cxCell,
			czCell,
			(short) 20,
			"test_world_flags",
			Instant.now(),
			coreApi.flags(),
			coreApi.config()
		);
		islands.grid().register(island);
		int previousFireRadius = ogOverworld.getGameRules().get(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER);

		try {
			int cx = island.centerX();
			int cy = 80;
			int cz = island.centerZ();

			// Enable vanilla global fire spread so FireSpreadMixin is solely responsible for blocking/allowing.
			ogOverworld.getGameRules().set(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, -1, server);

			// 1. TNT & Chest damage protections
			BlockPos stonePos = new BlockPos(cx, cy, cz);
			BlockPos chestPos = new BlockPos(cx + 1, cy, cz);
			ogOverworld.setBlock(stonePos, Blocks.STONE.defaultBlockState(), 18);
			ogOverworld.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 18);

			PrimedTnt tnt = EntityTypes.TNT.create(ogOverworld, EntitySpawnReason.COMMAND);
			helper.assertTrue(tnt != null, "Expected TNT entity");
			coreApi.lifecycle().setWorldSetting(BuiltinFlags.TNT_DAMAGE, false);
			coreApi.lifecycle().setWorldSetting(BuiltinFlags.CHEST_DAMAGE, false);
			new ServerExplosion(
				ogOverworld,
				tnt,
				null,
				null,
				new Vec3(cx + 0.5, cy + 0.5, cz + 0.5),
				3.0F,
				false,
				Explosion.BlockInteraction.DESTROY_WITH_DECAY
			).explode();
			helper.assertTrue(ogOverworld.getBlockState(stonePos).is(Blocks.STONE), "TNT_DAMAGE=false must keep island stone intact");
			helper.assertTrue(ogOverworld.getBlockState(chestPos).is(Blocks.CHEST), "TNT_DAMAGE=false must keep chest intact");

			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "/isadmin worldflag tnt_damage true");
			new ServerExplosion(
				ogOverworld,
				tnt,
				null,
				null,
				new Vec3(cx + 0.5, cy + 0.5, cz + 0.5),
				3.0F,
				false,
				Explosion.BlockInteraction.DESTROY_WITH_DECAY
			).explode();
			helper.assertTrue(!ogOverworld.getBlockState(stonePos).is(Blocks.STONE), "TNT_DAMAGE=true should break stone");
			helper.assertTrue(ogOverworld.getBlockState(chestPos).is(Blocks.CHEST), "CHEST_DAMAGE=false must still shield chest from TNT explosion");
			tnt.discard();

			// 2. Liquid spread outside halfSize perimeter
			int maxX = island.maxX();
			BlockPos waterSrc = new BlockPos(maxX, cy, cz);
			BlockPos outsideWater = new BlockPos(maxX + 1, cy, cz);
			ogOverworld.setBlock(waterSrc.below(), Blocks.STONE.defaultBlockState(), 18);
			ogOverworld.setBlock(outsideWater.below(), Blocks.STONE.defaultBlockState(), 18);
			ogOverworld.setBlock(waterSrc, Blocks.WATER.defaultBlockState(), 18);
			ogOverworld.setBlock(outsideWater, Blocks.AIR.defaultBlockState(), 18);
			coreApi.lifecycle().setWorldSetting(BuiltinFlags.LIQUID_FLOW_OUTSIDE_BOUNDS, false);
			ogOverworld.getFluidState(waterSrc).tick(ogOverworld, waterSrc, ogOverworld.getBlockState(waterSrc));
			helper.assertTrue(ogOverworld.getFluidState(outsideWater).isEmpty(), "Water must not cross outside island maxX perimeter");

			// 3. Piston cross-border push guard
			BlockPos pistonPos = new BlockPos(maxX - 1, cy + 2, cz);
			BlockPos pushTarget = new BlockPos(maxX, cy + 2, cz);
			BlockPos outsideDest = new BlockPos(maxX + 1, cy + 2, cz);
			ogOverworld.setBlock(pushTarget, Blocks.COBBLESTONE.defaultBlockState(), 18);
			ogOverworld.setBlock(outsideDest, Blocks.AIR.defaultBlockState(), 18);
			ogOverworld.setBlock(pistonPos, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST), 18);
			coreApi.lifecycle().setWorldSetting(BuiltinFlags.PISTON_OUTSIDE_BOUNDS, false);
			helper.assertTrue(
				!ProtectionResolver.active().canPistonMove(ogOverworld, pistonPos, Direction.EAST, true),
				"Piston must not push block across island boundary when PISTON_OUTSIDE_BOUNDS=false"
			);
			coreApi.lifecycle().setWorldSetting(BuiltinFlags.PISTON_OUTSIDE_BOUNDS, true);
			helper.assertTrue(
				ProtectionResolver.active().canPistonMove(ogOverworld, pistonPos, Direction.EAST, true),
				"Piston should be allowed when PISTON_OUTSIDE_BOUNDS=true"
			);

			// 4. Fire spread, leaf decay & offline redstone gates
			coreApi.lifecycle().setSettingFlag(server, ownerId, island, BuiltinFlags.FIRE_SPREAD, false, true);
			helper.assertTrue(!ogOverworld.canSpreadFireAround(stonePos), "canSpreadFireAround must be false when FIRE_SPREAD=false");
			coreApi.lifecycle().setSettingFlag(server, ownerId, island, BuiltinFlags.FIRE_SPREAD, true, true);
			helper.assertTrue(ogOverworld.canSpreadFireAround(stonePos), "canSpreadFireAround must become true when FIRE_SPREAD=true");

			BlockPos leafPos = new BlockPos(cx + 3, cy, cz);
			BlockState decayingLeaf = Blocks.OAK_LEAVES.defaultBlockState()
				.setValue(LeavesBlock.PERSISTENT, false)
				.setValue(LeavesBlock.DISTANCE, 7);
			ogOverworld.setBlock(leafPos, decayingLeaf, 18);
			coreApi.lifecycle().setSettingFlag(server, ownerId, island, BuiltinFlags.LEAF_DECAY, false, true);
			ogOverworld.getBlockState(leafPos).randomTick(ogOverworld, leafPos, ogOverworld.getRandom());
			helper.assertTrue(ogOverworld.getBlockState(leafPos).is(Blocks.OAK_LEAVES), "Leaves must not decay when LEAF_DECAY=false");

			coreApi.lifecycle().setSettingFlag(server, ownerId, island, BuiltinFlags.OFFLINE_REDSTONE, false, true);
			helper.assertTrue(
				!ProtectionResolver.active().isRedstoneTickAllowed(ogOverworld, stonePos, Blocks.REPEATER),
				"Redstone ticks must be suppressed when OFFLINE_REDSTONE=false and no island members are online"
			);
			coreApi.lifecycle().setSettingFlag(server, ownerId, island, BuiltinFlags.OFFLINE_REDSTONE, true, true);
			helper.assertTrue(
				ProtectionResolver.active().isRedstoneTickAllowed(ogOverworld, stonePos, Blocks.REPEATER),
				"Redstone ticks must resume when OFFLINE_REDSTONE=true"
			);
		} finally {
			ogOverworld.setBlock(new BlockPos(island.maxX() + 1, 79, island.centerZ()), Blocks.AIR.defaultBlockState(), 18);
			ogOverworld.setBlock(new BlockPos(island.maxX() + 1, 80, island.centerZ()), Blocks.AIR.defaultBlockState(), 18);
			ogOverworld.setBlock(new BlockPos(island.maxX() + 1, 82, island.centerZ()), Blocks.AIR.defaultBlockState(), 18);
			ogOverworld.getGameRules().set(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, previousFireRadius, server);
			coreApi.lifecycle().setWorldSetting(BuiltinFlags.TNT_DAMAGE, false);
			coreApi.lifecycle().setWorldSetting(BuiltinFlags.CHEST_DAMAGE, false);
			coreApi.lifecycle().setWorldSetting(BuiltinFlags.PISTON_OUTSIDE_BOUNDS, false);
			coreApi.lifecycle().setWorldSetting(BuiltinFlags.LIQUID_FLOW_OUTSIDE_BOUNDS, false);
			coreApi.lifecycle().deleteIsland(null, island.islandId(), ownerId, IslandPreDeleteEvent.DeleteReason.ADMIN);
			GameTestSupport.clearLoadedBounds(server, island.minX(), island.maxX(), island.minZ(), island.maxZ());
			GameTestSupport.cleanupTestIsland(coreApi, islands, island.islandId(), gridX, gridZ);
		}

		helper.succeed();
	}
}
