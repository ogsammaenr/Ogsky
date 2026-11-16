package me.ogsammaenr.ogsky.gametest;

import io.netty.channel.embedded.EmbeddedChannel;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.gui.GuiButton;
import me.ogsammaenr.ogsky.core.api.gui.GuiScreenModel;
import me.ogsammaenr.ogsky.core.internal.OgskyCoreApiImpl;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandService;
import me.ogsammaenr.ogsky.core.internal.island.Island;
import me.ogsammaenr.ogsky.core.internal.island.OperationResult;
import me.ogsammaenr.ogsky.core.internal.world.OgskyDimensions;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.GameType;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared {@code core.guis()} declarative screen, back navigation, summary contributor, and host-absence fallback GameTests.
 */
public final class SharedGuiGameTests {
	@GameTest
	public void sharedGuiServiceCustomScreensBackNavSummaryAndDupeProtectionWork(GameTestHelper helper) throws Exception {
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel ogOverworld = server.getLevel(OgskyDimensions.OVERWORLD);
		helper.assertTrue(ogOverworld != null, "Expected ogsky:overworld dimension");
		OgskyCoreApiImpl coreApi = (OgskyCoreApiImpl) OgskyCore.api();

		if (!FabricLoader.getInstance().isModLoaded("ogsky-panel")) {
			helper.assertTrue(!coreApi.guis().isAvailable(), "Expected GuiService unavailable without ogsky-panel");
			ServerPlayer probe = GameTestSupport.createOnlineMockPlayer(server, ogOverworld, UUID.randomUUID(), "GuiNoHost", GameType.SURVIVAL);
			try {
				helper.assertTrue(
					!coreApi.guis().openScreen(probe, Identifier.fromNamespaceAndPath("ogsky_test", "unloaded"), null),
					"Expected GuiService.openScreen to return false safely without GuiHost"
				);
			} finally {
				server.getPlayerList().remove(probe);
			}
			helper.succeed();
			return;
		}

		helper.assertTrue(coreApi.guis().isAvailable(), "Expected GuiService.isAvailable() true when ogsky-panel is loaded");

		CoreIslandService islands = coreApi.coreIslands();
		Path blueprintsRoot = coreApi.blueprints().registry().blueprintsDir();
		Path bundleDir = blueprintsRoot.resolve("gui_test");

		UUID ownerId = UUID.randomUUID();
		UUID creativeId = UUID.randomUUID();
		AtomicReference<EmbeddedChannel> ownerChannelRef = new AtomicReference<>();
		ServerPlayer owner = GameTestSupport.createOnlineMockPlayer(server, ogOverworld, ownerId, "GuiOwner", GameType.SURVIVAL, ownerChannelRef);
		ServerPlayer creative = GameTestSupport.createOnlineMockPlayer(server, ogOverworld, creativeId, "GuiCreative", GameType.CREATIVE);
		owner.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD, 1));

		AtomicReference<UUID> createdIslandId = new AtomicReference<>();
		AtomicInteger confirmedCount = new AtomicInteger();

		Runnable cleanup = () -> {
			GameTestSupport.closeContainersQuietly(owner, creative);
			GameTestSupport.deleteAndCleanupIsland(server, coreApi, islands, createdIslandId.get(), ownerId);
			GameTestSupport.deleteDirectoryRecursively(bundleDir);
			coreApi.blueprints().registry().reload();
			server.getPlayerList().remove(owner);
			server.getPlayerList().remove(creative);
		};

		GameTestSupport.runWithCleanup(cleanup, () -> {
			GameTestSupport.synthesizeSimpleBlueprintBundle(ogOverworld, bundleDir, "gui_test", "GUI Test", 3);
			coreApi.blueprints().registry().reload();

			String runSuffix = ownerId.toString().substring(0, 8);
			Identifier summaryId = Identifier.fromNamespaceAndPath("ogsky_test", "summary_" + runSuffix);
			Identifier pagedId = Identifier.fromNamespaceAndPath("ogsky_test", "paged_" + runSuffix);
			Identifier fixedId = Identifier.fromNamespaceAndPath("ogsky_test", "fixed_" + runSuffix);

			coreApi.guis().registerSummaryContributor(summaryId, 15, ctx -> {
				if (!ctx.islandId().equals(createdIslandId.get())) {
					return List.of();
				}
				return List.of(GuiButton.nonItalic("Test Level: 77", ChatFormatting.GOLD));
			});

			coreApi.guis().registerScreen(fixedId, ctx -> GuiScreenModel.fixed("Fixed Detail", 3)
				.pinButton(13, GuiButton.of("minecraft:beacon", "Detail Beacon", ChatFormatting.AQUA))
				.pinButton(22, GuiButton.builder("minecraft:oak_door", "Back", ChatFormatting.YELLOW)
					.onClick(click -> click.goBack())
					.build())
				.build()
			);

			coreApi.guis().registerScreen(pagedId, ctx -> {
				String filter = ctx.stateOrDefault("filter", "all");
				GuiScreenModel.Builder builder = GuiScreenModel.paged("Paged Test (" + filter + ")")
					.contextButton(GuiButton.builder("minecraft:hopper", "Filter: " + filter, ChatFormatting.GOLD)
						.onClick(click -> {
							String nextFilter = "all".equals(click.stateOrDefault("filter", "all")) ? "top" : "all";
							click.refreshWithState("filter", nextFilter);
						})
						.build())
					.addPagedEntry(GuiButton.builder("minecraft:tnt", "Confirm Target", ChatFormatting.RED)
						.confirm("reset_counter", "Click again to confirm")
						.onClick(click -> {
							confirmedCount.incrementAndGet();
							click.refresh();
						})
						.build())
					.addPagedEntry(GuiButton.builder("minecraft:compass", "Open Detail", ChatFormatting.AQUA)
						.onClick(click -> click.openScreen(fixedId, click.islandId()))
						.build());

				if ("all".equals(filter)) {
					for (int i = 3; i <= 30; i++) {
						builder.addPagedEntry(GuiButton.of("minecraft:paper", "Entry " + i, ChatFormatting.WHITE));
					}
				}
				return builder.build();
			});

			helper.startSequence()
				.thenIdle(1)
				.thenExecute(GameTestSupport.guarded(cleanup, () -> {
					OperationResult<Island> createdRes = coreApi.lifecycle().createIsland(server, owner, ownerId, "gui_test");
					helper.assertTrue(createdRes.success() && createdRes.value() != null, "Expected test island creation");
					Island created = createdRes.value();
					createdIslandId.set(created.islandId());
					for (int t = 0; t < 20 && created.isPasting(); t++) {
						coreApi.blueprints().tick(server);
					}

					// Open main /is panel -> slot 8 lore must include contributed summary line
					server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "is panel");
				}))
				.thenWaitUntil(() -> helper.assertTrue(
					owner.containerMenu instanceof ChestMenu,
					"Expected /is panel to open main ChestMenu"
				))
				.thenExecute(GameTestSupport.guarded(cleanup, () -> {
					ChestMenu mainMenu = (ChestMenu) owner.containerMenu;
					ItemLore summaryLore = mainMenu.getSlot(8).getItem().get(DataComponents.LORE);
					helper.assertTrue(
						summaryLore != null && summaryLore.lines().stream()
							.anyMatch(l -> l.getString().contains("Test Level: 77")),
						"Expected slot 8 summary lore to contain IslandSummaryContributor output"
					);

					// Opening a registered screen while main panel is open attaches backRequest -> MAIN_PANEL_SCREEN_ID
					helper.assertTrue(
						coreApi.guis().openScreen(owner, pagedId, createdIslandId.get()),
						"Expected GuiService.openScreen to open registered paged screen"
					);
					helper.assertTrue(owner.containerMenu instanceof ChestMenu, "Expected custom GUI ChestMenu open");
					ChestMenu pagedMenu = (ChestMenu) owner.containerMenu;
					helper.assertTrue(pagedMenu.slots.size() == 90, "Expected 6-row menu (54 + 36 = 90 slots)");
					helper.assertTrue(
						pagedMenu.getSlot(45).getItem().is(Items.OAK_DOOR)
							&& "Back to Control Panel".equals(pagedMenu.getSlot(45).getItem().get(DataComponents.CUSTOM_NAME).getString()),
						"Expected slot 45 to display Back to Control Panel when opened from main panel"
					);

					// Anti-dupe check 1: F-swap against OgskyCustomGuiMenu leaves offhand SHIELD intact and resyncs offhand
					EmbeddedChannel ch = ownerChannelRef.get();
					GameTestSupport.drainOutboundPackets(ch);
					Int2ObjectMap<HashedStack> fSwapChanged = new Int2ObjectOpenHashMap<>();
					fSwapChanged.put(10, HashedStack.EMPTY);
					owner.connection.handleContainerClick(new ServerboundContainerClickPacket(
						pagedMenu.containerId,
						pagedMenu.getStateId(),
						(short) 10,
						(byte) 40,
						ContainerInput.SWAP,
						fSwapChanged,
						HashedStack.EMPTY
					));
					List<Packet<?>> fSwapPackets = GameTestSupport.drainOutboundPackets(ch);
					helper.assertTrue(
						owner.getOffhandItem().is(Items.SHIELD) && owner.getOffhandItem().getCount() == 1,
						"Expected offhand Shield preserved on F-swap attempt inside OgskyCustomGuiMenu"
					);
					helper.assertTrue(
						fSwapPackets.stream().anyMatch(p -> p instanceof ClientboundSetPlayerInventoryPacket inv
							&& inv.slot() == Inventory.SLOT_OFFHAND
							&& inv.contents().is(Items.SHIELD)),
						"Expected server to send offhand Shield resync packet on custom GUI F-swap"
					);

					// Anti-dupe check 2: ServerGamePacketListenerImplMixin blocks Creative packet when OgskyCustomGuiMenu is open
					helper.assertTrue(
						coreApi.guis().openScreen(creative, fixedId, null),
						"Expected creative player able to open CUSTOM_FIXED screen"
					);
					creative.connection.handleSetCreativeModeSlot(
						new ServerboundSetCreativeModeSlotPacket(InventoryMenu.SHIELD_SLOT, new ItemStack(Items.NETHERITE_BLOCK, 1))
					);
					helper.assertTrue(
						creative.getOffhandItem().isEmpty(),
						"Expected ServerboundSetCreativeModeSlotPacket blocked inside OgskyCustomGuiMenu"
					);
					creative.closeContainer();

					// Pagination: Next Page (slot 50) -> Entry 29 in slot 10 -> Previous Page (slot 48) -> Confirm Target
					pagedMenu.clicked(50, 0, ContainerInput.PICKUP, owner);
					helper.assertTrue(
						"Entry 29".equals(pagedMenu.getSlot(10).getItem().get(DataComponents.CUSTOM_NAME).getString()),
						"Expected Next Page to show Entry 29 in slot 10"
					);
					pagedMenu.clicked(48, 0, ContainerInput.PICKUP, owner);
					helper.assertTrue(
						"Confirm Target".equals(pagedMenu.getSlot(10).getItem().get(DataComponents.CUSTOM_NAME).getString()),
						"Expected Previous Page to return to Confirm Target in slot 10"
					);

					// Two-click confirmation via GuiConfirmSpec
					pagedMenu.clicked(10, 0, ContainerInput.PICKUP, owner);
					helper.assertTrue(confirmedCount.get() == 0, "First click on GuiConfirmSpec button must require confirmation");
					ItemLore pendingLore = pagedMenu.getSlot(10).getItem().get(DataComponents.LORE);
					helper.assertTrue(
						pendingLore != null && pendingLore.lines().stream()
							.anyMatch(l -> l.getString().contains("Click again to confirm")),
						"Expected confirmation prompt appended to button lore after first click"
					);
					pagedMenu.clicked(10, 0, ContainerInput.PICKUP, owner);
					helper.assertTrue(confirmedCount.get() == 1, "Second click must execute GuiButton onClick");

					// Context toggle via click.refreshWithState("filter", "top")
					pagedMenu.clicked(47, 0, ContainerInput.PICKUP, owner);
					helper.assertTrue(
						pagedMenu.getSlot(50).getItem().is(Items.STAINED_GLASS_PANE.gray()),
						"Expected Next Page arrow gone after filter reduced entries to 2"
					);

					// Navigate into nested CUSTOM_FIXED 3-row screen (slot 11: Open Detail)
					pagedMenu.clicked(11, 0, ContainerInput.PICKUP, owner);
					ChestMenu fixedMenu = (ChestMenu) owner.containerMenu;
					helper.assertTrue(fixedMenu != pagedMenu && fixedMenu.slots.size() == 63,
						"Expected 3-row CUSTOM_FIXED menu (27 + 36 = 63 slots)");
					helper.assertTrue(
						fixedMenu.getSlot(13).getItem().is(Items.BEACON)
							&& "Detail Beacon".equals(fixedMenu.getSlot(13).getItem().get(DataComponents.CUSTOM_NAME).getString()),
						"Expected fixed slot 13 pinned button"
					);

					// click.goBack() -> returns to pagedMenu (6 rows / 90 slots) with Back to Control Panel intact
					fixedMenu.clicked(22, 0, ContainerInput.PICKUP, owner);
					ChestMenu returnedPaged = (ChestMenu) owner.containerMenu;
					helper.assertTrue(returnedPaged.slots.size() == 90, "Expected goBack() to reopen 6-row paged menu");
					helper.assertTrue(
						returnedPaged.getSlot(45).getItem().is(Items.OAK_DOOR),
						"Expected Back to Control Panel preserved after returning from child screen"
					);

					// Slot 45 Back to Control Panel -> restores main OgskyPanelMenu
					returnedPaged.clicked(45, 0, ContainerInput.PICKUP, owner);
					ChestMenu restoredMain = (ChestMenu) owner.containerMenu;
					helper.assertTrue(restoredMain != returnedPaged && restoredMain.slots.size() == 90,
						"Expected Back to Control Panel to reopen main panel");
					ItemLore restoredLore = restoredMain.getSlot(8).getItem().get(DataComponents.LORE);
					helper.assertTrue(
						restoredLore != null && restoredLore.lines().stream()
							.anyMatch(l -> l.getString().contains("Test Level: 77")),
						"Expected restored main panel summary icon in slot 8"
					);

					cleanup.run();
				}))
				.thenSucceed();
		});
	}
}
