package me.ogsammaenr.ogsky.gametest;

import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.event.BlueprintPasteCompletedEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandDimensionInitEvent;
import me.ogsammaenr.ogsky.core.internal.OgskyCoreApiImpl;
import me.ogsammaenr.ogsky.core.internal.blueprint.CoreBlueprintService;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfig;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandService;
import me.ogsammaenr.ogsky.core.internal.island.Island;
import me.ogsammaenr.ogsky.core.internal.island.PackedGridMap;
import me.ogsammaenr.ogsky.core.internal.world.OgskyDimensions;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tick-budgeted blueprint pasting, lazy Nether/End initialization, anchor/sign processing, and chunk cleanup GameTests.
 */
public final class BlueprintGameTests {
	@GameTest
	public void tickBudgetedBlueprintPasteLazyDimensionsAndChunkCleanupWork(GameTestHelper helper) throws Exception {
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel overworld = server.getLevel(OgskyDimensions.OVERWORLD);
		ServerLevel nether = server.getLevel(OgskyDimensions.NETHER);
		ServerLevel end = server.getLevel(OgskyDimensions.END);
		helper.assertTrue(overworld != null && nether != null && end != null, "Expected all 3 ogsky dimensions");

		OgskyCoreApiImpl coreApi = (OgskyCoreApiImpl) OgskyCore.api();
		CoreIslandService islands = coreApi.coreIslands();

		// Core must never auto-generate a starter overworld.nbt file.
		Path configuredDefaultNbt = coreApi.blueprints().registry().blueprintsDir().resolve("default").resolve("overworld.nbt");
		helper.assertTrue(!Files.exists(configuredDefaultNbt), "Core must leave starter overworld.nbt creation to the user");

		Path tempBlueprints = Files.createTempDirectory("ogsky-bp-gametest");
		Path bundleDir = tempBlueprints.resolve("test_starter");
		Files.createDirectories(bundleDir);

		long cell = islands.grid().allocateNextCell();
		int gridX = PackedGridMap.unpackX(cell);
		int gridZ = PackedGridMap.unpackZ(cell);
		int centerX = islands.grid().centerCoordinate(gridX);
		int centerZ = islands.grid().centerCoordinate(gridZ);

		CoreConfig testConfig = CoreConfig.defaults();
		testConfig.island.initialHalfSize = 16;
		testConfig.blueprints.defaultBundle = "test_starter";
		testConfig.blueprints.blocksPerTick = 6;
		testConfig.blueprints.maxNanosPerTick = 50_000_000L;
		testConfig.blueprints.cleanupChunksPerTick = 4;

		Island island = new Island(
			UUID.randomUUID(),
			UUID.randomUUID(),
			gridX,
			gridZ,
			centerX,
			centerZ,
			(short) testConfig.island.initialHalfSize,
			"test_starter",
			Instant.now(),
			coreApi.flags(),
			testConfig
		);
		islands.grid().register(island);

		CoreBlueprintService testBlueprints = new CoreBlueprintService(
			testConfig,
			tempBlueprints,
			islands,
			coreApi.databases(),
			coreApi.events()
		);
		coreApi.portalRouter().setLazyDimensionHandler(testBlueprints);

		List<BlueprintPasteCompletedEvent> pasteEvents = new ArrayList<>();
		List<IslandDimensionInitEvent> initEvents = new ArrayList<>();
		coreApi.events().subscribe(BlueprintPasteCompletedEvent.class, pasteEvents::add);
		coreApi.events().subscribe(IslandDimensionInitEvent.class, initEvents::add);

		BlockPos scratchOrigin = new BlockPos(centerX + 4096, 120, centerZ + 4096);
		try {
			// Synthesize temporary overworld.nbt (3x3x3 -> 27 blocks including populated Chest)
			for (int dx = 0; dx < 3; dx++) {
				for (int dz = 0; dz < 3; dz++) {
					overworld.setBlock(scratchOrigin.offset(dx, 0, dz), Blocks.DIRT.defaultBlockState(), 18);
					overworld.setBlock(scratchOrigin.offset(dx, 1, dz), Blocks.GRASS_BLOCK.defaultBlockState(), 18);
					overworld.setBlock(scratchOrigin.offset(dx, 2, dz), Blocks.AIR.defaultBlockState(), 18);
				}
			}
			overworld.setBlock(scratchOrigin.offset(1, 2, 1), Blocks.CHEST.defaultBlockState(), 18);
			if (overworld.getBlockEntity(scratchOrigin.offset(1, 2, 1)) instanceof ChestBlockEntity chest) {
				chest.setItem(0, new ItemStack(Items.OAK_SAPLING, 1));
			}
			StructureTemplate owTemplate = new StructureTemplate();
			owTemplate.fillFromWorld(overworld, scratchOrigin, new Vec3i(3, 3, 3), false, List.of());
			NbtIo.writeCompressed(owTemplate.save(new CompoundTag()), bundleDir.resolve("overworld.nbt"));

			// Synthesize temporary nether.nbt with netherrack floor + prebuilt nether portal
			for (int dx = 0; dx < 3; dx++) {
				for (int dz = 0; dz < 3; dz++) {
					overworld.setBlock(scratchOrigin.offset(dx, 0, dz), Blocks.NETHERRACK.defaultBlockState(), 18);
					overworld.setBlock(scratchOrigin.offset(dx, 1, dz), Blocks.AIR.defaultBlockState(), 18);
					overworld.setBlock(scratchOrigin.offset(dx, 2, dz), Blocks.AIR.defaultBlockState(), 18);
				}
			}
			overworld.setBlock(scratchOrigin.offset(1, 1, 1), Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, Direction.Axis.X), 18);
			StructureTemplate netherTemplate = new StructureTemplate();
			netherTemplate.fillFromWorld(overworld, scratchOrigin, new Vec3i(3, 3, 3), false, List.of());
			NbtIo.writeCompressed(netherTemplate.save(new CompoundTag()), bundleDir.resolve("nether.nbt"));

			// Synthesize temporary end.nbt with end stone platform
			for (int dx = 0; dx < 3; dx++) {
				for (int dz = 0; dz < 3; dz++) {
					overworld.setBlock(scratchOrigin.offset(dx, 0, dz), Blocks.END_STONE.defaultBlockState(), 18);
					overworld.setBlock(scratchOrigin.offset(dx, 1, dz), Blocks.AIR.defaultBlockState(), 18);
					overworld.setBlock(scratchOrigin.offset(dx, 2, dz), Blocks.AIR.defaultBlockState(), 18);
				}
			}
			StructureTemplate endTemplate = new StructureTemplate();
			endTemplate.fillFromWorld(overworld, scratchOrigin, new Vec3i(3, 2, 3), false, List.of());
			NbtIo.writeCompressed(endTemplate.save(new CompoundTag()), bundleDir.resolve("end.nbt"));

			// Clear scratch zone back to air
			for (int dx = 0; dx < 3; dx++) {
				for (int dy = 0; dy < 3; dy++) {
					for (int dz = 0; dz < 3; dz++) {
						overworld.removeBlockEntity(scratchOrigin.offset(dx, dy, dz));
						overworld.setBlock(scratchOrigin.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 818);
					}
				}
			}

			Files.writeString(bundleDir.resolve("bundle.json"), """
				{
				  "id": "test_starter",
				  "displayName": "Test Starter",
				  "overworld": { "file": "overworld.nbt", "offsetY": -2 },
				  "nether": { "file": "nether.nbt", "offsetY": -1 },
				  "end": { "file": "end.nbt", "offsetY": -1 }
				}
				""");

			testBlueprints.onServerStarting();
			helper.assertTrue(testBlueprints.registry().find("test_starter").isPresent(), "Expected test_starter bundle");

			// Start tick-budgeted overworld paste (blocksPerTick = 6 on 27 blocks -> takes multiple ticks)
			boolean queued = testBlueprints.enqueueOverworldPaste(server, island, "test_starter", null);
			helper.assertTrue(queued, "Expected enqueueOverworldPaste to succeed");
			helper.assertTrue(island.isPasting(), "Expected STATE_PASTING bit set while paste queued");
			helper.assertTrue(!island.isOverworldReady(), "Overworld should not be marked ready before paste completes");

			testBlueprints.tick(server);
			helper.assertTrue(island.isPasting(), "Expected paste to span multiple ticks under blocksPerTick=6");
			helper.assertTrue(testBlueprints.paster().activeJobCount() == 1, "Expected active paste job after 1st tick");

			for (int t = 0; t < 20 && testBlueprints.paster().activeJobCount() > 0; t++) {
				testBlueprints.tick(server);
			}
			helper.assertTrue(testBlueprints.paster().activeJobCount() == 0, "Expected paste job to complete cleanly");
			helper.assertTrue(!island.isPasting(), "Expected STATE_PASTING cleared on completion");
			helper.assertTrue(island.isOverworldReady(), "Expected STATE_OVERWORLD_READY after paste completes");
			helper.assertTrue(
				pasteEvents.stream().anyMatch(e -> e.islandId().equals(island.islandId()) && e.bundleId().equals("test_starter")),
				"Expected BlueprintPasteCompletedEvent for overworld"
			);

			int spawnY = testConfig.island.defaultSpawnY;
			BlockPos chestPos = new BlockPos(centerX, spawnY, centerZ);
			helper.assertTrue(overworld.getBlockState(chestPos).is(Blocks.CHEST), "Expected chest pasted at center");
			helper.assertTrue(
				overworld.getBlockEntity(chestPos) instanceof ChestBlockEntity placedChest
					&& placedChest.getItem(0).is(Items.OAK_SAPLING),
				"Expected Chest BlockEntity inventory restored cleanly from NBT"
			);

			// Lazy Nether & End initialization via 1:1 PortalRouter
			BlockPos owNetherPortal = new BlockPos(centerX + 2, spawnY, centerZ);
			overworld.setBlock(owNetherPortal, Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, Direction.Axis.X), 18);
			ArmorStand traveler = new ArmorStand(EntityTypes.ARMOR_STAND, overworld);
			traveler.setPos(owNetherPortal.getX() + 0.5D, owNetherPortal.getY(), owNetherPortal.getZ() + 0.5D);

