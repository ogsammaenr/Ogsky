package me.ogsammaenr.ogsky.gametest;

import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.OgskyCoreApi;
import me.ogsammaenr.ogsky.core.api.db.ModuleDatabase;
import me.ogsammaenr.ogsky.core.api.flag.FlagType;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.internal.OgskyCoreApiImpl;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfig;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandService;
import me.ogsammaenr.ogsky.core.internal.island.Island;
import me.ogsammaenr.ogsky.core.internal.island.PackedGridMap;
import me.ogsammaenr.ogsky.core.internal.world.OgskyDimensions;
import me.ogsammaenr.ogsky.core.internal.world.VoidChunkGenerator;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.portal.TeleportTransition;

import java.nio.file.Files;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Core bootstrap, SQLite schema, active-module isolation, void worldgen, and 1:1 portal routing GameTests.
 */
public final class CoreBootstrapGameTests {
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

		Identifier viewLimitsFlag = Identifier.fromNamespaceAndPath("ogsky_limits", "view_limits");
		if (FabricLoader.getInstance().isModLoaded("ogsky-limits")) {
			ModuleDatabase limitsDb = api.databases().require("limits");
			helper.assertTrue(Files.exists(limitsDb.databasePath()), "Expected limits.db when ogsky-limits is bundled");
			Set<String> limitTables = limitsDb.queryBlocking(conn -> {
				Set<String> names = new HashSet<>();
				try (Statement stmt = conn.createStatement();
					 ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
					while (rs.next()) {
						names.add(rs.getString(1));
					}
				}
				return names;
			});
			helper.assertTrue(
				limitTables.contains("ogsky_island_limits") && limitTables.contains("ogsky_limits_meta"),
				"Expected ogsky_island_limits and ogsky_limits_meta tables in limits.db"
			);
			helper.assertTrue(
				api.flags().find(viewLimitsFlag).isPresent()
					&& api.flags().find(viewLimitsFlag).get().type() == FlagType.PROTECTION
					&& api.flags().find(viewLimitsFlag).get().defaultMinRank() == IslandRole.MEMBER,
				"Expected ogsky_limits:view_limits PROTECTION flag defaulting to MEMBER"
			);
			helper.assertTrue(
				api.commands().subcommands().stream().anyMatch(s -> s.name().equals("limits")),
				"Expected /is limits subcommand when ogsky-limits is loaded"
			);
			helper.assertTrue(
				api.actions().find(Identifier.fromNamespaceAndPath("ogsky_limits", "island_limits")).isPresent(),
				"Expected ogsky_limits:island_limits panel action when ogsky-limits is loaded"
			);
		} else {
			helper.assertTrue(api.databases().find("limits").isEmpty(), "Limits DB should not exist when ogsky-limits is absent");
			helper.assertTrue(
				api.flags().find(viewLimitsFlag).isEmpty(),
				"ogsky_limits:view_limits flag must not exist when ogsky-limits is absent"
			);
			helper.assertTrue(
				api.commands().subcommands().stream().noneMatch(s -> s.name().equals("limits")),
				"Limits subcommand must not exist when ogsky-limits is absent"
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
			GameTestSupport.clearLoadedBounds(server, island.minX(), island.maxX(), island.minZ(), island.maxZ());
			GameTestSupport.cleanupTestIsland(coreApi, islands, island.islandId(), gridX, gridZ);
		}

		helper.succeed();
	}

	@GameTest
	public void coreCreateAndResetWithoutPanelFallbackCleanly(GameTestHelper helper) throws Exception {
		if (FabricLoader.getInstance().isModLoaded("ogsky-panel")) {
			helper.succeed();
			return;
		}

		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel overworld = server.getLevel(OgskyDimensions.OVERWORLD);
		helper.assertTrue(overworld != null, "Expected ogsky:overworld dimension");

		OgskyCoreApiImpl coreApi = (OgskyCoreApiImpl) OgskyCore.api();
		CoreIslandService islands = coreApi.coreIslands();
		String defaultBundleId = coreApi.islands().defaultBlueprintId();
		java.nio.file.Path defaultBundleDir = coreApi.blueprints().registry().blueprintsDir().resolve(defaultBundleId);

		UUID ownerId = UUID.randomUUID();
		ServerPlayer player = GameTestSupport.createOnlineMockPlayer(server, overworld, ownerId, "CoreOnlyPlayer", GameType.SURVIVAL);
		java.util.concurrent.atomic.AtomicReference<UUID> islandRef = new java.util.concurrent.atomic.AtomicReference<>();
		Runnable cleanup = () -> {
			GameTestSupport.deleteAndCleanupIsland(server, coreApi, islands, islandRef.get(), ownerId);
			GameTestSupport.deleteDirectoryRecursively(defaultBundleDir);
			coreApi.reloadConfigAndBlueprints(server);
			server.getPlayerList().remove(player);
		};

		GameTestSupport.runWithCleanup(cleanup, () -> {
			GameTestSupport.synthesizeSimpleBlueprintBundle(overworld, defaultBundleDir, defaultBundleId, "Core Default", 0);
			coreApi.reloadConfigAndBlueprints(server);

			server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), "is create");
			Island created = islands.grid().findPrimaryIsland(ownerId).orElse(null);
			helper.assertTrue(created != null, "Expected argless /is create to create island directly in core-only mode");
			islandRef.set(created.islandId());
			for (int t = 0; t < 20 && created.isPasting(); t++) {
				coreApi.blueprints().tick(server);
			}

			BlockPos checkPos = new BlockPos(created.centerX(), 64, created.centerZ());
			helper.assertTrue(overworld.getBlockState(checkPos).is(Blocks.STONE),
				"Expected core-only /is create to paste default bundle block");
			overworld.setBlock(checkPos, Blocks.AIR.defaultBlockState(), 18);

			server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), "is reset");
			for (int t = 0; t < 40 && created.isPasting(); t++) {
				coreApi.blueprints().tick(server);
			}
			helper.assertTrue(overworld.getBlockState(checkPos).is(Blocks.STONE),
				"Expected argless /is reset to fall back cleanly and re-paste default bundle in core-only mode");
			cleanup.run();
		});

		helper.succeed();
	}
}
