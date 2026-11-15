package me.ogsammaenr.ogsky.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.OgskyCoreApi;
import me.ogsammaenr.ogsky.core.api.db.ModuleDatabase;
import me.ogsammaenr.ogsky.core.api.event.BlueprintPasteCompletedEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandDimensionInitEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandEnterEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandExitEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandPreDeleteEvent;
import me.ogsammaenr.ogsky.core.api.flag.FlagType;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.internal.OgskyCoreApiImpl;
import me.ogsammaenr.ogsky.core.internal.blueprint.CoreBlueprintService;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfig;
import me.ogsammaenr.ogsky.core.internal.flag.BuiltinFlags;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandRepository;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandService;
import me.ogsammaenr.ogsky.core.internal.island.Island;
import me.ogsammaenr.ogsky.core.internal.island.OperationResult;
import me.ogsammaenr.ogsky.core.internal.island.PackedGridMap;
import me.ogsammaenr.ogsky.core.internal.protection.ProtectionResolver;
import me.ogsammaenr.ogsky.core.internal.world.OgskyDimensions;
import me.ogsammaenr.ogsky.core.internal.world.VoidChunkGenerator;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.PistonType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Headless dedicated-server GameTests verifying active Ogsky modules and SQLite wiring inside a live MC 26.3 server.
 */
public final class OgskyBundleGameTests {
	@GameTest
	public void coreSqliteDatabaseAndActiveModulesLoadCleanly(GameTestHelper helper) {
		OgskyCoreApi api = OgskyCore.api();
		ModuleDatabase coreDb = api.databases().require("core");
		helper.assertTrue(Files.exists(coreDb.databasePath()), "Expected core.db to exist inside world directory");

		Set<String> coreTables = coreDb.queryBlocking(conn -> {
			Set<String> names = new HashSet<>();
			try (Statement stmt = conn.createStatement();
				 ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
				while (rs.next()) {
					names.add(rs.getString(1));
				}
			}
			return names;
		});
		for (String expected : Set.of(
			"islands",
			"world_meta",
			"island_members",
			"island_bans",
			"island_protection_flags",
			"island_setting_flags",
			"world_settings",
			"grid_cleanup_queue"
		)) {
			helper.assertTrue(coreTables.contains(expected), "Expected table '" + expected + "' in core.db");
		}

		helper.assertTrue(api.flags().isFrozen(), "Expected FlagRegistry to be frozen after startup");
		helper.assertTrue(api.flags().count(FlagType.PROTECTION) >= 34, "Expected built-in PROTECTION flags");
		helper.assertTrue(api.flags().count(FlagType.SETTING) >= 10, "Expected built-in SETTING flags");
		helper.assertTrue(api.flags().count(FlagType.WORLD_SETTING) >= 9, "Expected built-in WORLD_SETTING flags");
		helper.assertTrue(api.islands().lockedCellSize() >= 32, "Expected valid grid cellSize on IslandService");
		helper.assertTrue(api.commands() != null, "Expected IslandCommandRegistry on OgskyCoreApi");

		if (FabricLoader.getInstance().isModLoaded("ogsky-level")) {
			ModuleDatabase levelDb = api.databases().require("level");
			helper.assertTrue(Files.exists(levelDb.databasePath()), "Expected level.db when ogsky-level is bundled");
			helper.assertTrue(
				api.actions().find(OgskyCore.id("island_level")).isPresent(),
				"Expected ogsky:island_level action to be registered when ogsky-level is loaded"
			);
		}

		helper.succeed();
	}