			NetherPortalBlock netherPortalBlock = (NetherPortalBlock) Blocks.NETHER_PORTAL;
			TeleportTransition toNether = netherPortalBlock.getPortalDestination(overworld, traveler, owNetherPortal);
			helper.assertTrue(toNether != null && toNether.newLevel().equals(nether), "Expected 1:1 transition into ogsky:nether");
			helper.assertTrue(island.isNetherReady(), "Expected STATE_NETHER_READY after first Nether portal transit");
			helper.assertTrue(
				nether.getBlockState(new BlockPos(centerX, spawnY - 1, centerZ)).is(Blocks.NETHERRACK),
				"Expected lazy nether.nbt blocks pasted on first portal crossing"
			);
			helper.assertTrue(
				initEvents.stream().anyMatch(e -> e.islandId().equals(island.islandId()) && e.dimensionId().equals(OgskyDimensions.NETHER.identifier())),
				"Expected IslandDimensionInitEvent for lazy nether paste"
			);

			BlockPos owEndPortal = new BlockPos(centerX - 2, spawnY, centerZ);
			overworld.setBlock(owEndPortal, Blocks.END_PORTAL.defaultBlockState(), 18);
			traveler.setPos(owEndPortal.getX() + 0.5D, owEndPortal.getY(), owEndPortal.getZ() + 0.5D);
			EndPortalBlock endPortalBlock = (EndPortalBlock) Blocks.END_PORTAL;
			TeleportTransition toEnd = endPortalBlock.getPortalDestination(overworld, traveler, owEndPortal);
			helper.assertTrue(toEnd != null && toEnd.newLevel().equals(end), "Expected 1:1 transition into ogsky:end");
			helper.assertTrue(island.isEndReady(), "Expected STATE_END_READY after first End portal transit");
			helper.assertTrue(
				end.getBlockState(new BlockPos(centerX, spawnY - 1, centerZ)).is(Blocks.END_STONE),
				"Expected lazy end.nbt blocks pasted on first portal crossing"
			);

