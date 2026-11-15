package me.ogsammaenr.ogsky.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
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
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.Connection;
import net.minecraft.network.HashedStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
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
import java.util.concurrent.atomic.AtomicReference;
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

		for (String coreAction : List.of(
			"island_home",
			"island_home_nether",
			"island_home_end",
			"island_sethome",
			"island_toggle_lock"
		)) {
			helper.assertTrue(
				api.actions().find(OgskyCore.id(coreAction)).isPresent(),
				"Expected built-in core action " + coreAction
			);
		}

		if (FabricLoader.getInstance().isModLoaded("ogsky-panel")) {
			ModuleDatabase panelDb = api.databases().require("panel");
			helper.assertTrue(Files.exists(panelDb.databasePath()), "Expected panel.db when ogsky-panel is bundled");
			Set<String> panelCols = panelDb.queryBlocking(conn -> {
				Set<String> cols = new HashSet<>();
				try (Statement stmt = conn.createStatement();
					 ResultSet rs = stmt.executeQuery("PRAGMA table_info(panel_preferences)")) {
					while (rs.next()) {
						cols.add(rs.getString("name"));
					}
				}
				return cols;
			});
			for (String expectedCol : Set.of("player_uuid", "ui_mode", "preferred_tab", "flag_mode", "updated_at")) {
				helper.assertTrue(panelCols.contains(expectedCol), "Expected column '" + expectedCol + "' in panel_preferences v2");
			}
			helper.assertTrue(
				api.commands().subcommands().stream().anyMatch(s -> s.name().equals("panel")),
				"Expected /is panel subcommand when ogsky-panel is loaded"
			);
			helper.assertTrue(
				api.commands().subcommands().stream().anyMatch(s -> s.name().equals("settings")),
				"Expected /is settings subcommand when ogsky-panel is loaded"
			);
		} else {
			helper.assertTrue(api.databases().find("panel").isEmpty(), "Panel DB should not exist when ogsky-panel is absent");
			helper.assertTrue(
				api.commands().subcommands().stream().noneMatch(s -> s.name().equals("panel")),
				"Panel subcommand should not be registered when ogsky-panel is absent"
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

			ownerPlayer = createOnlineMockPlayer(server, overworld, ownerId, "BpOwner", GameType.SURVIVAL);
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

			testBlueprints.cleanupQueue().enqueue(gridX, gridZ, island.minX(), island.maxX(), island.minZ(), island.maxZ());
			for (int t = 0; t < 32 && !islands.grid().pendingCleanups().isEmpty(); t++) {
				testBlueprints.tick(server);
			}
		} finally {
			if (ownerPlayer != null) {
				server.getPlayerList().remove(ownerPlayer);
			}
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
			coreApi.lifecycle().deleteIsland(server, visIslandId, visitorId, IslandPreDeleteEvent.DeleteReason.ADMIN);
			for (int t = 0; t < 512 && !islands.grid().pendingCleanups().isEmpty(); t++) {
				coreApi.blueprints().tick(server);
			}
			cleanupTestIsland(coreApi, islands, visIslandId, visGridX, visGridZ);
			visitorIsland = null;

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
			if (visitorIsland != null) {
				cleanupTestIsland(coreApi, islands, visitorIsland.islandId(), visitorIsland.gridX(), visitorIsland.gridZ());
			}
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

	@GameTest
	public void panelCommandsChestMenuExploitsAndLiveRefreshWork(GameTestHelper helper) throws Exception {
		if (!FabricLoader.getInstance().isModLoaded("ogsky-panel")) {
			helper.succeed();
			return;
		}

		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel ogOverworld = server.getLevel(OgskyDimensions.OVERWORLD);
		helper.assertTrue(ogOverworld != null, "Expected ogsky:overworld dimension");

		OgskyCoreApiImpl coreApi = (OgskyCoreApiImpl) OgskyCore.api();
		CoreIslandService islands = coreApi.coreIslands();
		Path blueprintsRoot = coreApi.blueprints().registry().blueprintsDir();
		Path bundleTestDir = blueprintsRoot.resolve("panel_test");
		Path bundleAltDir = blueprintsRoot.resolve("panel_alt");

		UUID ownerId = UUID.randomUUID();
		UUID memberId = UUID.randomUUID();
		UUID bannedId = UUID.randomUUID();
		ServerPlayer owner = createOnlineMockPlayer(server, ogOverworld, ownerId, "PanelOwner", GameType.SURVIVAL);
		ServerPlayer member = createOnlineMockPlayer(server, ogOverworld, memberId, "PanelMember", GameType.SURVIVAL);
		owner.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
		owner.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD, 1));

		AtomicReference<UUID> createdIslandId = new AtomicReference<>();
		Runnable cleanup = () -> {
			try {
				owner.closeContainer();
				member.closeContainer();
			} catch (Exception ignored) {
			}
			UUID islandId = createdIslandId.get();
			if (islandId != null) {
				islands.grid().findById(islandId).ifPresent(found -> {
					int gx = found.gridX();
					int gz = found.gridZ();
					coreApi.lifecycle().deleteIsland(server, islandId, ownerId, IslandPreDeleteEvent.DeleteReason.ADMIN);
					for (int t = 0; t < 64 && !islands.grid().pendingCleanups().isEmpty(); t++) {
						coreApi.blueprints().tick(server);
					}
					cleanupTestIsland(coreApi, islands, islandId, gx, gz);
				});
			}
			deleteDirectoryRecursively(bundleTestDir);
			deleteDirectoryRecursively(bundleAltDir);
			coreApi.blueprints().registry().reload();
			server.getPlayerList().remove(owner);
			server.getPlayerList().remove(member);
		};

		try {
			synthesizeSimpleBlueprintBundle(ogOverworld, bundleTestDir, "panel_test", "Panel Test", 1);
			synthesizeSimpleBlueprintBundle(ogOverworld, bundleAltDir, "panel_alt", "Panel Alt", 2);
			coreApi.blueprints().registry().reload();
			helper.assertTrue(coreApi.blueprints().registry().find("panel_test").isPresent(), "Expected panel_test blueprint");
			helper.assertTrue(coreApi.blueprints().registry().find("panel_alt").isPresent(), "Expected panel_alt blueprint");

			// Step 1: Islandless /is panel asynchronously loads panel.db preferences and opens Blueprints & Info tab
			server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "is panel");

			helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(
					owner.containerMenu instanceof ChestMenu,
					"Expected async /is panel execution to open a server ChestMenu"
				))
				.thenExecute(guarded(cleanup, () -> {
					ChestMenu menu = (ChestMenu) owner.containerMenu;
					helper.assertTrue(
						Boolean.TRUE.equals(menu.getSlot(4).getItem().get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)),
						"Expected islandless player to land on Blueprints & Info tab (slot 4)"
					);

					// Step 2: Verify ChestMenu immunity to vanilla dupe/swap/quick-move/clone/throw packets
					int testBpSlot = findContentSlotByName(menu, "Panel Test");
					helper.assertTrue(testBpSlot >= 0, "Expected Panel Test blueprint icon inside panel grid");
					menu.clicked(testBpSlot, 0, ContainerInput.SWAP, owner);
					menu.clicked(testBpSlot, 40, ContainerInput.SWAP, owner);
					menu.clicked(testBpSlot, 0, ContainerInput.THROW, owner);
					menu.clicked(testBpSlot, 0, ContainerInput.CLONE, owner);
					menu.clicked(testBpSlot, 0, ContainerInput.PICKUP_ALL, owner);
					menu.clicked(testBpSlot, 0, ContainerInput.QUICK_CRAFT, owner);
					menu.clicked(54, 0, ContainerInput.PICKUP, owner);
					ItemStack quickMoved = menu.quickMoveStack(owner, testBpSlot);
					helper.assertTrue(quickMoved.isEmpty(), "Expected quickMoveStack to return ItemStack.EMPTY");
					helper.assertTrue(menu.getCarried().isEmpty(), "Expected carried stack to remain empty");
					helper.assertTrue(owner.getInventory().getItem(0).is(Items.DIAMOND)
						&& owner.getInventory().getItem(0).getCount() == 3, "Expected player hotbar diamond untouched");
					helper.assertTrue(owner.getOffhandItem().is(Items.SHIELD), "Expected offhand shield untouched");

					// Step 3: Click Panel Test blueprint to create island for islandless player
					menu.clicked(testBpSlot, 0, ContainerInput.PICKUP, owner);
					Island created = islands.grid().findPrimaryIsland(ownerId).orElse(null);
					helper.assertTrue(created != null, "Expected blueprint click to create island");
					createdIslandId.set(created.islandId());
					helper.assertTrue("panel_test".equals(created.bundleId()), "Expected created island to use panel_test bundle");
					helper.assertTrue(!(owner.containerMenu instanceof ChestMenu), "Expected menu to close after creation");
					for (int t = 0; t < 20 && created.isPasting(); t++) {
						coreApi.blueprints().tick(server);
					}

					// Step 4: Add a member + ban a UUID, then test cached /is settings & tab clicks
					helper.assertTrue(coreApi.team().inviteMember(created, ownerId, memberId).success(), "Invite member");
					helper.assertTrue(coreApi.team().acceptInvite(memberId).success(), "Accept invite");
					helper.assertTrue(coreApi.team().banPlayer(server, created, ownerId, bannedId, false).success(), "Ban UUID");

					server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "is settings");
					helper.assertTrue(owner.containerMenu instanceof ChestMenu, "Expected cached /is settings to open synchronously");
					ChestMenu settingsMenu = (ChestMenu) owner.containerMenu;
					helper.assertTrue(
						Boolean.TRUE.equals(settingsMenu.getSlot(1).getItem().get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)),
						"Expected Protection Flags tab (slot 1) highlighted"
					);

					int breakSlot = findContentSlotByName(settingsMenu, "Break Blocks");
					helper.assertTrue(breakSlot >= 0, "Expected Break Blocks flag in Basic mode");
					helper.assertTrue(created.protectionRankBySlot(coreApi.flags().slotOf(BuiltinFlags.BREAK_BLOCKS)) == IslandRole.MEMBER,
						"Expected Break Blocks default rank MEMBER");
					settingsMenu.clicked(breakSlot, 0, ContainerInput.PICKUP, owner);
					helper.assertTrue(created.protectionRankBySlot(coreApi.flags().slotOf(BuiltinFlags.BREAK_BLOCKS)) == IslandRole.ADMIN,
						"Left-click must raise Break Blocks to ADMIN");
					settingsMenu.clicked(breakSlot, 1, ContainerInput.PICKUP, owner);
					helper.assertTrue(created.protectionRankBySlot(coreApi.flags().slotOf(BuiltinFlags.BREAK_BLOCKS)) == IslandRole.MEMBER,
						"Right-click must lower Break Blocks back to MEMBER");
					settingsMenu.clicked(breakSlot, 0, ContainerInput.PICKUP, owner);
					settingsMenu.clicked(breakSlot, 0, ContainerInput.QUICK_MOVE, owner);
					helper.assertTrue(created.protectionRankBySlot(coreApi.flags().slotOf(BuiltinFlags.BREAK_BLOCKS)) == IslandRole.MEMBER,
						"Shift-click must reset Break Blocks to default");

					// Switch to Members tab (slot 3): unban, promote, demote, 2-step kick
					settingsMenu.clicked(3, 0, ContainerInput.PICKUP, owner);
					int bannedSlot = findContentSlotByName(settingsMenu, bannedId.toString().substring(0, 8));
					helper.assertTrue(bannedSlot >= 0, "Expected banned player head in Members tab");
					settingsMenu.clicked(bannedSlot, 0, ContainerInput.PICKUP, owner);
					helper.assertTrue(!created.isBanned(bannedId), "Clicking banned head must unban player");

					int memberSlot = findContentSlotByName(settingsMenu, "PanelMember");
					helper.assertTrue(memberSlot >= 0, "Expected PanelMember head in Members tab");
					settingsMenu.clicked(memberSlot, 0, ContainerInput.PICKUP, owner);
					helper.assertTrue(created.roleOf(memberId) == IslandRole.ADMIN, "Left-click must promote member to ADMIN");
					settingsMenu.clicked(memberSlot, 1, ContainerInput.PICKUP, owner);
					helper.assertTrue(created.roleOf(memberId) == IslandRole.MEMBER, "Right-click must demote admin to MEMBER");
					settingsMenu.clicked(memberSlot, 0, ContainerInput.QUICK_MOVE, owner);
					helper.assertTrue(created.roleOf(memberId) == IslandRole.MEMBER, "First Shift-Left click must only prompt confirmation");
					settingsMenu.clicked(memberSlot, 0, ContainerInput.QUICK_MOVE, owner);
					helper.assertTrue(created.roleOf(memberId) == IslandRole.VISITOR, "Second Shift-Left click must confirm kick");

					// Switch to Island Settings tab (slot 2), verify top-bar Lock Status badge (slot 7) and live refresh
					settingsMenu.clicked(2, 0, ContainerInput.PICKUP, owner);
					helper.assertTrue(
						settingsMenu.getSlot(7).getItem().is(Items.OAK_DOOR)
							&& !Boolean.TRUE.equals(settingsMenu.getSlot(7).getItem().get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)),
						"Expected top-bar lock badge (slot 7) to show UNLOCKED (OAK_DOOR, unglinted)"
					);
					int lockSlot = findContentSlotByName(settingsMenu, "Island Lock");
					helper.assertTrue(lockSlot >= 0, "Expected Island Lock in Island Settings tab");
					helper.assertTrue(
						!Boolean.TRUE.equals(settingsMenu.getSlot(lockSlot).getItem().get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)),
						"Expected Island Lock initially unglinted"
					);
					// Clicking top-bar lock badge locks island directly and updates Actions tab entry dynamically
					settingsMenu.clicked(7, 0, ContainerInput.PICKUP, owner);
					helper.assertTrue(created.isSettingEnabled(BuiltinFlags.ISLAND_LOCK), "Clicking slot 7 must lock island");
					helper.assertTrue(
						settingsMenu.getSlot(7).getItem().is(Items.IRON_BARS)
							&& Boolean.TRUE.equals(settingsMenu.getSlot(7).getItem().get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)),
						"Expected top-bar lock badge (slot 7) to show LOCKED (IRON_BARS, glinted)"
					);
					settingsMenu.clicked(0, 0, ContainerInput.PICKUP, owner); // Actions tab
					int actionLockSlot = findContentSlotByName(settingsMenu, "Island Lock: LOCKED");
					helper.assertTrue(
						actionLockSlot >= 0
							&& settingsMenu.getSlot(actionLockSlot).getItem().is(Items.IRON_BARS)
							&& Boolean.TRUE.equals(settingsMenu.getSlot(actionLockSlot).getItem().get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)),
						"Expected Actions tab toggle_lock entry to reflect LOCKED state dynamically"
					);
					// Clicking slot 7 again unlocks island before testing external lock event refresh
					settingsMenu.clicked(7, 0, ContainerInput.PICKUP, owner);
					helper.assertTrue(!created.isSettingEnabled(BuiltinFlags.ISLAND_LOCK), "Clicking slot 7 again must unlock island");
					settingsMenu.clicked(2, 0, ContainerInput.PICKUP, owner);
					helper.assertTrue(
						!Boolean.TRUE.equals(settingsMenu.getSlot(lockSlot).getItem().get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)),
						"Expected Island Lock unglinted again after unlock"
					);
					helper.assertTrue(
						coreApi.islands().setSettingFlag(server, ownerId, created.islandId(), BuiltinFlags.ISLAND_LOCK, true, false).success(),
						"External Island Lock mutation must succeed"
					);
				}))
				.thenIdle(1)
				.thenExecute(guarded(cleanup, () -> {
					ChestMenu menu = (ChestMenu) owner.containerMenu;
					Island created = islands.grid().findById(createdIslandId.get()).orElseThrow();
					int lockSlot = findContentSlotByName(menu, "Island Lock");
					helper.assertTrue(
						Boolean.TRUE.equals(menu.getSlot(lockSlot).getItem().get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)),
						"Expected OgskyEventBus live refresh to update Island Lock item glint on open ChestMenu"
					);

					// Step 5: 2-step reset confirmation on Blueprints & Info tab (slot 4)
					menu.clicked(4, 0, ContainerInput.PICKUP, owner);
					int altBpSlot = findContentSlotByName(menu, "Panel Alt");
					helper.assertTrue(altBpSlot >= 0, "Expected Panel Alt blueprint on Blueprints & Info tab");
					menu.clicked(altBpSlot, 0, ContainerInput.PICKUP, owner);
					helper.assertTrue("panel_test".equals(created.bundleId()), "First reset click must require confirmation");
					helper.assertTrue(
						Boolean.TRUE.equals(menu.getSlot(altBpSlot).getItem().get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)),
						"Pending reset target should highlight with glint"
					);
					menu.clicked(altBpSlot, 0, ContainerInput.PICKUP, owner);
					helper.assertTrue("panel_alt".equals(created.bundleId()), "Second click within TTL must reset island to panel_alt");
					for (int t = 0; t < 20 && created.isPasting(); t++) {
						coreApi.blueprints().tick(server);
					}

					// Verify async panel_preferences persisted viewer's latest tab choice ('blueprints_info') to panel.db
					ModuleDatabase panelDb = coreApi.databases().require("panel");
					panelDb.flush().join();
					String persistedTab = panelDb.queryBlocking(conn -> {
						try (var stmt = conn.prepareStatement("SELECT preferred_tab FROM panel_preferences WHERE player_uuid = ?")) {
							stmt.setString(1, ownerId.toString());
							try (ResultSet rs = stmt.executeQuery()) {
								return rs.next() ? rs.getString(1) : null;
							}
						}
					});
					helper.assertTrue("blueprints_info".equals(persistedTab),
						"Expected panel_preferences.preferred_tab persisted in SQLite, got: " + persistedTab);

					cleanup.run();
				}))
				.thenSucceed();
		} catch (Throwable t) {
			cleanup.run();
			if (t instanceof RuntimeException re) {
				throw re;
			}
			if (t instanceof Error err) {
				throw err;
			}
			throw new RuntimeException(t);
		}
	}

	@GameTest
	public void panelOffhandSwapAndCreativeDupePacketsBlocked(GameTestHelper helper) {
		if (!FabricLoader.getInstance().isModLoaded("ogsky-panel")) {
			helper.succeed();
			return;
		}

		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel ogOverworld = server.getLevel(OgskyDimensions.OVERWORLD);
		helper.assertTrue(ogOverworld != null, "Expected ogsky:overworld dimension");

		AtomicReference<EmbeddedChannel> survivalChannelRef = new AtomicReference<>();
		AtomicReference<EmbeddedChannel> creativeChannelRef = new AtomicReference<>();
		ServerPlayer survival = createOnlineMockPlayer(
			server, ogOverworld, UUID.randomUUID(), "PanelSwapSurv", GameType.SURVIVAL, survivalChannelRef
		);
		ServerPlayer creative = createOnlineMockPlayer(
			server, ogOverworld, UUID.randomUUID(), "PanelSwapCrea", GameType.CREATIVE, creativeChannelRef
		);
		survival.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD, 1));
		survival.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 2));

		Runnable cleanup = () -> {
			try {
				survival.closeContainer();
				creative.closeContainer();
			} catch (Exception ignored) {
			}
			server.getPlayerList().remove(survival);
			server.getPlayerList().remove(creative);
		};

		try {
			server.getCommands().performPrefixedCommand(survival.createCommandSourceStack(), "is panel");
			server.getCommands().performPrefixedCommand(creative.createCommandSourceStack(), "is panel");

			helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(
					survival.containerMenu instanceof ChestMenu && creative.containerMenu instanceof ChestMenu,
					"Expected both Survival and Creative mock players to open Ogsky panel ChestMenu"
				))
				.thenExecute(guarded(cleanup, () -> {
					ChestMenu survMenu = (ChestMenu) survival.containerMenu;
					ChestMenu creaMenu = (ChestMenu) creative.containerMenu;
					EmbeddedChannel survCh = survivalChannelRef.get();
					EmbeddedChannel creaCh = creativeChannelRef.get();

					// Every panel slot icon must carry the hidden panel item marker
					ItemStack iconCopy = creaMenu.getSlot(4).getItem().copy();
					CustomData marker = iconCopy.get(DataComponents.CUSTOM_DATA);
					helper.assertTrue(
						marker != null && marker.copyTag().getBooleanOr("ogsky_panel_item", false),
						"Expected panel icon stack to carry internal ogsky_panel_item marker"
					);

					// 1. Survival: simulate pressing 'F' (ContainerInput.SWAP, button 40) on panel slot 4 and slot 45
					drainOutboundPackets(survCh);
					Int2ObjectMap<HashedStack> fSwapChanged = new Int2ObjectOpenHashMap<>();
					fSwapChanged.put(4, HashedStack.EMPTY);
					survival.connection.handleContainerClick(new ServerboundContainerClickPacket(
						survMenu.containerId,
						survMenu.getStateId(),
						(short) 4,
						(byte) 40,
						ContainerInput.SWAP,
						fSwapChanged,
						HashedStack.EMPTY
					));
					List<Packet<?>> survPackets = drainOutboundPackets(survCh);
					String survPacketSummary = survPackets.stream()
						.map(p -> p.getClass().getSimpleName())
						.collect(java.util.stream.Collectors.joining(","));
					helper.assertTrue(survival.getOffhandItem().is(Items.SHIELD),
						"Expected Survival offhand Shield untouched after F-swap on panel slot");
					helper.assertTrue(
						survPackets.stream().anyMatch(p -> p instanceof ClientboundSetPlayerInventoryPacket inv
							&& inv.slot() == Inventory.SLOT_OFFHAND
							&& inv.contents().is(Items.SHIELD)),
						"Expected server to send ClientboundSetPlayerInventoryPacket(40, SHIELD), got [" + survPacketSummary + "]"
					);
					helper.assertTrue(
						survPackets.stream().anyMatch(p -> p instanceof ClientboundSetCursorItemPacket cur
							&& cur.contents().isEmpty()),
						"Expected server to send empty cursor sync packet after blocked F-swap"
					);
					helper.assertTrue(
						survPackets.stream().anyMatch(p -> p instanceof ClientboundContainerSetContentPacket content
							&& content.containerId() == survMenu.containerId
							&& !content.items().get(4).isEmpty()),
						"Expected server to resync panel content packet preserving slot 4"
					);

					// Survival: pressing '1' (button 0) must also resync hotbar slot 0 (diamond)
					drainOutboundPackets(survCh);
					Int2ObjectMap<HashedStack> hotbarSwapChanged = new Int2ObjectOpenHashMap<>();
					hotbarSwapChanged.put(4, HashedStack.EMPTY);
					survival.connection.handleContainerClick(new ServerboundContainerClickPacket(
						survMenu.containerId,
						survMenu.getStateId(),
						(short) 4,
						(byte) 0,
						ContainerInput.SWAP,
						hotbarSwapChanged,
						HashedStack.EMPTY
					));
					List<Packet<?>> hotbarPackets = drainOutboundPackets(survCh);
					helper.assertTrue(
						survival.getInventory().getItem(0).is(Items.DIAMOND) && survival.getInventory().getItem(0).getCount() == 2,
						"Expected Survival hotbar slot 0 untouched after hotbar swap attempt"
					);
					helper.assertTrue(
						hotbarPackets.stream().anyMatch(p -> p instanceof ClientboundSetPlayerInventoryPacket inv
							&& inv.slot() == 0
							&& inv.contents().is(Items.DIAMOND)),
						"Expected server to resync hotbar slot 0 after blocked 1-9 swap"
					);

					// 2. Creative: F-swap packet, offhand PlayerAction, and SetCreativeModeSlot injection must all fail
					drainOutboundPackets(creaCh);
					Int2ObjectMap<HashedStack> creaFSwap = new Int2ObjectOpenHashMap<>();
					creaFSwap.put(4, HashedStack.EMPTY);
					creative.connection.handleContainerClick(new ServerboundContainerClickPacket(
						creaMenu.containerId,
						creaMenu.getStateId(),
						(short) 4,
						(byte) 40,
						ContainerInput.SWAP,
						creaFSwap,
						HashedStack.EMPTY
					));
					List<Packet<?>> creaPackets = drainOutboundPackets(creaCh);
					helper.assertTrue(creative.getOffhandItem().isEmpty(),
						"Expected Creative offhand empty after F-swap on panel slot");
					helper.assertTrue(
						creaPackets.stream().anyMatch(p -> p instanceof ClientboundSetPlayerInventoryPacket inv
							&& inv.slot() == Inventory.SLOT_OFFHAND
							&& inv.contents().isEmpty()),
						"Expected server to send offhand clear packet in Creative mode too"
					);

					creative.connection.handlePlayerAction(new ServerboundPlayerActionPacket(
						ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
						BlockPos.ZERO,
						Direction.DOWN
					));
					helper.assertTrue(creative.getOffhandItem().isEmpty(),
						"Expected offhand action blocked while panel menu is open");

					// Attempt to inject an item via Creative slot packet while panel is open
					creative.connection.handleSetCreativeModeSlot(
						new ServerboundSetCreativeModeSlotPacket(InventoryMenu.SHIELD_SLOT, new ItemStack(Items.NETHERITE_BLOCK, 1))
					);
					helper.assertTrue(creative.getOffhandItem().isEmpty(),
						"Expected ServerboundSetCreativeModeSlotPacket blocked while OgskyPanelMenu is open");

					// Close panel and attempt to inject or drop a copied panel icon via Creative slot packet
					creative.closeContainer();
					creative.connection.handleSetCreativeModeSlot(
						new ServerboundSetCreativeModeSlotPacket(InventoryMenu.SHIELD_SLOT, iconCopy.copy())
					);
					helper.assertTrue(creative.getOffhandItem().isEmpty(),
						"Expected Creative offhand injection of marked panel icon rejected even after closing panel");

					List<ItemEntity> dropped = ogOverworld.getEntitiesOfClass(
						ItemEntity.class, creative.getBoundingBox().inflate(8.0)
					);
					creative.connection.handleSetCreativeModeSlot(
						new ServerboundSetCreativeModeSlotPacket(-1, iconCopy.copy())
					);
					List<ItemEntity> droppedAfter = ogOverworld.getEntitiesOfClass(
						ItemEntity.class, creative.getBoundingBox().inflate(8.0)
					);
					helper.assertTrue(droppedAfter.size() == dropped.size(),
						"Expected Creative drop of marked panel icon blocked");

					// Normal unmarked Creative items still work cleanly once panel is closed
					creative.connection.handleSetCreativeModeSlot(
						new ServerboundSetCreativeModeSlotPacket(36, new ItemStack(Items.OAK_PLANKS, 16))
					);
					helper.assertTrue(
						creative.getInventory().getItem(0).is(Items.OAK_PLANKS) && creative.getInventory().getItem(0).getCount() == 16,
						"Expected legitimate Creative inventory placement to continue working when panel is closed"
					);

					cleanup.run();
				}))
				.thenSucceed();
		} catch (Throwable t) {
			cleanup.run();
			if (t instanceof RuntimeException re) {
				throw re;
			}
			if (t instanceof Error err) {
				throw err;
			}
			throw new RuntimeException(t);
		}
	}

	private static Runnable guarded(Runnable cleanup, Runnable body) {
		return () -> {
			try {
				body.run();
			} catch (Throwable t) {
				cleanup.run();
				throw t;
			}
		};
	}

	private static int findContentSlotByName(ChestMenu menu, String expectedName) {
		int[] contentSlots = {
			10, 11, 12, 13, 14, 15, 16,
			19, 20, 21, 22, 23, 24, 25,
			28, 29, 30, 31, 32, 33, 34,
			37, 38, 39, 40, 41, 42, 43
		};
		for (int slot : contentSlots) {
			ItemStack stack = menu.getSlot(slot).getItem();
			if (stack.isEmpty()) {
				continue;
			}
			var customName = stack.get(DataComponents.CUSTOM_NAME);
			if (customName != null && expectedName.equals(customName.getString())) {
				return slot;
			}
		}
		return -1;
	}

	private static void synthesizeSimpleBlueprintBundle(
		ServerLevel scratchLevel,
		Path bundleDir,
		String id,
		String displayName,
		int order
	) throws Exception {
		Files.createDirectories(bundleDir);
		BlockPos origin = new BlockPos(99_000 + order * 16, 120, 99_000);
		scratchLevel.setBlock(origin, Blocks.STONE.defaultBlockState(), 18);
		try {
			StructureTemplate template = new StructureTemplate();
			template.fillFromWorld(scratchLevel, origin, new Vec3i(1, 1, 1), false, List.of());
			NbtIo.writeCompressed(template.save(new CompoundTag()), bundleDir.resolve("overworld.nbt"));
		} finally {
			scratchLevel.setBlock(origin, Blocks.AIR.defaultBlockState(), 18);
		}
		Files.writeString(bundleDir.resolve("bundle.json"), """
			{
			  "id": "%s",
			  "displayName": "%s",
			  "description": "GameTest panel starter",
			  "icon": "minecraft:grass_block",
			  "order": %d,
			  "overworld": { "file": "overworld.nbt", "offsetY": 0 }
			}
			""".formatted(id, displayName, order));
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
		return createOnlineMockPlayer(server, level, uuid, username, mode, null);
	}

	private static ServerPlayer createOnlineMockPlayer(
		MinecraftServer server,
		ServerLevel level,
		UUID uuid,
		String username,
		GameType mode,
		AtomicReference<EmbeddedChannel> outChannel
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
		EmbeddedChannel channel = new EmbeddedChannel(connection);
		if (outChannel != null) {
			outChannel.set(channel);
		}
		server.getPlayerList().placeNewPlayer(connection, player, cookie);
		return player;
	}

	private static List<Packet<?>> drainOutboundPackets(EmbeddedChannel channel) {
		channel.flush();
		channel.runPendingTasks();
		List<Packet<?>> drained = new ArrayList<>();
		Object msg;
		while ((msg = channel.readOutbound()) != null) {
			if (msg instanceof Packet<?> packet) {
				drained.add(packet);
			}
		}
		return drained;
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