	@GameTest
	public void voidDimensionsAndOneToOnePortalRoutingWork(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel overworld = server.getLevel(OgskyDimensions.OVERWORLD);
		ServerLevel nether = server.getLevel(OgskyDimensions.NETHER);
		ServerLevel end = server.getLevel(OgskyDimensions.END);

		helper.assertTrue(overworld != null, "Expected ogsky:overworld dimension to load");
		helper.assertTrue(nether != null, "Expected ogsky:nether dimension to load");
		helper.assertTrue(end != null, "Expected ogsky:end dimension to load");

		helper.assertTrue(overworld.getChunkSource().getGenerator() instanceof VoidChunkGenerator, "ogsky:overworld must use VoidChunkGenerator");
		helper.assertTrue(nether.getChunkSource().getGenerator() instanceof VoidChunkGenerator, "ogsky:nether must use VoidChunkGenerator");
		helper.assertTrue(end.getChunkSource().getGenerator() instanceof VoidChunkGenerator, "ogsky:end must use VoidChunkGenerator");

		helper.assertTrue(overworld.dimensionType().coordinateScale() == 1.0D, "ogsky:overworld coordinateScale must be 1.0");
		helper.assertTrue(nether.dimensionType().coordinateScale() == 1.0D, "ogsky:nether coordinateScale must be 1.0");
		helper.assertTrue(end.dimensionType().coordinateScale() == 1.0D, "ogsky:end coordinateScale must be 1.0");
		helper.assertTrue(!end.dimensionType().hasEnderDragonFight(), "ogsky:end must disable EnderDragonFight");
		helper.assertTrue(end.getDragonFight() == null, "ogsky:end ServerLevel dragonFight must be null");

		// Verify generated chunks stay pure void/air in all 3 dimensions
		helper.assertTrue(overworld.getBlockState(new BlockPos(512, 64, 512)).isAir(), "Expected void air in ogsky:overworld");
		helper.assertTrue(overworld.getBlockState(new BlockPos(512, -60, 512)).isAir(), "Expected void air below Y=0 in ogsky:overworld");
		helper.assertTrue(nether.getBlockState(new BlockPos(512, 64, 512)).isAir(), "Expected void air in ogsky:nether");
		helper.assertTrue(end.getBlockState(new BlockPos(0, 64, 0)).isAir(), "Expected void air at origin in ogsky:end");

		OgskyCoreApiImpl coreApi = (OgskyCoreApiImpl) OgskyCore.api();
		CoreIslandService islands = coreApi.coreIslands();
		long cell = islands.grid().allocateNextCell();
		int gridX = PackedGridMap.unpackX(cell);
		int gridZ = PackedGridMap.unpackZ(cell);
		int centerX = islands.grid().centerCoordinate(gridX);
		int centerZ = islands.grid().centerCoordinate(gridZ);
		CoreConfig config = islands.config();
		Island island = new Island(
			UUID.randomUUID(),
			UUID.randomUUID(),
			gridX,
			gridZ,
			centerX,
			centerZ,
			(short) config.island.initialHalfSize,
			config.blueprints.defaultBundle,
			Instant.now(),
			coreApi.flags(),
			config
		);
		islands.grid().register(island);

		try {
			ArmorStand stand = new ArmorStand(EntityTypes.ARMOR_STAND, overworld);
			BlockPos wildernessPos = new BlockPos(centerX + island.halfSize() + 64, 72, centerZ);
			stand.setPos(wildernessPos.getX() + 0.5D, wildernessPos.getY(), wildernessPos.getZ() + 0.5D);
			NetherPortalBlock netherPortalBlock = (NetherPortalBlock) Blocks.NETHER_PORTAL;
			EndPortalBlock endPortalBlock = (EndPortalBlock) Blocks.END_PORTAL;

			// Wilderness portal usage must be denied cleanly
			helper.assertTrue(
				netherPortalBlock.getPortalDestination(overworld, stand, wildernessPos) == null,
				"Wilderness nether portal usage must be rejected in ogsky dimensions"
			);
			helper.assertTrue(
				endPortalBlock.getPortalDestination(overworld, stand, wildernessPos) == null,
				"Wilderness end portal usage must be rejected in ogsky dimensions"
			);

			// 1:1 Overworld -> Nether portal routing & return within island column
			BlockPos owPortalPos = new BlockPos(centerX + 8, 74, centerZ - 6);
			overworld.setBlock(owPortalPos, Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, Direction.Axis.X), 18);
			stand.setPos(owPortalPos.getX() + 0.5D, owPortalPos.getY(), owPortalPos.getZ() + 0.5D);
			TeleportTransition toNether = netherPortalBlock.getPortalDestination(overworld, stand, owPortalPos);
			helper.assertTrue(toNether != null, "Expected non-null 1:1 Nether portal transition inside island");
			helper.assertTrue(toNether.newLevel().equals(nether), "Expected Overworld NetherPortal to target ogsky:nether");
			helper.assertTrue(
				island.contains((int) Math.floor(toNether.position().x), (int) Math.floor(toNether.position().z)),
				"Expected generated Nether portal exit inside island bounds at 1:1 coordinates"
			);
			helper.assertTrue(
				Math.abs(toNether.position().x - owPortalPos.getX()) <= 16.0D
					&& Math.abs(toNether.position().z - owPortalPos.getZ()) <= 16.0D,
				"Expected 1:1 Nether coordinates near entry rather than 8x scaled"
			);

			ArmorStand netherStand = new ArmorStand(EntityTypes.ARMOR_STAND, nether);
			BlockPos netherPortalPos = BlockPos.containing(toNether.position());
			netherStand.setPos(toNether.position().x, toNether.position().y, toNether.position().z);
			TeleportTransition backToOverworld = netherPortalBlock.getPortalDestination(nether, netherStand, netherPortalPos);
			helper.assertTrue(backToOverworld != null, "Expected non-null return portal transition from ogsky:nether");
			helper.assertTrue(backToOverworld.newLevel().equals(overworld), "Expected Nether portal return to target ogsky:overworld");
			helper.assertTrue(
				Math.abs(backToOverworld.position().x - (owPortalPos.getX() + 0.5D)) <= 3.0D
					&& Math.abs(backToOverworld.position().z - (owPortalPos.getZ() + 0.5D)) <= 3.0D,
				"Expected Nether -> Overworld return to lock onto 1:1 island portal"
			);

			// 1:1 Overworld -> End -> Overworld routing + fallback landing platform
			BlockPos endPortalPos = new BlockPos(centerX - 5, 72, centerZ + 5);
			overworld.setBlock(endPortalPos, Blocks.END_PORTAL.defaultBlockState(), 18);
			stand.setPos(endPortalPos.getX() + 0.5D, endPortalPos.getY(), endPortalPos.getZ() + 0.5D);
			TeleportTransition toEnd = endPortalBlock.getPortalDestination(overworld, stand, endPortalPos);
			helper.assertTrue(toEnd != null, "Expected non-null End portal transition inside island");
			helper.assertTrue(toEnd.newLevel().equals(end), "Expected Overworld EndPortal to target ogsky:end");
			BlockPos endLandingBlock = BlockPos.containing(toEnd.position());
			helper.assertTrue(
				island.contains(endLandingBlock.getX(), endLandingBlock.getZ()),
				"Expected End landing platform inside island bounds"
			);
			helper.assertTrue(
				end.getBlockState(endLandingBlock.below()).is(Blocks.OBSIDIAN),
				"Expected safe fallback obsidian End platform when end.nbt is absent"
			);

			ArmorStand endStand = new ArmorStand(EntityTypes.ARMOR_STAND, end);
			endStand.setPos(toEnd.position().x, toEnd.position().y, toEnd.position().z);
			TeleportTransition endReturn = endPortalBlock.getPortalDestination(end, endStand, endLandingBlock);
			helper.assertTrue(endReturn != null, "Expected non-null return transition from ogsky:end");
			helper.assertTrue(endReturn.newLevel().equals(overworld), "Expected End portal in ogsky:end to return to ogsky:overworld");
			helper.assertTrue(
				Math.abs(endReturn.position().x - island.overworldSpawn().worldX(centerX)) < 1.0D
					&& Math.abs(endReturn.position().z - island.overworldSpawn().worldZ(centerZ)) < 1.0D,
				"Expected End -> Overworld portal return to target island overworld spawn"
			);
		} finally {
			cleanupTestIsland(coreApi, islands, island.islandId(), gridX, gridZ);
		}

