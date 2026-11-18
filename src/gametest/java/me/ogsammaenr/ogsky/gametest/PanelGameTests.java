package me.ogsammaenr.ogsky.gametest;

import io.netty.channel.embedded.EmbeddedChannel;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.db.ModuleDatabase;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.internal.OgskyCoreApiImpl;
import me.ogsammaenr.ogsky.core.internal.flag.BuiltinFlags;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandService;
import me.ogsammaenr.ogsky.core.internal.island.Island;
import me.ogsammaenr.ogsky.core.internal.world.OgskyDimensions;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Interactive Control Panel ({@code ogsky-panel}) commands, anti-dupe packet guards, and live refresh GameTests.
 */
public final class PanelGameTests {
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
		ServerPlayer owner = GameTestSupport.createOnlineMockPlayer(server, ogOverworld, ownerId, "PanelOwner", GameType.SURVIVAL);
		ServerPlayer member = GameTestSupport.createOnlineMockPlayer(server, ogOverworld, memberId, "PanelMember", GameType.SURVIVAL);
		owner.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
		owner.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD, 1));

		AtomicReference<UUID> createdIslandId = new AtomicReference<>();
		Runnable cleanup = () -> {
			GameTestSupport.closeContainersQuietly(owner, member);
			GameTestSupport.deleteAndCleanupIsland(server, coreApi, islands, createdIslandId.get(), ownerId);
			GameTestSupport.deleteDirectoryRecursively(bundleTestDir);
			GameTestSupport.deleteDirectoryRecursively(bundleAltDir);
			coreApi.blueprints().registry().reload();
			server.getPlayerList().remove(owner);
			server.getPlayerList().remove(member);
		};

		GameTestSupport.runWithCleanup(cleanup, () -> {
			GameTestSupport.synthesizeSimpleBlueprintBundle(ogOverworld, bundleTestDir, "panel_test", "Panel Test", 1);
			GameTestSupport.synthesizeSimpleBlueprintBundle(ogOverworld, bundleAltDir, "panel_alt", "Panel Alt", 2);
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
				.thenExecute(GameTestSupport.guarded(cleanup, () -> {
					ChestMenu menu = (ChestMenu) owner.containerMenu;
					helper.assertTrue(
						Boolean.TRUE.equals(menu.getSlot(4).getItem().get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)),
						"Expected islandless player to land on Blueprints & Info tab (slot 4)"
					);

					// Step 2: Verify ChestMenu immunity to vanilla dupe/swap/quick-move/clone/throw packets
					int testBpSlot = GameTestSupport.findContentSlotByName(menu, "Panel Test");
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

					// Step 3: Click Panel Test blueprint -> opens 3D Preview Detail screen -> 2-click Create Island confirm
					menu.clicked(testBpSlot, 0, ContainerInput.PICKUP, owner);
					helper.assertTrue(
						islands.grid().findPrimaryIsland(ownerId).isEmpty() && owner.containerMenu instanceof ChestMenu,
						"Expected blueprint card click to open blueprint preview ChestMenu before creating"
					);
					ChestMenu createPreviewMenu = (ChestMenu) owner.containerMenu;
					int createConfirmSlot = GameTestSupport.findContainerSlotByName(createPreviewMenu, "Create Island");
					helper.assertTrue(createConfirmSlot >= 0, "Expected Create Island action slot inside preview menu");
					createPreviewMenu.clicked(createConfirmSlot, 0, ContainerInput.PICKUP, owner);
					helper.assertTrue(
						islands.grid().findPrimaryIsland(ownerId).isEmpty(),
						"First click on Create Island must require confirmation"
					);
					helper.assertTrue(
						Boolean.TRUE.equals(createPreviewMenu.getSlot(createConfirmSlot).getItem().get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)),
						"Pending create confirmation must highlight with glint"
					);
					createPreviewMenu.clicked(createConfirmSlot, 0, ContainerInput.PICKUP, owner);
					Island created = islands.grid().findPrimaryIsland(ownerId).orElse(null);
					helper.assertTrue(created != null, "Expected second click on Create Island to create island");
					createdIslandId.set(created.islandId());
					helper.assertTrue("panel_test".equals(created.bundleId()), "Expected created island to use panel_test bundle");
					helper.assertTrue(!(owner.containerMenu instanceof ChestMenu), "Expected menu to close after creation");
					for (int t = 0; t < 20 && created.isPasting(); t++) {
						coreApi.blueprints().tick(server);
					}
					coreApi.lifecycle().resizeIsland(created.islandId(), 16);

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

					int breakSlot = GameTestSupport.findContentSlotByName(settingsMenu, "Break Blocks");
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
					int bannedSlot = GameTestSupport.findContentSlotByName(settingsMenu, bannedId.toString().substring(0, 8));
					helper.assertTrue(bannedSlot >= 0, "Expected banned player head in Members tab");
					settingsMenu.clicked(bannedSlot, 0, ContainerInput.PICKUP, owner);
					helper.assertTrue(!created.isBanned(bannedId), "Clicking banned head must unban player");

					int memberSlot = GameTestSupport.findContentSlotByName(settingsMenu, "PanelMember");
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
					int lockSlot = GameTestSupport.findContentSlotByName(settingsMenu, "Island Lock");
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
					int actionLockSlot = GameTestSupport.findContentSlotByName(settingsMenu, "Island Lock: LOCKED");
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
				.thenExecute(GameTestSupport.guarded(cleanup, () -> {
					ChestMenu menu = (ChestMenu) owner.containerMenu;
					Island created = islands.grid().findById(createdIslandId.get()).orElseThrow();
					int lockSlot = GameTestSupport.findContentSlotByName(menu, "Island Lock");
					helper.assertTrue(
						Boolean.TRUE.equals(menu.getSlot(lockSlot).getItem().get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)),
						"Expected OgskyEventBus live refresh to update Island Lock item glint on open ChestMenu"
					);

					// Step 5: Blueprints & Info tab -> open Panel Alt preview -> 2-step reset confirmation
					menu.clicked(4, 0, ContainerInput.PICKUP, owner);
					int altBpSlot = GameTestSupport.findContentSlotByName(menu, "Panel Alt");
					helper.assertTrue(altBpSlot >= 0, "Expected Panel Alt blueprint on Blueprints & Info tab");
					menu.clicked(altBpSlot, 0, ContainerInput.PICKUP, owner);
					helper.assertTrue(
						"panel_test".equals(created.bundleId()) && owner.containerMenu instanceof ChestMenu,
						"Clicking blueprint card must open 3D preview menu first"
					);
					ChestMenu resetPreviewMenu = (ChestMenu) owner.containerMenu;
					int resetConfirmSlot = GameTestSupport.findContainerSlotByName(resetPreviewMenu, "Reset Island");
					helper.assertTrue(resetConfirmSlot >= 0, "Expected Reset Island action slot inside preview menu");
					resetPreviewMenu.clicked(resetConfirmSlot, 0, ContainerInput.PICKUP, owner);
					helper.assertTrue("panel_test".equals(created.bundleId()), "First reset click must require confirmation");
					helper.assertTrue(
						Boolean.TRUE.equals(resetPreviewMenu.getSlot(resetConfirmSlot).getItem().get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)),
						"Pending reset target should highlight with glint"
					);
					resetPreviewMenu.clicked(resetConfirmSlot, 0, ContainerInput.PICKUP, owner);
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
		});
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
		ServerPlayer survival = GameTestSupport.createOnlineMockPlayer(
			server, ogOverworld, UUID.randomUUID(), "PanelSwapSurv", GameType.SURVIVAL, survivalChannelRef
		);
		ServerPlayer creative = GameTestSupport.createOnlineMockPlayer(
			server, ogOverworld, UUID.randomUUID(), "PanelSwapCrea", GameType.CREATIVE, creativeChannelRef
		);
		survival.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD, 1));
		survival.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 2));

		Runnable cleanup = () -> {
			GameTestSupport.closeContainersQuietly(survival, creative);
			server.getPlayerList().remove(survival);
			server.getPlayerList().remove(creative);
		};

		GameTestSupport.runWithCleanup(cleanup, () -> {
			server.getCommands().performPrefixedCommand(survival.createCommandSourceStack(), "is panel");
			server.getCommands().performPrefixedCommand(creative.createCommandSourceStack(), "is panel");

			helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(
					survival.containerMenu instanceof ChestMenu && creative.containerMenu instanceof ChestMenu,
					"Expected both Survival and Creative mock players to open Ogsky panel ChestMenu"
				))
				.thenExecute(GameTestSupport.guarded(cleanup, () -> {
					ChestMenu survMenu = (ChestMenu) survival.containerMenu;
					ChestMenu creaMenu = (ChestMenu) creative.containerMenu;
					EmbeddedChannel survCh = survivalChannelRef.get();
					EmbeddedChannel creaCh = creativeChannelRef.get();

					// Every panel slot icon must carry the hidden Ogui item marker
					ItemStack iconCopy = creaMenu.getSlot(4).getItem().copy();
					CustomData marker = iconCopy.get(DataComponents.CUSTOM_DATA);
					helper.assertTrue(
						marker != null && marker.copyTag().getBooleanOr("ogui_item", false),
						"Expected panel icon stack to carry internal ogui_item marker"
					);

					// 1. Survival: simulate pressing 'F' (ContainerInput.SWAP, button 40) on panel slot 4 and slot 45
					GameTestSupport.drainOutboundPackets(survCh);
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
					List<Packet<?>> survPackets = GameTestSupport.drainOutboundPackets(survCh);
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
					GameTestSupport.drainOutboundPackets(survCh);
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
					List<Packet<?>> hotbarPackets = GameTestSupport.drainOutboundPackets(survCh);
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
					GameTestSupport.drainOutboundPackets(creaCh);
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
					List<Packet<?>> creaPackets = GameTestSupport.drainOutboundPackets(creaCh);
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
		});
	}

	@GameTest
	public void panelHybridClientProtocolAndVanillaFakeSignSearchWork(GameTestHelper helper) {
		if (!FabricLoader.getInstance().isModLoaded("ogsky-panel")) {
			helper.succeed();
			return;
		}
		OguiPanelGameTestBridge.runHybridAndFakeSignTest(helper);
	}

	@GameTest
	public void blueprintGallery3dPreviewAndResetMenuWork(GameTestHelper helper) {
		if (!FabricLoader.getInstance().isModLoaded("ogsky-panel")) {
			helper.succeed();
			return;
		}
		OguiPanelGameTestBridge.runBlueprintGalleryAnd3DPreviewTest(helper);
	}
}
