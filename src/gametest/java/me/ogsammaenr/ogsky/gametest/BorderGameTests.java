package me.ogsammaenr.ogsky.gametest;

import io.netty.channel.embedded.EmbeddedChannel;
import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.action.IslandActionContext;
import me.ogsammaenr.ogsky.core.api.action.IslandActionDescriptor;
import me.ogsammaenr.ogsky.core.api.db.ModuleDatabase;
import me.ogsammaenr.ogsky.core.internal.OgskyCoreApiImpl;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandService;
import me.ogsammaenr.ogsky.core.internal.island.Island;
import me.ogsammaenr.ogsky.core.internal.world.OgskyDimensions;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end headless GameTests for {@code ogsky-border}: single-command toggle, color switching,
 * control panel single-button toggle, cross-island/wilderness/dimension transitions, resize sync, and SQLite persistence.
 */
public final class BorderGameTests {
	@GameTest(maxTicks = 1200)
	public void borderCommandPanelButtonBoundaryTrackingAndPersistenceWork(GameTestHelper helper) throws Exception {
		if (!FabricLoader.getInstance().isModLoaded("ogsky-border")) {
			helper.succeed();
			return;
		}

		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel ogOverworld = server.getLevel(OgskyDimensions.OVERWORLD);
		ServerLevel ogNether = server.getLevel(OgskyDimensions.NETHER);
		ServerLevel vanillaOverworld = server.getLevel(Level.OVERWORLD);
		helper.assertTrue(ogOverworld != null && ogNether != null && vanillaOverworld != null, "Expected server levels");

		OgskyCoreApiImpl coreApi = (OgskyCoreApiImpl) OgskyCore.api();
		CoreIslandService islands = coreApi.coreIslands();

		Path borderConfigPath = FabricLoader.getInstance().getConfigDir().resolve("ogsky").resolve("border.json");
		String originalBorderJson = Files.exists(borderConfigPath) ? Files.readString(borderConfigPath) : null;

		Path blueprintsRoot = coreApi.blueprints().registry().blueprintsDir();
		Path bundleDir = blueprintsRoot.resolve("border_test");

		UUID ownerAId = UUID.randomUUID();
		UUID ownerBId = UUID.randomUUID();
		AtomicReference<EmbeddedChannel> ownerAChannel = new AtomicReference<>();
		AtomicReference<EmbeddedChannel> ownerBChannel = new AtomicReference<>();
		ServerPlayer ownerA = GameTestSupport.createOnlineMockPlayer(server, ogOverworld, ownerAId, "BorderOwnerA", GameType.SURVIVAL, ownerAChannel);
		ServerPlayer ownerB = GameTestSupport.createOnlineMockPlayer(server, ogOverworld, ownerBId, "BorderOwnerB", GameType.SURVIVAL, ownerBChannel);

		AtomicReference<UUID> islandAIdRef = new AtomicReference<>();
		AtomicReference<UUID> islandBIdRef = new AtomicReference<>();
		AtomicReference<BlockPos> wildernessPadRef = new AtomicReference<>();

		Runnable cleanup = () -> {
			GameTestSupport.closeContainersQuietly(ownerA, ownerB);
			try {
				if (originalBorderJson != null) {
					Files.writeString(borderConfigPath, originalBorderJson);
				} else {
					Files.deleteIfExists(borderConfigPath);
				}
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "isadmin border reload");
			} catch (Exception ignored) {
			}
			if (wildernessPadRef.get() != null) {
				GameTestSupport.clearBlockPosIfLoaded(ogOverworld, wildernessPadRef.get());
			}
			GameTestSupport.deleteAndCleanupIsland(server, coreApi, islands, islandAIdRef.get(), ownerAId);
			GameTestSupport.deleteAndCleanupIsland(server, coreApi, islands, islandBIdRef.get(), ownerBId);
			GameTestSupport.deleteDirectoryRecursively(bundleDir);
			coreApi.blueprints().registry().reload();
			server.getPlayerList().remove(ownerA);
			server.getPlayerList().remove(ownerB);
			coreApi.team().onPlayerDisconnect(server, ownerAId);
			coreApi.team().onPlayerDisconnect(server, ownerBId);
			coreApi.protectionListeners().onPlayerDisconnect(ownerAId);
			coreApi.protectionListeners().onPlayerDisconnect(ownerBId);
		};

