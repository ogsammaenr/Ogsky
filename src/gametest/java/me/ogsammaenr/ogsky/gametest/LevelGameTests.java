package me.ogsammaenr.ogsky.gametest;

import io.netty.channel.embedded.EmbeddedChannel;
import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.action.IslandActionContext;
import me.ogsammaenr.ogsky.core.api.action.IslandActionDescriptor;
import me.ogsammaenr.ogsky.core.api.db.ModuleDatabase;
import me.ogsammaenr.ogsky.core.api.event.IslandLevelCalculatedEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandPreDeleteEvent;
import me.ogsammaenr.ogsky.core.api.level.BlockPointBreakdown;
import me.ogsammaenr.ogsky.core.api.level.IslandLevelProvider;
import me.ogsammaenr.ogsky.core.api.level.IslandLevelSnapshot;
import me.ogsammaenr.ogsky.core.internal.OgskyCoreApiImpl;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandService;
import me.ogsammaenr.ogsky.core.internal.island.Island;
import me.ogsammaenr.ogsky.core.internal.world.OgskyDimensions;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Island level ({@code ogsky-level}) calculation, lazy-Nether scan, scoring cap, leaderboard GUI, and chat fallback GameTests.
 */
public final class LevelGameTests {
	// GameTestServer.waitUntilNextTick() spins unthrottled (>10k ticks/s) while awaiting async IOWorker chunk reads.
	@GameTest(maxTicks = 200000)
	public void levelCalculationLeaderboardGuiAndFallbackWork(GameTestHelper helper) throws Exception {
		if (!FabricLoader.getInstance().isModLoaded("ogsky-level")) {
			helper.succeed();
			return;
		}

		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel ogOverworld = server.getLevel(OgskyDimensions.OVERWORLD);
		ServerLevel ogNether = server.getLevel(OgskyDimensions.NETHER);
		helper.assertTrue(ogOverworld != null && ogNether != null, "Expected ogsky overworld and nether levels");

		OgskyCoreApiImpl coreApi = (OgskyCoreApiImpl) OgskyCore.api();
		CoreIslandService islands = coreApi.coreIslands();
		IslandLevelProvider levelProvider = coreApi.services().find(IslandLevelProvider.class).orElse(null);
		helper.assertTrue(levelProvider != null, "Expected IslandLevelProvider registered in core.services()");
		helper.assertTrue(
			coreApi.actions().find(OgskyCore.id("island_level")).isPresent()
				&& coreApi.actions().find(Identifier.fromNamespaceAndPath("ogsky_level", "island_top")).isPresent()
				&& coreApi.actions().find(Identifier.fromNamespaceAndPath("ogsky_level", "block_values")).isPresent(),
			"Expected island_level, island_top, and block_values actions registered"
		);

		Path levelConfigPath = FabricLoader.getInstance().getConfigDir().resolve("ogsky").resolve("level.json");
		String originalLevelJson = Files.exists(levelConfigPath) ? Files.readString(levelConfigPath) : null;

		Path blueprintsRoot = coreApi.blueprints().registry().blueprintsDir();
		Path bundleDir = blueprintsRoot.resolve("level_test");

		UUID ownerAId = UUID.randomUUID();
		UUID ownerBId = UUID.randomUUID();
		UUID islandlessViewerId = UUID.randomUUID();
		AtomicReference<EmbeddedChannel> ownerAChannel = new AtomicReference<>();
		ServerPlayer ownerA = GameTestSupport.createOnlineMockPlayer(server, ogOverworld, ownerAId, "LevelOwnerA", GameType.SURVIVAL, ownerAChannel);
		ServerPlayer ownerB = GameTestSupport.createOnlineMockPlayer(server, ogOverworld, ownerBId, "LevelOwnerB", GameType.SURVIVAL);
		ServerPlayer islandlessViewer = GameTestSupport.createOnlineMockPlayer(server, ogOverworld, islandlessViewerId, "LevelValuesViewer", GameType.SURVIVAL);

		AtomicReference<UUID> islandAId = new AtomicReference<>();
		AtomicReference<UUID> islandBId = new AtomicReference<>();
		AtomicReference<BlockPos> outOfBoundsBeaconPos = new AtomicReference<>();
		AtomicReference<BlockPos> netherBlockPos = new AtomicReference<>();
		List<IslandLevelCalculatedEvent> calculatedEvents = new ArrayList<>();
		coreApi.events().subscribe(IslandLevelCalculatedEvent.class, calculatedEvents::add);

		Runnable cleanup = () -> {
			GameTestSupport.closeContainersQuietly(ownerA, ownerB, islandlessViewer);
			try {
				if (originalLevelJson != null) {
					Files.writeString(levelConfigPath, originalLevelJson);
				}
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "isadmin level reload");
			} catch (Exception ignored) {
			}
			if (outOfBoundsBeaconPos.get() != null) {
				ogOverworld.setBlock(outOfBoundsBeaconPos.get(), Blocks.AIR.defaultBlockState(), 18);
			}
			if (netherBlockPos.get() != null) {
				ogNether.setBlock(netherBlockPos.get(), Blocks.AIR.defaultBlockState(), 18);
			}
			GameTestSupport.deleteAndCleanupIsland(server, coreApi, islands, islandAId.get(), ownerAId);
			GameTestSupport.deleteAndCleanupIsland(server, coreApi, islands, islandBId.get(), ownerBId);
			GameTestSupport.deleteDirectoryRecursively(bundleDir);
			coreApi.blueprints().registry().reload();
			server.getPlayerList().remove(ownerA);
			server.getPlayerList().remove(ownerB);
			server.getPlayerList().remove(islandlessViewer);
		};