			// Spawn stray entity inside island, then run tick-budgeted ChunkCleanupQueue
			ArmorStand stray = new ArmorStand(EntityTypes.ARMOR_STAND, overworld);
			stray.setPos(centerX + 0.5D, spawnY + 1.0D, centerZ + 0.5D);
			overworld.addFreshEntity(stray);

			islands.grid().unregister(island.islandId());
			testBlueprints.cleanupQueue().enqueue(gridX, gridZ, island.minX(), island.maxX(), island.minZ(), island.maxZ());
			helper.assertTrue(!islands.grid().pendingCleanups().isEmpty(), "Expected cleanup entry registered in IslandGrid");

			for (int t = 0; t < 32 && !islands.grid().pendingCleanups().isEmpty(); t++) {
				testBlueprints.tick(server);
			}
			helper.assertTrue(islands.grid().pendingCleanups().isEmpty(), "Expected ChunkCleanupQueue to finish clearing all island chunks");
			helper.assertTrue(overworld.getBlockState(chestPos).isAir(), "Expected pasted chest cleared cleanly");
			helper.assertTrue(overworld.getBlockEntity(chestPos) == null, "Expected chest BlockEntity removed cleanly");
			helper.assertTrue(nether.getBlockState(new BlockPos(centerX, spawnY - 1, centerZ)).isAir(), "Expected nether blocks cleared cleanly");
			helper.assertTrue(end.getBlockState(new BlockPos(centerX, spawnY - 1, centerZ)).isAir(), "Expected end blocks cleared cleanly");
			helper.assertTrue(!stray.isAlive(), "Expected non-player entity discarded during island chunk cleanup");
			helper.assertTrue(
				overworld.getEntitiesOfClass(
					net.minecraft.world.entity.Entity.class,
					new AABB(island.minX(), overworld.getMinY(), island.minZ(), island.maxX() + 1.0D, overworld.getMaxY() + 1.0D, island.maxZ() + 1.0D),
					e -> true
				).isEmpty(),
				"Expected zero leftover drops or entities inside cleared island bounds"
			);
		} finally {
			coreApi.portalRouter().setLazyDimensionHandler(coreApi.blueprints());
			GameTestSupport.cleanupTestIsland(coreApi, islands, island.islandId(), gridX, gridZ);
			GameTestSupport.deleteDirectoryRecursively(tempBlueprints);
		}

		helper.succeed();
	}

	@GameTest
	public void blueprintBedrockAnchorSpawnAndWelcomeSignsWork(GameTestHelper helper) throws Exception {
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel overworld = server.getLevel(OgskyDimensions.OVERWORLD);
		helper.assertTrue(overworld != null, "Expected ogsky:overworld dimension");

		OgskyCoreApiImpl coreApi = (OgskyCoreApiImpl) OgskyCore.api();
		CoreIslandService islands = coreApi.coreIslands();

		Path tempBlueprints = Files.createTempDirectory("ogsky-bp-anchor-gametest");
		Path bundleDir = tempBlueprints.resolve("anchor_starter");
		Files.createDirectories(bundleDir);

		long cell = islands.grid().allocateNextCell();
		int gridX = PackedGridMap.unpackX(cell);
		int gridZ = PackedGridMap.unpackZ(cell);
		int centerX = islands.grid().centerCoordinate(gridX);
		int centerZ = islands.grid().centerCoordinate(gridZ);

		CoreConfig testConfig = CoreConfig.defaults();
		testConfig.island.initialHalfSize = 16;
		testConfig.blueprints.defaultBundle = "anchor_starter";
		testConfig.blueprints.blocksPerTick = 128;
		testConfig.blueprints.maxNanosPerTick = 50_000_000L;
		testConfig.blueprints.cleanupChunksPerTick = 16;
		testConfig.blueprints.welcomeSignLines = List.of(
			"&aHos Geldin,",
			"&f{player}",
			"&7Ada: {bundle}",
			"&eIyi oyunlar!"
		);

		UUID ownerId = UUID.randomUUID();
		Island island = new Island(
			UUID.randomUUID(),
			ownerId,
			gridX,
			gridZ,
			centerX,
			centerZ,
			(short) testConfig.island.initialHalfSize,
			"anchor_starter",
			Instant.now(),
			coreApi.flags(),
			testConfig
		);
		islands.grid().register(island);

		CoreBlueprintService testBlueprints = new CoreBlueprintService(
			testConfig,
			tempBlueprints,
			islands,
			coreApi.databases(),
			coreApi.events()
		);

		BlockPos scratchOrigin = new BlockPos(centerX + 5120, 120, centerZ + 5120);
		ServerPlayer ownerPlayer = null;
		try {
			for (int dx = 0; dx < 5; dx++) {
				for (int dz = 0; dz < 5; dz++) {
					overworld.setBlock(scratchOrigin.offset(dx, 0, dz), Blocks.DIRT.defaultBlockState(), 18);
					overworld.setBlock(scratchOrigin.offset(dx, 1, dz), Blocks.GRASS_BLOCK.defaultBlockState(), 18);
					overworld.setBlock(scratchOrigin.offset(dx, 2, dz), Blocks.AIR.defaultBlockState(), 18);
				}
			}
			// Asymmetric bedrock anchor at relative (1, 1, 3)
			overworld.setBlock(scratchOrigin.offset(1, 1, 3), Blocks.BEDROCK.defaultBlockState(), 18);

			// Standing [SPAWN] sign at relative (3, 2, 1), rotation=12 (front facing EAST / yaw=-90)
			BlockPos scratchSpawnSign = scratchOrigin.offset(3, 2, 1);
			overworld.setBlock(
				scratchSpawnSign,
				Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 12),
				18
			);
			if (overworld.getBlockEntity(scratchSpawnSign) instanceof SignBlockEntity spawnSign) {
				SignText front = spawnSign.getText(SignTextSlot.FRONT).asMutable()
					.setLine(0, Component.literal("[SPAWN]"))
					.asImmutable();
				spawnSign.setText(front, SignTextSlot.FRONT);
			}

			// Oak planks support + glowing CYAN [WELCOME] wall sign at relative (1, 2, 2)
			overworld.setBlock(scratchOrigin.offset(0, 2, 2), Blocks.OAK_PLANKS.defaultBlockState(), 18);
			BlockPos scratchWelcomeSign = scratchOrigin.offset(1, 2, 2);
			overworld.setBlock(
				scratchWelcomeSign,
				Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, Direction.EAST),
				18
			);
			if (overworld.getBlockEntity(scratchWelcomeSign) instanceof SignBlockEntity welcomeSign) {
				SignText front = welcomeSign.getText(SignTextSlot.FRONT).asMutable()
					.setColor(DyeColor.CYAN)
					.setTextGlowing(true)
					.setLine(0, Component.literal("[WELCOME]"))
					.asImmutable();
				welcomeSign.setText(front, SignTextSlot.FRONT);
			}

			StructureTemplate captured = new StructureTemplate();
			captured.fillFromWorld(overworld, scratchOrigin, new Vec3i(5, 4, 5), false, List.of());
			Path nbtPath = bundleDir.resolve("overworld.nbt");
			NbtIo.writeCompressed(captured.save(new CompoundTag()), nbtPath);

			// Clear scratch zone
			for (int dx = 0; dx < 5; dx++) {
				for (int dy = 0; dy < 4; dy++) {
					for (int dz = 0; dz < 5; dz++) {
						overworld.removeBlockEntity(scratchOrigin.offset(dx, dy, dz));
						overworld.setBlock(scratchOrigin.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 818);
					}
				}
			}

			testBlueprints.onServerStarting(server);
			helper.assertTrue(
				testBlueprints.registry().find("anchor_starter").map(b -> b.hasOverworld()).orElse(false),
				"Expected anchor_starter compressed at server startup"
			);

			// Remove .nbt file on disk after startup compression to prove pasting reads exclusively from compressed RAM object.
			Files.deleteIfExists(nbtPath);

			ownerPlayer = GameTestSupport.createOnlineMockPlayer(server, overworld, ownerId, "BpOwner", GameType.SURVIVAL);
			boolean queued = testBlueprints.enqueueOverworldPaste(server, island, "anchor_starter", ownerPlayer.getScoreboardName(), null);
			helper.assertTrue(queued, "Expected enqueueOverworldPaste from compressed RAM blueprint to succeed");

			for (int t = 0; t < 16 && testBlueprints.paster().activeJobCount() > 0; t++) {
				testBlueprints.tick(server);
			}
			helper.assertTrue(testBlueprints.paster().activeJobCount() == 0 && island.isOverworldReady(), "Expected anchored paste completion");

			int defaultSpawnY = testConfig.island.defaultSpawnY;
			// Bedrock at relative (1, 1, 3) -> world (centerX, defaultSpawnY, centerZ)
			BlockPos bedrockWorldPos = new BlockPos(centerX, defaultSpawnY, centerZ);
			helper.assertTrue(
				overworld.getBlockState(bedrockWorldPos).is(Blocks.BEDROCK),
				"Expected bedrock from blueprint centered at exact island center (" + bedrockWorldPos + ")"
			);

			// origin = (centerX - 1, defaultSpawnY - 1, centerZ - 3)
			// [SPAWN] sign at relative (3, 2, 1) -> world (centerX + 2, defaultSpawnY + 1, centerZ - 2)
			BlockPos spawnWorldPos = new BlockPos(centerX + 2, defaultSpawnY + 1, centerZ - 2);
			helper.assertTrue(
				overworld.getBlockState(spawnWorldPos).isAir(),
				"[SPAWN] sign must not be pasted into the island"
			);
			helper.assertTrue(
				overworld.getBlockEntity(spawnWorldPos) == null,
				"[SPAWN] sign must not create a BlockEntity in the world"
			);
			helper.assertTrue(
				island.overworldSpawn().blockX(centerX) == spawnWorldPos.getX()
					&& island.overworldSpawn().blockY() == spawnWorldPos.getY()
					&& island.overworldSpawn().blockZ(centerZ) == spawnWorldPos.getZ()
					&& Math.abs(island.overworldSpawn().yaw() - (-90.0F)) < 2.0F,
				"Expected island overworld spawnpoint to match removed [SPAWN] sign with front-facing yaw (-90 East), got yaw="
					+ island.overworldSpawn().yaw()
			);

			coreApi.lifecycle().teleportHome(server, ownerPlayer, island, OgskyDimensions.OVERWORLD);
			helper.assertTrue(
				Math.abs(ownerPlayer.getX() - (spawnWorldPos.getX() + 0.5D)) < 0.01D
					&& Math.abs(ownerPlayer.getY() - spawnWorldPos.getY()) < 0.01D
					&& Math.abs(ownerPlayer.getZ() - (spawnWorldPos.getZ() + 0.5D)) < 0.01D
					&& Math.abs(ownerPlayer.getYRot() - (-90.0F)) < 2.0F,
				"Expected teleportHome to place player at [SPAWN] position facing sign front face"
			);

			// [WELCOME] wall sign at relative (1, 2, 2) -> world (centerX, defaultSpawnY + 1, centerZ - 1)
			BlockPos welcomeWorldPos = new BlockPos(centerX, defaultSpawnY + 1, centerZ - 1);
			helper.assertTrue(
				overworld.getBlockEntity(welcomeWorldPos) instanceof SignBlockEntity,
				"Expected [WELCOME] sign pasted at relative (1, 2, 2)"
			);
			SignBlockEntity pastedWelcome = (SignBlockEntity) overworld.getBlockEntity(welcomeWorldPos);
			SignText frontText = pastedWelcome.getText(SignTextSlot.FRONT);
			helper.assertTrue(frontText.getColor() == DyeColor.CYAN, "Expected [WELCOME] sign dye color preserved");
			helper.assertTrue(frontText.hasGlowingText(), "Expected [WELCOME] sign glowing text preserved");
			List<Component> lines = frontText.getMessages(false);
			helper.assertTrue(lines.get(0).getString().equals("§aHos Geldin,"), "Expected line 0 rendered, got: " + lines.get(0).getString());
			helper.assertTrue(lines.get(1).getString().equals("§fBpOwner"), "Expected {player} substituted, got: " + lines.get(1).getString());
			helper.assertTrue(lines.get(2).getString().equals("§7Ada: Anchor_starter"), "Expected {bundle} substituted, got: " + lines.get(2).getString());
			helper.assertTrue(lines.get(3).getString().equals("§eIyi oyunlar!"), "Expected line 3 rendered, got: " + lines.get(3).getString());
		} finally {
			if (ownerPlayer != null) {
				server.getPlayerList().remove(ownerPlayer);
			}
			GameTestSupport.clearLoadedBounds(server, island.minX(), island.maxX(), island.minZ(), island.maxZ());
			GameTestSupport.cleanupTestIsland(coreApi, islands, island.islandId(), gridX, gridZ);
			GameTestSupport.deleteDirectoryRecursively(tempBlueprints);
		}

		helper.succeed();
	}
}