		GameTestSupport.runWithCleanup(cleanup, () -> {
			GameTestSupport.synthesizeSimpleBlueprintBundle(ogOverworld, bundleDir, "border_test", "Border Test", 7);
			coreApi.blueprints().registry().reload();

			var resA = coreApi.islands().createIsland(server, ownerA, ownerAId, "border_test");
			var resB = coreApi.islands().createIsland(server, ownerB, ownerBId, "border_test");
			helper.assertTrue(resA.success() && resA.value() != null, "Expected island A created");
			helper.assertTrue(resB.success() && resB.value() != null, "Expected island B created");
			UUID islandAId = resA.value().islandId();
			UUID islandBId = resB.value().islandId();
			islandAIdRef.set(islandAId);
			islandBIdRef.set(islandBId);

			Island islandA = islands.grid().findById(islandAId).orElseThrow();
			Island islandB = islands.grid().findById(islandBId).orElseThrow();
			coreApi.lifecycle().resizeIsland(islandAId, 16);
			coreApi.lifecycle().resizeIsland(islandBId, 12);

			helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(
					!islandA.isPasting() && !islandB.isPasting(),
					"Expected both test islands to finish pasting"
				))
				.thenExecute(GameTestSupport.guarded(cleanup, () -> {
					int ax = islandA.centerX();
					int ay = islandA.overworldSpawn().blockY();
					int az = islandA.centerZ();
					ogOverworld.setBlock(new BlockPos(ax, ay - 1, az), Blocks.STONE.defaultBlockState(), 18);
					ownerA.teleport(new TeleportTransition(
						ogOverworld, new Vec3(ax + 0.5D, ay, az + 0.5D),
						Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.DO_NOTHING
					));

					// 1. Single command /is border toggles ON -> sends exact BLUE ClientboundInitializeBorderPacket
					GameTestSupport.drainOutboundPackets(ownerAChannel.get());
					helper.assertTrue(exec(server, ownerA, "is border") > 0, "Expected /is border toggle ON to succeed");
					ClientboundInitializeBorderPacket bluePacket = latestBorderPacket(ownerAChannel.get());
					helper.assertTrue(bluePacket != null, "Expected ClientboundInitializeBorderPacket on /is border ON");
					helper.assertTrue(
						Math.abs(bluePacket.getNewCenterX() - (ax + 0.5D)) < 1.0E-4D
							&& Math.abs(bluePacket.getNewCenterZ() - (az + 0.5D)) < 1.0E-4D
							&& Math.abs(bluePacket.getNewSize() - 33.0D) < 1.0E-4D
							&& bluePacket.getLerpTime() == 0L,
						"Expected stationary BLUE border centered on island A with size 33"
					);

					// 2. Color selection -> green (GROWING) & red (SHRINKING)
					GameTestSupport.drainOutboundPackets(ownerAChannel.get());
					helper.assertTrue(exec(server, ownerA, "is border color green") > 0, "Expected /is border color green to succeed");
					ClientboundInitializeBorderPacket greenPacket = latestBorderPacket(ownerAChannel.get());
					helper.assertTrue(
						greenPacket != null && greenPacket.getNewSize() > greenPacket.getOldSize() && greenPacket.getLerpTime() > 0L,
						"Expected GREEN border packet (newSize > oldSize, lerpTime > 0)"
					);

					GameTestSupport.drainOutboundPackets(ownerAChannel.get());
					helper.assertTrue(exec(server, ownerA, "is border color red") > 0, "Expected /is border color red to succeed");
					ClientboundInitializeBorderPacket redPacket = latestBorderPacket(ownerAChannel.get());
					helper.assertTrue(
						redPacket != null && redPacket.getNewSize() < redPacket.getOldSize() && redPacket.getLerpTime() > 0L,
						"Expected RED border packet (newSize < oldSize, lerpTime > 0)"
					);

					// Verify async SQLite persistence in border.db
					ModuleDatabase borderDb = coreApi.databases().require("border");
					borderDb.flush().join();
					BorderPrefRow row = queryPrefRow(borderDb, ownerAId);
					helper.assertTrue(row != null && row.enabled() && "red".equals(row.color()),
						"Expected border.db to persist enabled=1 and color=red, got: " + row);

					// 3. Resize event while standing inside -> border size updates automatically to 24*2+1=49
					GameTestSupport.drainOutboundPackets(ownerAChannel.get());
					helper.assertTrue(exec(server, server.createCommandSourceStack(), "isadmin setrange BorderOwnerA 24") > 0,
						"Expected setrange 24 to succeed");
					ClientboundInitializeBorderPacket resizedPacket = latestBorderPacket(ownerAChannel.get());
					helper.assertTrue(
						resizedPacket != null && Math.abs(resizedPacket.getOldSize() - 49.0D) < 1.0E-2D,
						"Expected IslandRangeChangeEvent to send resized border size 49"
					);
					exec(server, server.createCommandSourceStack(), "isadmin setrange BorderOwnerA 16");

					// 4. Move to wilderness buffer -> border resets to vanilla level WorldBorder
					BlockPos pad = new BlockPos(ax + 32, ay - 1, az + 32);
					wildernessPadRef.set(pad);
					ogOverworld.setBlock(pad, Blocks.STONE.defaultBlockState(), 18);
					GameTestSupport.drainOutboundPackets(ownerAChannel.get());
					ownerA.teleport(new TeleportTransition(
						ogOverworld, new Vec3(pad.getX() + 0.5D, pad.getY() + 1.0D, pad.getZ() + 0.5D),
						Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.DO_NOTHING
					));
				}))
				.thenIdle(2)
				.thenExecute(GameTestSupport.guarded(cleanup, () -> {
					ClientboundInitializeBorderPacket wildReset = latestBorderPacket(ownerAChannel.get());
					helper.assertTrue(
						wildReset != null && wildReset.getNewSize() > 1_000_000.0D,
						"Expected moving into wilderness buffer to reset client border to level WorldBorder"
					);

					// 5. Enter Island B -> shows Island B's border (centerB, size 25)
					GameTestSupport.drainOutboundPackets(ownerAChannel.get());
					int bx = islandB.centerX();
					int by = islandB.overworldSpawn().blockY();
					int bz = islandB.centerZ();
					ogOverworld.setBlock(new BlockPos(bx, by - 1, bz), Blocks.STONE.defaultBlockState(), 18);
					ownerA.teleport(new TeleportTransition(
						ogOverworld, new Vec3(bx + 0.5D, by, bz + 0.5D),
						Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.DO_NOTHING
					));
				}))
				.thenIdle(2)
				.thenExecute(GameTestSupport.guarded(cleanup, () -> {
					ClientboundInitializeBorderPacket islandBPacket = latestBorderPacket(ownerAChannel.get());
					helper.assertTrue(
						islandBPacket != null
							&& Math.abs(islandBPacket.getNewCenterX() - (islandB.centerX() + 0.5D)) < 1.0E-4D
							&& Math.abs(islandBPacket.getOldSize() - 25.0D) < 1.0E-2D,
						"Expected standing inside Island B to display Island B's border (size 25)"
					);

					// 6. Dimension transition into Island A's ogsky:nether -> re-applies Island A's border after sendLevelInfo
					int ax = islandA.centerX();
					int az = islandA.centerZ();
					GameTestSupport.drainOutboundPackets(ownerAChannel.get());
					ogNether.setBlock(new BlockPos(ax, 63, az), Blocks.NETHERRACK.defaultBlockState(), 18);
					ownerA.teleport(new TeleportTransition(
						ogNether, new Vec3(ax + 0.5D, 64.0D, az + 0.5D),
						Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.DO_NOTHING
					));
				}))
				.thenIdle(1)
				.thenExecute(GameTestSupport.guarded(cleanup, () -> {
					int ax = islandA.centerX();
					int az = islandA.centerZ();
					ClientboundInitializeBorderPacket netherPacket = latestBorderPacket(ownerAChannel.get());
					helper.assertTrue(
						netherPacket != null
							&& Math.abs(netherPacket.getNewCenterX() - (ax + 0.5D)) < 1.0E-4D
							&& Math.abs(netherPacket.getOldSize() - 33.0D) < 1.0E-2D,
						"Expected dimension switch into ogsky:nether inside Island A to show Island A's border"
					);

					// Transition to vanilla minecraft:overworld -> clears border
					GameTestSupport.drainOutboundPackets(ownerAChannel.get());
					ownerA.teleport(new TeleportTransition(
						vanillaOverworld, new Vec3(0.5D, 80.0D, 0.5D),
						Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.DO_NOTHING
					));
				}))
				.thenIdle(1)
				.thenExecute(GameTestSupport.guarded(cleanup, () -> {
					int ax = islandA.centerX();
					int az = islandA.centerZ();
					ClientboundInitializeBorderPacket vanillaReset = latestBorderPacket(ownerAChannel.get());
					helper.assertTrue(
						vanillaReset != null && vanillaReset.getNewSize() > 1_000_000.0D,
						"Expected leaving Skyblock dimensions to reset client border"
					);

					// Return to Island A Overworld for panel / action test
					ownerA.teleport(new TeleportTransition(
						ogOverworld, new Vec3(ax + 0.5D, islandA.overworldSpawn().blockY(), az + 0.5D),
						Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.DO_NOTHING
					));
				}))
				.thenIdle(1)
				.thenExecute(GameTestSupport.guarded(cleanup, () -> {
					// 7. Control panel single-button toggle (or direct action check if panel omitted)
					if (FabricLoader.getInstance().isModLoaded("ogsky-panel")) {
						helper.assertTrue(exec(server, ownerA, "is panel actions") > 0, "Expected /is panel actions to open");
					} else {
						IslandActionDescriptor borderAction = coreApi.actions()
							.find(Identifier.fromNamespaceAndPath("ogsky_border", "toggle_border"))
							.orElseThrow();
						GameTestSupport.drainOutboundPackets(ownerAChannel.get());
						borderAction.handler().execute(new IslandActionContext(ownerA, islandAId));
						ClientboundInitializeBorderPacket toggledOff = latestBorderPacket(ownerAChannel.get());
						helper.assertTrue(toggledOff != null && toggledOff.getNewSize() > 1_000_000.0D,
							"Expected border action toggle to turn border OFF");

						GameTestSupport.drainOutboundPackets(ownerAChannel.get());
						borderAction.handler().execute(new IslandActionContext(ownerA, islandAId));
						ClientboundInitializeBorderPacket toggledOn = latestBorderPacket(ownerAChannel.get());
						helper.assertTrue(toggledOn != null && Math.abs(toggledOn.getOldSize() - 33.0D) < 1.0E-2D,
							"Expected second border action toggle to turn border back ON");
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
						ChestMenu menu = (ChestMenu) ownerA.containerMenu;
						ItemLore summaryLore = menu.getSlot(8).getItem().get(DataComponents.LORE);
						helper.assertTrue(
							summaryLore != null && summaryLore.lines().stream().anyMatch(l -> l.getString().contains("Border: ON (Red)")),
							"Expected slot 8 summary to show Border: ON (Red)"
						);

						int borderSlot = GameTestSupport.findContentSlotByName(menu, "Island Border: ON");
						helper.assertTrue(borderSlot >= 0, "Expected single 'Island Border: ON' button in Actions tab");
						helper.assertTrue(
							Boolean.TRUE.equals(menu.getSlot(borderSlot).getItem().get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)),
							"Expected active border button to glow"
						);

						// Single click toggles border OFF and updates panel button in place
						GameTestSupport.drainOutboundPackets(ownerAChannel.get());
						menu.clicked(borderSlot, 0, ContainerInput.PICKUP, ownerA);
						ClientboundInitializeBorderPacket offPacket = latestBorderPacket(ownerAChannel.get());
						helper.assertTrue(offPacket != null && offPacket.getNewSize() > 1_000_000.0D,
							"Expected single click in panel to disable border and reset client WorldBorder");
						helper.assertTrue(
							"Island Border: OFF".equals(menu.getSlot(borderSlot).getItem().get(DataComponents.CUSTOM_NAME).getString()),
							"Expected panel button to refresh live to 'Island Border: OFF'"
						);
						helper.assertTrue(
							!Boolean.TRUE.equals(menu.getSlot(borderSlot).getItem().get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)),
							"Expected disabled border button not to glow"
						);

						// Single click again toggles border ON!
						GameTestSupport.drainOutboundPackets(ownerAChannel.get());
						menu.clicked(borderSlot, 0, ContainerInput.PICKUP, ownerA);
						ClientboundInitializeBorderPacket onPacket = latestBorderPacket(ownerAChannel.get());
						helper.assertTrue(
							onPacket != null && Math.abs(onPacket.getOldSize() - 33.0D) < 1.0E-2D,
							"Expected second click in panel to re-enable island border"
						);
						ownerA.closeContainer();
					}

					// 8. Admin commands: toggle off, inspect, reload
					GameTestSupport.drainOutboundPackets(ownerAChannel.get());
					helper.assertTrue(exec(server, server.createCommandSourceStack(), "isadmin border toggle BorderOwnerA off") > 0,
						"Expected /isadmin border toggle off to succeed");
					ClientboundInitializeBorderPacket adminOffPacket = latestBorderPacket(ownerAChannel.get());
					helper.assertTrue(adminOffPacket != null && adminOffPacket.getNewSize() > 1_000_000.0D,
						"Expected admin toggle off to clear border");
					helper.assertTrue(exec(server, server.createCommandSourceStack(), "isadmin border inspect BorderOwnerA") > 0,
						"Expected /isadmin border inspect to succeed");
					helper.assertTrue(exec(server, server.createCommandSourceStack(), "isadmin border reload") > 0,
						"Expected /isadmin border reload to succeed");

					// Verify disabled state also works cleanly with /is border status
					GameTestSupport.drainOutboundPackets(ownerAChannel.get());
					helper.assertTrue(exec(server, ownerA, "is border status") > 0, "Expected /is border status to succeed");
					List<String> statusMessages = drainChatMessages(ownerAChannel.get());
					helper.assertTrue(statusMessages.stream().anyMatch(m -> m.contains("OFF")),
						"Expected status output to report OFF, got: " + statusMessages);

					cleanup.run();
				}))
				.thenSucceed();
		});
	}

	private record BorderPrefRow(boolean enabled, String color) {
	}

	private static BorderPrefRow queryPrefRow(ModuleDatabase db, UUID playerUuid) {
		return db.queryBlocking(conn -> {
			try (var stmt = conn.prepareStatement("SELECT enabled, color FROM ogsky_border_preferences WHERE player_uuid = ?")) {
				stmt.setString(1, playerUuid.toString());
				try (ResultSet rs = stmt.executeQuery()) {
					if (!rs.next()) {
						return null;
					}
					return new BorderPrefRow(rs.getInt(1) != 0, rs.getString(2));
				}
			}
		});
	}

	private static ClientboundInitializeBorderPacket latestBorderPacket(EmbeddedChannel channel) {
		ClientboundInitializeBorderPacket last = null;
		for (Packet<?> packet : GameTestSupport.drainOutboundPackets(channel)) {
			if (packet instanceof ClientboundInitializeBorderPacket borderPacket) {
				last = borderPacket;
			}
		}
		return last;
	}

	private static List<String> drainChatMessages(EmbeddedChannel channel) {
		return GameTestSupport.drainOutboundPackets(channel).stream()
			.filter(ClientboundSystemChatPacket.class::isInstance)
			.map(ClientboundSystemChatPacket.class::cast)
			.map(p -> p.content().getString())
			.toList();
	}

	private static int exec(MinecraftServer server, ServerPlayer player, String command) {
		return exec(server, player.createCommandSourceStack(), command);
	}

	private static int exec(MinecraftServer server, net.minecraft.commands.CommandSourceStack source, String command) {
		try {
			return server.getCommands().getDispatcher().execute(command, source);
		} catch (Exception ex) {
			return 0;
		}
	}
}