		GameTestSupport.runWithCleanup(cleanup, () -> {
			GameTestSupport.synthesizeSimpleBlueprintBundle(ogOverworld, bundleDir, "level_test", "Level Test", 4);
			coreApi.blueprints().registry().reload();

			var createA = coreApi.islands().createIsland(server, ownerA, ownerAId, "level_test");
			helper.assertTrue(createA.success() && createA.value() != null, "Expected island A creation: " + createA.message());
			islandAId.set(createA.value().islandId());
			coreApi.lifecycle().resizeIsland(islandAId.get(), 16);

			// Step 1: Allow paste + initial auto-scan of Island A (1x stone starter = 2 pts) and create Island B
			helper.startSequence()
				.thenIdle(1)
				.thenExecute(GameTestSupport.guarded(cleanup, () -> {
					var createB = coreApi.islands().createIsland(server, ownerB, ownerBId, "level_test");
					helper.assertTrue(createB.success() && createB.value() != null, "Expected island B creation: " + createB.message());
					islandBId.set(createB.value().islandId());
					coreApi.lifecycle().resizeIsland(islandBId.get(), 16);
				}))
				.thenWaitUntil(() -> {
					Island a = islands.grid().findById(islandAId.get()).orElse(null);
					Island b = islands.grid().findById(islandBId.get()).orElse(null);
					helper.assertTrue(
						a != null && !a.isPasting() && a.isOverworldReady()
							&& b != null && !b.isPasting() && b.isOverworldReady(),
						"Expected both test islands to finish pasting"
					);
				})
				.thenWaitUntil(() -> helper.assertTrue(
					levelProvider.findSnapshot(islandAId.get()).isPresent()
						&& levelProvider.findSnapshot(islandBId.get()).isPresent(),
					"Expected autoScanOnPasteComplete to populate initial starter levels"
				))
				.thenExecute(GameTestSupport.guarded(cleanup, () -> {
					Island a = islands.grid().findById(islandAId.get()).orElseThrow();
					Island b = islands.grid().findById(islandBId.get()).orElseThrow();
					int y = coreApi.config().island.defaultSpawnY;

					// Place scored blocks inside Island A: 3 diamond_block (300) + 10 cobble (10) + 4 dirt (0) + starter stone (2) = 312 pts
					for (int i = 1; i <= 3; i++) {
						ogOverworld.setBlock(new BlockPos(a.centerX() + i, y, a.centerZ()), Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);
					}
					for (int i = 1; i <= 10; i++) {
						ogOverworld.setBlock(new BlockPos(a.centerX() - i, y, a.centerZ()), Blocks.COBBLESTONE.defaultBlockState(), 18);
					}
					for (int i = 1; i <= 4; i++) {
						ogOverworld.setBlock(new BlockPos(a.centerX(), y, a.centerZ() + i), Blocks.DIRT.defaultBlockState(), 18);
					}
					// Place a beacon strictly outside Island A bounds -> must be ignored
					BlockPos outside = new BlockPos(a.maxX() + 1, y, a.centerZ());
					outOfBoundsBeaconPos.set(outside);
					ogOverworld.setBlock(outside, Blocks.BEACON.defaultBlockState(), 18);

					// Place 1 netherite_block in unready Nether -> must be skipped until STATE_NETHER_READY is set
					BlockPos netherPos = new BlockPos(a.centerX(), y, a.centerZ());
					netherBlockPos.set(netherPos);
					ogNether.setBlock(netherPos, Blocks.NETHERITE_BLOCK.defaultBlockState(), 18);

					// Place 1 diamond_block inside Island B (+ starter stone = 102 pts)
					ogOverworld.setBlock(new BlockPos(b.centerX() + 1, y, b.centerZ()), Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);

					calculatedEvents.clear();
					server.getCommands().performPrefixedCommand(ownerA.createCommandSourceStack(), "is level");
					server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "isadmin level scan LevelOwnerB");
				}))
				.thenWaitUntil(() -> {
					Optional<IslandLevelSnapshot> aSnap = levelProvider.findSnapshot(islandAId.get());
					Optional<IslandLevelSnapshot> bSnap = levelProvider.findSnapshot(islandBId.get());
					helper.assertTrue(
						aSnap.isPresent() && aSnap.get().blockPoints() == 312L
							&& bSnap.isPresent() && bSnap.get().blockPoints() == 102L,
						"Expected Island A = 312 pts and Island B = 102 pts after /is level & /isadmin level scan (actual A="
							+ aSnap.map(s -> s.blockPoints() + "pts").orElse("empty")
							+ ", B=" + bSnap.map(s -> s.blockPoints() + "pts").orElse("empty")
							+ ")"
					);
				})
				.thenExecute(GameTestSupport.guarded(cleanup, () -> {
					IslandLevelSnapshot aSnap = levelProvider.findSnapshot(islandAId.get()).orElseThrow();
					IslandLevelSnapshot bSnap = levelProvider.findSnapshot(islandBId.get()).orElseThrow();
					helper.assertTrue(aSnap.levelValue() == 3L && aSnap.pointsToNextLevel() == 88L, "Expected Level 3 (88 to next)");
					helper.assertTrue(bSnap.levelValue() == 1L && bSnap.pointsToNextLevel() == 98L, "Expected Level 1 (98 to next)");
					helper.assertTrue(
						levelProvider.rankOf(islandAId.get()).orElse(-1) == 1
							&& levelProvider.rankOf(islandBId.get()).orElse(-1) == 2,
						"Expected Island A rank #1 and Island B rank #2"
					);
					helper.assertTrue(
						calculatedEvents.stream().anyMatch(e -> e.islandId().equals(islandAId.get()) && e.newLevel() == 3L),
						"Expected IslandLevelCalculatedEvent published for Island A"
					);

					// Verify level.db persisted v2 breakdown JSON
					ModuleDatabase levelDb = coreApi.databases().require("level");
					levelDb.flush().join();
					String storedJson = levelDb.queryBlocking(conn -> {
						try (var ps = conn.prepareStatement("SELECT top_blocks_json FROM island_levels WHERE island_id = ?")) {
							ps.setString(1, islandAId.get().toString());
							try (ResultSet rs = ps.executeQuery()) {
								return rs.next() ? rs.getString("top_blocks_json") : "";
							}
						}
					});
					helper.assertTrue(storedJson.contains("minecraft:diamond_block"), "Expected top_blocks_json in level.db");

					// Mark Nether ready -> rescanning includes Nether netherite_block (+400 -> 712 pts / Level 7)
					Island a = islands.grid().findById(islandAId.get()).orElseThrow();
					a.setStateBit(Island.STATE_NETHER_READY, true);
					server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "isadmin level scan LevelOwnerA");
				}))
				.thenWaitUntil(() -> {
					IslandLevelSnapshot snap = levelProvider.findSnapshot(islandAId.get()).orElse(null);
					helper.assertTrue(snap != null && snap.blockPoints() == 712L, "Expected 712 pts with Nether ready");
				})
				.thenExecute(GameTestSupport.guarded(cleanup, () -> {
					// Test config reload + block value override + scoring cap via /isadmin level reload
					try {
						Files.writeString(levelConfigPath, """
							{
							  "formula": { "pointsPerLevel": 100, "defaultBlockValue": 0 },
							  "scan": { "cooldownSeconds": 30, "chunksPerTick": 16, "maxNanosPerTick": 4000000, "includeUnloadedChunks": true, "maxInFlightDiskReads": 8, "autoScanOnPasteComplete": true },
							  "top": { "maxCachedEntries": 100, "chatEntriesPerPage": 10, "detailTopBlocksLimit": 14 },
							  "blockValues": {
							    "minecraft:diamond_block": 200,
							    "minecraft:cobblestone": 1,
							    "minecraft:stone": 2,
							    "minecraft:netherite_block": 400
							  },
							  "blockPointLimits": {
							    "minecraft:cobblestone": 5
							  }
							}
							""");
					} catch (Exception ex) {
						throw new RuntimeException(ex);
					}
					server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "isadmin level reload");
					server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "isadmin level scan LevelOwnerA");
				}))
				.thenWaitUntil(() -> {
					IslandLevelSnapshot snap = levelProvider.findSnapshot(islandAId.get()).orElse(null);
					// 3*200 + min(10,5)*1 + 2 + 400 = 1007 pts => Level 10
					helper.assertTrue(snap != null && snap.blockPoints() == 1007L && snap.levelValue() == 10L,
						"Expected reloaded config + cobble cap -> 1007 pts / Level 10");
				})
				.thenExecute(GameTestSupport.guarded(cleanup, () -> {
					IslandLevelSnapshot snap = levelProvider.findSnapshot(islandAId.get()).orElseThrow();
					BlockPointBreakdown cobble = snap.topBlocks().stream()
						.filter(b -> b.blockId().equals(Identifier.parse("minecraft:cobblestone")))
						.findFirst()
						.orElse(null);
					helper.assertTrue(
						cobble != null && cobble.rawCount() == 10 && cobble.countedForPoints() == 5 && cobble.pointsEarned() == 5L,
						"Expected cobblestone breakdown raw=10, counted=5, pts=5"
					);

					if (!FabricLoader.getInstance().isModLoaded("ogsky-panel")) {
						// Verify chat fallback when ogsky-panel is absent (-Pmodules=core,level)
						helper.assertTrue(!coreApi.guis().isAvailable(), "Expected GuiService unavailable without ogsky-panel");
						int res;
						int valuesRes;
						int searchRes;
						GameTestSupport.drainOutboundPackets(ownerAChannel.get());
						try {
							res = server.getCommands().getDispatcher().execute("is top", ownerA.createCommandSourceStack());
							valuesRes = server.getCommands().getDispatcher().execute("is values", ownerA.createCommandSourceStack());
							searchRes = server.getCommands().getDispatcher().execute("is values search diamond", ownerA.createCommandSourceStack());
						} catch (Exception ex) {
							throw new RuntimeException(ex);
						}
						helper.assertTrue(res > 0, "Expected /is top chat fallback command to succeed");
						helper.assertTrue(valuesRes > 0, "Expected /is values chat fallback command to succeed");
						helper.assertTrue(searchRes > 0, "Expected /is values search chat fallback command to succeed");
						List<String> chatMessages = GameTestSupport.drainOutboundPackets(ownerAChannel.get()).stream()
							.filter(ClientboundSystemChatPacket.class::isInstance)
							.map(ClientboundSystemChatPacket.class::cast)
							.map(p -> p.content().getString())
							.toList();
						helper.assertTrue(
							chatMessages.stream().anyMatch(m -> m.contains("Island Block Values") && m.contains("100 pts/level")),
							"Expected /is values chat header to show formula"
						);
						helper.assertTrue(
							chatMessages.stream().anyMatch(m -> m.contains("Block of Diamond") && m.contains("+200 pts")),
							"Expected /is values chat list to show reloaded Diamond Block value (+200 pts)"
						);
						helper.assertTrue(
							chatMessages.stream().anyMatch(m -> m.contains("Cobblestone") && m.contains("Max: 5 blocks")),
							"Expected /is values chat list to show Cobblestone scoring cap (Max: 5 blocks)"
						);

						IslandActionDescriptor topAction = coreApi.actions()
							.find(Identifier.fromNamespaceAndPath("ogsky_level", "island_top"))
							.orElseThrow();
						topAction.handler().execute(new IslandActionContext(ownerA, islandAId.get()));
						IslandActionDescriptor valuesAction = coreApi.actions()
							.find(Identifier.fromNamespaceAndPath("ogsky_level", "block_values"))
							.orElseThrow();
						valuesAction.handler().execute(new IslandActionContext(ownerA, islandAId.get()));
					} else {
						// Open /is panel to verify slot 8 contributor & then /is top -> detail screen navigation
						server.getCommands().performPrefixedCommand(ownerA.createCommandSourceStack(), "is panel");
					}
				}))
				.thenWaitUntil(() -> {
					if (!FabricLoader.getInstance().isModLoaded("ogsky-panel")) {
						return;
					}
					helper.assertTrue(ownerA.containerMenu instanceof ChestMenu, "Expected /is panel ChestMenu to open");
				})
				.thenExecute(GameTestSupport.guarded(cleanup, () -> {
					if (FabricLoader.getInstance().isModLoaded("ogsky-panel")) {
						ChestMenu panelMenu = (ChestMenu) ownerA.containerMenu;
						ItemLore summaryLore = panelMenu.getSlot(8).getItem().get(DataComponents.LORE);
						helper.assertTrue(
							summaryLore != null && summaryLore.lines().stream()
								.anyMatch(l -> l.getString().contains("Island Level: 10")),
							"Expected /is panel slot 8 summary line to show Island Level: 10"
						);
						ownerA.closeContainer();

						// Open /is top GUI screen
						server.getCommands().performPrefixedCommand(ownerA.createCommandSourceStack(), "is top");
						helper.assertTrue(
							ownerA.containerMenu instanceof ChestMenu topMenu && topMenu.slots.size() == 90,
							"Expected /is top to open 6-row PAGED_GRID screen"
						);
						ChestMenu topMenu = (ChestMenu) ownerA.containerMenu;
						helper.assertTrue(
							topMenu.getSlot(10).getItem().get(DataComponents.CUSTOM_NAME).getString().contains("#1 LevelOwnerA"),
							"Expected #1 LevelOwnerA head in slot 10 of /is top"
						);

						// Click #1 island -> opens 4-row CUSTOM_FIXED breakdown screen (36 + 36 = 72 slots)
						topMenu.clicked(10, 0, ContainerInput.PICKUP, ownerA);
						helper.assertTrue(
							ownerA.containerMenu instanceof ChestMenu detailMenu && detailMenu.slots.size() == 72,
							"Expected clicking leaderboard entry to open 4-row breakdown menu"
						);
						ChestMenu detailMenu = (ChestMenu) ownerA.containerMenu;
						helper.assertTrue(
							detailMenu.getSlot(10).getItem().is(Items.DIAMOND_BLOCK),
							"Expected highest contributing block (DIAMOND_BLOCK) in slot 10 of breakdown"
						);

						// Slot 33 Block Point Values navigates to values screen and Back returns cleanly
						detailMenu.clicked(33, 0, ContainerInput.PICKUP, ownerA);
						helper.assertTrue(
							ownerA.containerMenu instanceof ChestMenu dvMenu && dvMenu.slots.size() == 90,
							"Expected Block Point Values footer button to open 6-row PAGED_GRID values menu"
						);
						ChestMenu detailValuesMenu = (ChestMenu) ownerA.containerMenu;
						detailValuesMenu.clicked(45, 0, ContainerInput.PICKUP, ownerA);
						helper.assertTrue(
							ownerA.containerMenu instanceof ChestMenu retDetail && retDetail.slots.size() == 72,
							"Expected Back from values screen to return to 4-row detail breakdown menu"
						);
						ChestMenu returnedDetail = (ChestMenu) ownerA.containerMenu;

						// Slot 27 Back returns cleanly to /is top
						returnedDetail.clicked(27, 0, ContainerInput.PICKUP, ownerA);
						helper.assertTrue(
							ownerA.containerMenu instanceof ChestMenu backTop && backTop.slots.size() == 90,
							"Expected Back button to reopen 6-row /is top screen"
						);
						ownerA.closeContainer();

						// Direct /is values GUI opens sorted PAGED_GRID with formula, sort toggle, and search filter
						server.getCommands().performPrefixedCommand(ownerA.createCommandSourceStack(), "is values");
						helper.assertTrue(
							ownerA.containerMenu instanceof ChestMenu valuesMenu && valuesMenu.slots.size() == 90,
							"Expected /is values to open 6-row PAGED_GRID screen"
						);
						ChestMenu valuesMenu = (ChestMenu) ownerA.containerMenu;
						ItemLore formulaLore = valuesMenu.getSlot(4).getItem().get(DataComponents.LORE);
						helper.assertTrue(
							formulaLore != null && formulaLore.lines().stream()
								.anyMatch(l -> l.getString().contains("100 pts = 1 level")),
							"Expected /is values slot 4 to show 100 pts = 1 level formula"
						);
						helper.assertTrue(
							valuesMenu.getSlot(10).getItem().is(Items.NETHERITE_BLOCK)
								&& valuesMenu.getSlot(11).getItem().is(Items.DIAMOND_BLOCK),
							"Expected /is values highest points first: NETHERITE_BLOCK (400) then DIAMOND_BLOCK (200)"
						);
						ItemLore cobbleLore = valuesMenu.getSlot(13).getItem().get(DataComponents.LORE);
						helper.assertTrue(
							valuesMenu.getSlot(13).getItem().is(Items.COBBLESTONE)
								&& cobbleLore != null
								&& cobbleLore.lines().stream().anyMatch(l -> l.getString().contains("Max 5 blocks")),
							"Expected /is values cobblestone slot to display scoring cap Max 5 blocks"
						);

						// Click slot 47 context hopper -> Alphabetical (A-Z) places Block of Diamond before Block of Netherite
						valuesMenu.clicked(47, 0, ContainerInput.PICKUP, ownerA);
						helper.assertTrue(
							valuesMenu.getSlot(47).getItem().get(DataComponents.CUSTOM_NAME).getString().contains("Alphabetical")
								&& valuesMenu.getSlot(10).getItem().is(Items.DIAMOND_BLOCK),
							"Expected /is values sort toggle to order Block of Diamond before Block of Netherite alphabetically"
						);
						ownerA.closeContainer();

						// /is values search works & islandless player can open /is values GUI cleanly
						server.getCommands().performPrefixedCommand(ownerA.createCommandSourceStack(), "is values search diamond");
						helper.assertTrue(
							ownerA.containerMenu instanceof ChestMenu filteredMenu
								&& filteredMenu.slots.size() == 90
								&& filteredMenu.getSlot(10).getItem().is(Items.DIAMOND_BLOCK)
								&& filteredMenu.getSlot(11).getItem().is(Items.STAINED_GLASS_PANE.gray()),
							"Expected /is values search diamond to filter grid to DIAMOND_BLOCK"
						);
						ownerA.closeContainer();

						server.getCommands().performPrefixedCommand(islandlessViewer.createCommandSourceStack(), "is values");
						helper.assertTrue(
							islandlessViewer.containerMenu instanceof ChestMenu noIslandValues && noIslandValues.slots.size() == 90,
							"Expected islandless player /is values to open 6-row GUI cleanly"
						);
						islandlessViewer.closeContainer();
					}

					// Delete Island A and confirm level.db + rank update cleanly
					Island islandARef = islands.grid().findById(islandAId.get()).orElseThrow();
					int aGridX = islandARef.gridX();
					int aGridZ = islandARef.gridZ();
					int aMinX = islandARef.minX();
					int aMaxX = islandARef.maxX();
					int aMinZ = islandARef.minZ();
					int aMaxZ = islandARef.maxZ();
					coreApi.lifecycle().deleteIsland(null, islandAId.get(), ownerAId, IslandPreDeleteEvent.DeleteReason.ADMIN);
					GameTestSupport.clearLoadedBounds(server, aMinX, aMaxX, aMinZ, aMaxZ);
					GameTestSupport.cleanupTestIsland(coreApi, islands, islandAId.get(), aGridX, aGridZ);
					helper.assertTrue(levelProvider.findSnapshot(islandAId.get()).isEmpty(), "Expected deleted island removed from cache");
					helper.assertTrue(levelProvider.rankOf(islandBId.get()).orElse(-1) == 1, "Expected Island B promoted to #1");

					cleanup.run();
				}))
				.thenSucceed();
		});
	}
}