		helper.succeed();
	}

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
		testConfig.blueprints.defaultBundle = "test_starter";
		testConfig.blueprints.blocksPerTick = 6;
		testConfig.blueprints.maxNanosPerTick = 50_000_000L;
		testConfig.blueprints.cleanupChunksPerTick = 16;

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
			cleanupTestIsland(coreApi, islands, island.islandId(), gridX, gridZ);
			deleteDirectoryRecursively(tempBlueprints);
		}

		helper.succeed();
	}

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
				cleanupTestIsland(coreApi, islands, createdIsland.islandId(), gridX, gridZ);
			}
			deleteDirectoryRecursively(bundleA);
			deleteDirectoryRecursively(bundleB);
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
		ServerPlayer owner = createOnlineMockPlayer(server, ogOverworld, ownerId, "ogsky_owner", GameType.SURVIVAL);
		ServerPlayer visitor = createOnlineMockPlayer(server, ogOverworld, visitorId, "ogsky_visitor", GameType.SURVIVAL);
		Island createdIsland = null;
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

			// Boundary enter event + /is lock eviction + exit event
			ServerTickEvents.END_SERVER_TICK.invoker().onEndTick(server);
			helper.assertTrue(enteredIslands.contains(createdIsland.islandId()), "Expected IslandEnterEvent on entering island");
			server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "/is lock true");
			ServerTickEvents.END_SERVER_TICK.invoker().onEndTick(server);
			helper.assertTrue(!createdIsland.contains(visitor.getBlockX(), visitor.getBlockZ()) || visitor.level() != ogOverworld, "Expected locked island to evict visitor");
			helper.assertTrue(exitedIslands.contains(createdIsland.islandId()), "Expected IslandExitEvent after eviction");

			// Void rescue
			server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "/is flag pvp_overworld false");
			owner.teleport(new TeleportTransition(ogOverworld, new Vec3(cx + 0.5, ogOverworld.getMinY() - 5.0, cz + 0.5), Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.DO_NOTHING));
			ServerTickEvents.END_SERVER_TICK.invoker().onEndTick(server);
			helper.assertTrue(owner.getY() >= ogOverworld.getMinY(), "Expected VOID_TELEPORT to rescue falling owner");

			// /isadmin setrange & delete
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "/isadmin setrange ogsky_owner 64");
			helper.assertTrue(createdIsland.halfSize() == 64, "Expected /isadmin setrange to resize island halfSize to 64");
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
			if (createdIsland != null) {
				cleanupTestIsland(coreApi, islands, createdIsland.islandId(), gridX, gridZ);
				clearBlockPosIfLoaded(ogOverworld, new BlockPos(createdIsland.centerX(), coreApi.coreIslands().config().island.defaultSpawnY, createdIsland.centerZ()));
			}
			coreApi.lifecycle().setWorldSetting(BuiltinFlags.TNT_DAMAGE, false);
			coreApi.lifecycle().setWorldSetting(BuiltinFlags.CHEST_DAMAGE, false);
			deleteDirectoryRecursively(cmdBundleDir);
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

		UUID ownerId = UUID.randomUUID();
		Island island = new Island(
			UUID.randomUUID(),
			ownerId,
			0,
			0,
			islands.grid().centerCoordinate(0),
			islands.grid().centerCoordinate(0),
			(short) 20,
			"test_world_flags",
			Instant.now(),
			coreApi.flags(),
			coreApi.config()
		);
		islands.grid().register(island);
		int gridX = island.gridX();
		int gridZ = island.gridZ();
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
			ogOverworld.getGameRules().set(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, previousFireRadius, server);
			coreApi.lifecycle().setWorldSetting(BuiltinFlags.TNT_DAMAGE, false);
			coreApi.lifecycle().setWorldSetting(BuiltinFlags.CHEST_DAMAGE, false);
			coreApi.lifecycle().setWorldSetting(BuiltinFlags.PISTON_OUTSIDE_BOUNDS, false);
			coreApi.lifecycle().setWorldSetting(BuiltinFlags.LIQUID_FLOW_OUTSIDE_BOUNDS, false);
			coreApi.lifecycle().deleteIsland(server, island.islandId(), ownerId, IslandPreDeleteEvent.DeleteReason.ADMIN);
			for (int t = 0; t < 64 && !islands.grid().pendingCleanups().isEmpty(); t++) {
				coreApi.blueprints().tick(server);
			}
			cleanupTestIsland(coreApi, islands, island.islandId(), gridX, gridZ);
		}

		helper.succeed();
	}

	private static void clearBlockPosIfLoaded(ServerLevel level, BlockPos center) {
		for (int dx = -2; dx <= 2; dx++) {
			for (int dy = -1; dy <= 2; dy++) {
				for (int dz = -2; dz <= 2; dz++) {
					level.setBlock(center.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 818);
				}
			}
		}
	}

	private static ServerPlayer createOnlineMockPlayer(
		MinecraftServer server,
		ServerLevel level,
		UUID uuid,
		String username,
		GameType mode
	) {
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(uuid, username), false);
		ServerPlayer player = new ServerPlayer(server, level, cookie.gameProfile(), cookie.clientInformation()) {
			@Override
			public GameType gameMode() {
				return mode;
			}

			@Override
			public boolean isClientAuthoritative() {
				return false;
			}
		};
		mode.updatePlayerAbilities(player.getAbilities());
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		new EmbeddedChannel(connection);
		server.getPlayerList().placeNewPlayer(connection, player, cookie);
		return player;
	}

	private static void cleanupTestIsland(
		OgskyCoreApiImpl coreApi,
		CoreIslandService islands,
		UUID islandId,
		int gridX,
		int gridZ
	) {
		islands.grid().completeCleanupCell(gridX, gridZ);
		islands.grid().unregister(islandId);
		coreApi.databases().find(CoreIslandRepository.MODULE_ID).ifPresent(db -> {
			CoreIslandRepository.deleteIslandAsync(db, islandId).join();
			CoreIslandRepository.deleteCleanupEntryAsync(db, gridX, gridZ).join();
		});
	}

	private static void deleteDirectoryRecursively(Path root) {
		if (!Files.exists(root)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(root)) {
			walk.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (Exception ignored) {
				}
			});
		} catch (Exception ignored) {
		}
	}
}
