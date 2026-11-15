package me.ogsammaenr.ogsky.panel;

import me.ogsammaenr.ogsky.core.api.flag.FlagMode;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.panel.PanelController.ConfirmKind;
import me.ogsammaenr.ogsky.panel.PanelController.PendingConfirmation;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.component.TooltipDisplay;
import org.jspecify.annotations.Nullable;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-side 6-row vanilla {@link ChestMenu} rendering {@link PanelSnapshot} state.
 * Strictly read-only across all slots and immune to vanilla container transfer/swap/dupe clicks.
 */
public final class OgskyPanelMenu extends ChestMenu {
	public static final int ROWS = 6;
	public static final int MENU_SIZE = ROWS * 9;
	public static final int SLOT_LOCK_STATUS = 7;
	public static final int SLOT_ISLAND_SUMMARY = 8;
	public static final int SLOT_CLOSE = 45;
	public static final int SLOT_CONTEXT_CONTROL = 47;
	public static final int SLOT_PREV_PAGE = 48;
	public static final int SLOT_PAGE_INDICATOR = 49;
	public static final int SLOT_NEXT_PAGE = 50;
	public static final int SLOT_REFRESH = 53;

	public static final int[] CONTENT_SLOTS = {
		10, 11, 12, 13, 14, 15, 16,
		19, 20, 21, 22, 23, 24, 25,
		28, 29, 30, 31, 32, 33, 34,
		37, 38, 39, 40, 41, 42, 43
	};

	private static final String PANEL_ITEM_MARKER_KEY = "ogsky_panel_item";

	private static final DateTimeFormatter DATE_FORMATTER =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

	private final PanelController controller;
	private final ServerPlayer viewer;
	private PanelSnapshot snapshot;
	private boolean forceResyncAfterClick;
	private int pendingHotbarResync = -1;

	public OgskyPanelMenu(
		int containerId,
		Inventory playerInventory,
		PanelController controller,
		PanelSnapshot initialSnapshot
	) {
		super(MenuType.GENERIC_9x6, containerId, playerInventory, new SimpleContainer(MENU_SIZE), ROWS);
		this.controller = Objects.requireNonNull(controller, "controller");
		if (!(playerInventory.player instanceof ServerPlayer serverPlayer)) {
			throw new IllegalStateException("OgskyPanelMenu requires a ServerPlayer inventory.");
		}
		this.viewer = serverPlayer;
		this.snapshot = Objects.requireNonNull(initialSnapshot, "initialSnapshot");
		this.controller.registerOpenMenu(this);
		renderSnapshot(initialSnapshot, serverPlayer.level().getServer(), null);
	}

	@Override
	protected Slot addSlot(Slot slot) {
		int nextIndex = this.slots.size();
		if (nextIndex < MENU_SIZE) {
			return super.addSlot(new ReadOnlySlot(slot.container, nextIndex, slot.x, slot.y));
		}
		return super.addSlot(slot);
	}

	public ServerPlayer viewer() {
		return viewer;
	}

	public PanelSnapshot snapshot() {
		return snapshot;
	}

	public int contentIndexForSlot(int slotIndex) {
		for (int i = 0; i < CONTENT_SLOTS.length; i++) {
			if (CONTENT_SLOTS[i] == slotIndex) {
				return i;
			}
		}
		return -1;
	}

	public void renderSnapshot(
		PanelSnapshot nextSnapshot,
		MinecraftServer server,
		@Nullable PendingConfirmation pendingConfirm
	) {
		this.snapshot = Objects.requireNonNull(nextSnapshot, "nextSnapshot");
		Container container = getContainer();
		ItemStack filler = createFillerPane();
		for (int i = 0; i < MENU_SIZE; i++) {
			container.setItem(i, filler.copy());
		}

		renderTabBar(container, server);
		renderContent(container, server, pendingConfirm);
		renderFooter(container);
		setCarried(ItemStack.EMPTY);
		broadcastChanges();
	}

	private void renderTabBar(Container container, MinecraftServer server) {
		PanelTab[] tabs = PanelTab.values();
		for (int i = 0; i < tabs.length; i++) {
			PanelTab tab = tabs[i];
			boolean active = tab == snapshot.activeTab();
			boolean disabledNoIsland = snapshot.islandId() == null && tab != PanelTab.BLUEPRINTS_INFO;
			ChatFormatting color = active
				? ChatFormatting.AQUA
				: (disabledNoIsland ? ChatFormatting.DARK_GRAY : ChatFormatting.YELLOW);
			List<Component> lore = new ArrayList<>();
			if (active) {
				lore.add(line("Currently Viewing", ChatFormatting.GREEN));
			} else if (disabledNoIsland) {
				lore.add(line("Create an island first to unlock this tab.", ChatFormatting.RED));
			} else {
				lore.add(line("Click to open " + tab.displayName(), ChatFormatting.GRAY));
			}
			container.setItem(
				i,
				buildItem(tab.iconItemId(), line(tab.displayName(), color), lore, active)
			);
		}
		PanelSnapshot.IslandSummary summary = snapshot.island();
		if (summary != null) {
			container.setItem(SLOT_LOCK_STATUS, buildLockStatusItem(summary));
		}
		container.setItem(SLOT_ISLAND_SUMMARY, buildIslandSummaryItem(server));
	}

	private ItemStack buildLockStatusItem(PanelSnapshot.IslandSummary summary) {
		boolean locked = summary.locked();
		boolean canEdit = snapshot.viewerAdminBypass() || snapshot.viewerRole() >= IslandRole.ADMIN;
		List<Component> lore = new ArrayList<>();
		lore.add(line("State: " + (locked ? "LOCKED" : "UNLOCKED"), locked ? ChatFormatting.RED : ChatFormatting.GREEN));
		lore.add(line(
			locked ? "Visitors cannot enter your island." : "Visitors are allowed to enter your island.",
			ChatFormatting.GRAY
		));
		if (canEdit) {
			lore.add(line(locked ? "Click to UNLOCK island" : "Click to LOCK island", ChatFormatting.YELLOW));
		} else {
			lore.add(line("View only (requires Admin rank)", ChatFormatting.RED));
		}
		return buildItem(
			locked ? Items.IRON_BARS : Items.OAK_DOOR,
			line(locked ? "Island Access: LOCKED" : "Island Access: UNLOCKED", locked ? ChatFormatting.RED : ChatFormatting.GREEN),
			lore,
			locked
		);
	}

	private ItemStack buildIslandSummaryItem(MinecraftServer server) {
		PanelSnapshot.IslandSummary summary = snapshot.island();
		if (summary == null) {
			return buildItem(
				Items.BARRIER,
				line("No Skyblock Island", ChatFormatting.RED),
				List.of(
					line("You do not currently belong to an island here.", ChatFormatting.GRAY),
					line("Pick a starter blueprint below or run /is create.", ChatFormatting.YELLOW)
				),
				false
			);
		}
		List<Component> lore = new ArrayList<>();
		lore.add(line("Owner: " + summary.ownerName(), ChatFormatting.GRAY));
		lore.add(line("Grid Cell: (" + summary.gridX() + ", " + summary.gridZ() + ")", ChatFormatting.GRAY));
		lore.add(line("Center: (" + summary.centerX() + ", " + summary.centerZ() + ")", ChatFormatting.GRAY));
		lore.add(line("Size: " + summary.sideLength() + "x" + summary.sideLength() + " (halfSize=" + summary.halfSize() + ")", ChatFormatting.GRAY));
		lore.add(line("Blueprint: " + summary.bundleId(), ChatFormatting.GRAY));
		lore.add(line("Locked: " + (summary.locked() ? "Yes" : "No"), summary.locked() ? ChatFormatting.RED : ChatFormatting.GREEN));
		lore.add(line("Members: " + summary.memberCount() + " | Banned: " + summary.bannedCount(), ChatFormatting.GRAY));
		lore.add(line(
			"Dims: OW=" + yesNo(summary.overworldReady()) + " | Nether=" + yesNo(summary.netherReady()) + " | End=" + yesNo(summary.endReady()),
			ChatFormatting.DARK_AQUA
		));
		lore.add(line("Created: " + DATE_FORMATTER.format(summary.createdAt()), ChatFormatting.DARK_GRAY));
		lore.add(line("Click to open Blueprints & Info", ChatFormatting.YELLOW));
		return buildPlayerHead(
			server,
			summary.ownerId(),
			line("Island Overview", ChatFormatting.GOLD),
			lore,
			false
		);
	}

	private void renderContent(
		Container container,
		MinecraftServer server,
		@Nullable PendingConfirmation pendingConfirm
	) {
		switch (snapshot.activeTab()) {
			case ACTIONS -> {
				List<PanelSnapshot.ActionEntry> entries = snapshot.actions();
				for (int i = 0; i < entries.size() && i < CONTENT_SLOTS.length; i++) {
					container.setItem(CONTENT_SLOTS[i], buildActionItem(entries.get(i)));
				}
			}
			case PROTECTION_FLAGS -> {
				List<PanelSnapshot.ProtectionFlagEntry> entries = snapshot.protectionFlags();
				for (int i = 0; i < entries.size() && i < CONTENT_SLOTS.length; i++) {
					container.setItem(CONTENT_SLOTS[i], buildProtectionFlagItem(entries.get(i)));
				}
			}
			case SETTING_FLAGS -> {
				List<PanelSnapshot.SettingFlagEntry> entries = snapshot.settingFlags();
				for (int i = 0; i < entries.size() && i < CONTENT_SLOTS.length; i++) {
					container.setItem(CONTENT_SLOTS[i], buildSettingFlagItem(entries.get(i)));
				}
			}
			case MEMBERS -> {
				List<PanelSnapshot.MemberEntry> entries = snapshot.members();
				for (int i = 0; i < entries.size() && i < CONTENT_SLOTS.length; i++) {
					container.setItem(CONTENT_SLOTS[i], buildMemberItem(entries.get(i), server, pendingConfirm));
				}
			}
			case BLUEPRINTS_INFO -> {
				List<PanelSnapshot.BlueprintEntry> entries = snapshot.blueprints();
				if (entries.isEmpty()) {
					container.setItem(22, buildEmptyBlueprintsItem());
				} else {
					for (int i = 0; i < entries.size() && i < CONTENT_SLOTS.length; i++) {
						container.setItem(CONTENT_SLOTS[i], buildBlueprintItem(entries.get(i), pendingConfirm));
					}
				}
			}
		}
	}

	private ItemStack buildActionItem(PanelSnapshot.ActionEntry entry) {
		List<Component> lore = new ArrayList<>();
		if (entry.activeState() != null) {
			boolean active = entry.activeState();
			lore.add(line("State: " + (active ? "LOCKED" : "UNLOCKED"), active ? ChatFormatting.RED : ChatFormatting.GREEN));
		}
		if (!entry.description().isBlank()) {
			lore.add(line(entry.description(), ChatFormatting.GRAY));
		}
		lore.add(line("Action ID: " + entry.actionId(), ChatFormatting.DARK_GRAY));
		lore.add(line("Click to execute", ChatFormatting.YELLOW));
		ChatFormatting titleColor = entry.activeState() == null
			? ChatFormatting.AQUA
			: (entry.activeState() ? ChatFormatting.RED : ChatFormatting.GREEN);
		boolean glint = Boolean.TRUE.equals(entry.activeState());
		return buildItem(entry.iconItemId(), line(entry.title(), titleColor), lore, glint);
	}

	private ItemStack buildProtectionFlagItem(PanelSnapshot.ProtectionFlagEntry entry) {
		ChatFormatting rankColor = rankColor(entry.minRank());
		List<Component> lore = new ArrayList<>();
		lore.add(line("Required Rank: " + entry.minRankName(), rankColor));
		lore.add(line("Default: " + entry.defaultMinRankName() + " | Mode: " + modeLabel(entry.mode()), ChatFormatting.DARK_GRAY));
		if (entry.editable()) {
			lore.add(line("Left-click: Raise rank | Right-click: Lower rank", ChatFormatting.YELLOW));
			lore.add(line("Shift-click: Reset to default", ChatFormatting.GOLD));
		} else {
			lore.add(line("View only (requires Admin rank)", ChatFormatting.RED));
		}
		boolean modified = entry.minRank() != entry.defaultMinRank();
		return buildItem(entry.iconItemId(), line(entry.title(), ChatFormatting.GOLD), lore, modified);
	}

	private ItemStack buildSettingFlagItem(PanelSnapshot.SettingFlagEntry entry) {
		List<Component> lore = new ArrayList<>();
		lore.add(line(
			"State: " + (entry.enabled() ? "Enabled" : "Disabled"),
			entry.enabled() ? ChatFormatting.GREEN : ChatFormatting.RED
		));
		lore.add(line("Default: " + (entry.defaultValue() ? "Enabled" : "Disabled") + " | Mode: " + modeLabel(entry.mode()), ChatFormatting.DARK_GRAY));
		if (entry.editable()) {
			lore.add(line("Click: Toggle setting | Shift-click: Reset default", ChatFormatting.YELLOW));
		} else {
			lore.add(line("View only (requires Admin rank)", ChatFormatting.RED));
		}
		return buildItem(
			entry.iconItemId(),
			line(entry.title(), entry.enabled() ? ChatFormatting.GREEN : ChatFormatting.GRAY),
			lore,
			entry.enabled()
		);
	}

	private ItemStack buildMemberItem(
		PanelSnapshot.MemberEntry entry,
		MinecraftServer server,
		@Nullable PendingConfirmation pendingConfirm
	) {
		List<Component> lore = new ArrayList<>();
		ChatFormatting roleColor = switch (entry.kind()) {
			case PRIMARY_MEMBER -> rankColor(entry.role());
			case SECONDARY_HELPER -> ChatFormatting.LIGHT_PURPLE;
			case BANNED_PLAYER -> ChatFormatting.DARK_RED;
		};
		lore.add(line("Role: " + entry.roleName(), roleColor));
		lore.add(line("Category: " + switch (entry.kind()) {
			case PRIMARY_MEMBER -> "Primary Member";
			case SECONDARY_HELPER -> "Secondary Helper";
			case BANNED_PLAYER -> "Banned Player";
		}, ChatFormatting.GRAY));

		boolean confirmKick = isPending(pendingConfirm, ConfirmKind.KICK_MEMBER, entry.playerUuid().toString());
		boolean confirmBan = isPending(pendingConfirm, ConfirmKind.BAN_PLAYER, entry.playerUuid().toString());
		if (entry.canUnban()) {
			lore.add(line("Click: Unban player", ChatFormatting.GREEN));
		}
		if (entry.kind() == PanelSnapshot.MemberEntry.Kind.SECONDARY_HELPER && entry.canRemoveOrKick()) {
			lore.add(line("Click: Remove " + entry.roleName() + " role", ChatFormatting.YELLOW));
		}
		if (entry.canPromote()) {
			lore.add(line("Left-click: Promote to Admin", ChatFormatting.GREEN));
		}
		if (entry.canDemote()) {
			lore.add(line("Right-click: Demote to Member", ChatFormatting.GOLD));
		}
		if (entry.kind() == PanelSnapshot.MemberEntry.Kind.PRIMARY_MEMBER && entry.canRemoveOrKick()) {
			lore.add(line(
				confirmKick ? "CONFIRM: Shift-Left-click again to KICK!" : "Shift-Left-click: Kick member",
				confirmKick ? ChatFormatting.RED : ChatFormatting.YELLOW
			));
		}
		if (entry.canBan()) {
			lore.add(line(
				confirmBan ? "CONFIRM: Shift-Right-click again to BAN!" : "Shift-Right-click: Ban player",
				confirmBan ? ChatFormatting.DARK_RED : ChatFormatting.RED
			));
		}
		return buildPlayerHead(
			server,
			entry.playerUuid(),
			line(entry.displayName(), roleColor),
			lore,
			confirmKick || confirmBan
		);
	}

	private ItemStack buildBlueprintItem(
		PanelSnapshot.BlueprintEntry entry,
		@Nullable PendingConfirmation pendingConfirm
	) {
		boolean creating = snapshot.islandId() == null;
		boolean confirmReset = !creating && isPending(pendingConfirm, ConfirmKind.RESET_ISLAND, entry.id());
		List<Component> lore = new ArrayList<>();
		if (!entry.description().isBlank()) {
			lore.add(line(entry.description(), ChatFormatting.GRAY));
		}
		lore.add(line(
			"Dimensions: OW=" + yesNo(entry.hasOverworld()) + " | Nether=" + yesNo(entry.hasNether()) + " | End=" + yesNo(entry.hasEnd()),
			ChatFormatting.DARK_AQUA
		));
		if (entry.activeOnIsland()) {
			lore.add(line("Currently active on this island", ChatFormatting.GREEN));
		}
		if (entry.defaultBundle()) {
			lore.add(line("Default server starter blueprint", ChatFormatting.AQUA));
		}
		if (!entry.permitted()) {
			lore.add(line("Locked: missing blueprint permission", ChatFormatting.RED));
		} else if (creating) {
			lore.add(line("Click to create your island with this blueprint!", ChatFormatting.GREEN));
		} else if (snapshot.viewerRole() == IslandRole.OWNER || snapshot.viewerAdminBypass()) {
			lore.add(line(
				confirmReset ? "CONFIRM: Click again within 10s to RESET island!" : "Click to reset island to this blueprint",
				confirmReset ? ChatFormatting.RED : ChatFormatting.GOLD
			));
		} else {
			lore.add(line("Only the island owner can reset the island.", ChatFormatting.GRAY));
		}
		return buildItem(
			entry.iconItemId(),
			line(entry.displayName(), entry.activeOnIsland() ? ChatFormatting.GREEN : ChatFormatting.AQUA),
			lore,
			entry.activeOnIsland() || confirmReset
		);
	}

	private ItemStack buildEmptyBlueprintsItem() {
		List<Component> lore = new ArrayList<>();
		lore.add(line("No blueprint bundles with overworld.nbt were found.", ChatFormatting.GRAY));
		lore.add(line("Add templates under config/ogsky/blueprints/<id>/", ChatFormatting.YELLOW));
		lore.add(line("then run /isadmin reload as an operator.", ChatFormatting.YELLOW));
		return buildItem(Items.STRUCTURE_BLOCK, line("No Starter Blueprints Loaded", ChatFormatting.GOLD), lore, false);
	}

	private void renderFooter(Container container) {
		container.setItem(
			SLOT_CLOSE,
			buildItem(Items.BARRIER, line("Close Panel", ChatFormatting.RED), List.of(line("Click to close menu", ChatFormatting.GRAY)), false)
		);

		if (snapshot.activeTab() == PanelTab.PROTECTION_FLAGS || snapshot.activeTab() == PanelTab.SETTING_FLAGS) {
			boolean advanced = snapshot.flagMode() == FlagModeFilter.ADVANCED;
			container.setItem(
				SLOT_CONTEXT_CONTROL,
				buildItem(
					Items.LEVER,
					line("Mode: " + snapshot.flagMode().displayName(), ChatFormatting.AQUA),
					List.of(
						line(advanced ? "Showing Basic + Advanced flags" : "Showing Basic flags only", ChatFormatting.GRAY),
						line("Click to switch between Basic and Advanced", ChatFormatting.YELLOW)
					),
					advanced
				)
			);
		} else if (snapshot.activeTab() == PanelTab.MEMBERS) {
			container.setItem(
				SLOT_CONTEXT_CONTROL,
				buildItem(
					Items.WRITABLE_BOOK,
					line("Add & Transfer Commands", ChatFormatting.GOLD),
					List.of(
						line("/is invite <player> - Invite primary member", ChatFormatting.GRAY),
						line("/is coop <player> - Add temporary coop helper", ChatFormatting.GRAY),
						line("/is trust <player> - Add persistent trusted helper", ChatFormatting.GRAY),
						line("/is ban <player> | /is transfer <player>", ChatFormatting.GRAY),
						line("Click to print commands in chat", ChatFormatting.YELLOW)
					),
					false
				)
			);
		}

		if (snapshot.hasPreviousPage()) {
			container.setItem(
				SLOT_PREV_PAGE,
				buildItem(
					Items.ARROW,
					line("Previous Page", ChatFormatting.YELLOW),
					List.of(line("Go to page " + snapshot.page(), ChatFormatting.GRAY)),
					false
				)
			);
		}

		container.setItem(
			SLOT_PAGE_INDICATOR,
			buildItem(
				Items.PAPER,
				line("Page " + (snapshot.page() + 1) + " / " + snapshot.totalPages(), ChatFormatting.WHITE),
				List.of(
					line("Entries in tab: " + snapshot.totalEntries(), ChatFormatting.GRAY),
					line("Your rank: " + controller.roleDisplayName(snapshot.viewerRole())
						+ (snapshot.viewerAdminBypass() ? " (Admin Bypass)" : ""), ChatFormatting.DARK_AQUA),
					line("Click to refresh", ChatFormatting.YELLOW)
				),
				false
			)
		);

		if (snapshot.hasNextPage()) {
			container.setItem(
				SLOT_NEXT_PAGE,
				buildItem(
					Items.ARROW,
					line("Next Page", ChatFormatting.YELLOW),
					List.of(line("Go to page " + (snapshot.page() + 2), ChatFormatting.GRAY)),
					false
				)
			);
		}

		container.setItem(
			SLOT_REFRESH,
			buildItem(
				Items.CLOCK,
				line("Refresh", ChatFormatting.GREEN),
				List.of(line("Reload latest island state", ChatFormatting.GRAY)),
				false
			)
		);
	}

	@Override
	public void clicked(int slotIndex, int buttonNum, ContainerInput containerInput, Player player) {
		setCarried(ItemStack.EMPTY);
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		boolean validPanelSlot = slotIndex >= 0 && slotIndex < MENU_SIZE;
		boolean validAction = containerInput == ContainerInput.PICKUP || containerInput == ContainerInput.QUICK_MOVE;
		boolean validButton = buttonNum == 0 || buttonNum == 1;
		if (!validPanelSlot || !validAction || !validButton) {
			int hotbarSlot = containerInput == ContainerInput.SWAP
				&& buttonNum >= 0
				&& buttonNum < Inventory.getSelectionSize()
				? buttonNum
				: -1;
			this.forceResyncAfterClick = true;
			this.pendingHotbarResync = hotbarSlot;
			syncClientState(serverPlayer, hotbarSlot);
			return;
		}
		controller.handleClick(this, serverPlayer, slotIndex, buttonNum, containerInput);
		setCarried(ItemStack.EMPTY);
		if (serverPlayer.containerMenu == this) {
			broadcastChanges();
			if (containerInput == ContainerInput.QUICK_MOVE) {
				// Vanilla clients predictively transfer slot contents into player inventory on Shift-Click.
				this.forceResyncAfterClick = true;
			}
		}
	}

	public boolean consumeForceResync() {
		boolean resync = this.forceResyncAfterClick;
		this.forceResyncAfterClick = false;
		return resync;
	}

	public int consumePendingHotbarResync() {
		int hotbar = this.pendingHotbarResync;
		this.pendingHotbarResync = -1;
		return hotbar;
	}

	/**
	 * Immediately resynchronizes the panel container, cursor item, offhand slot, and player inventory.
	 */
	public void syncClientState(ServerPlayer serverPlayer, int swappedHotbarSlot) {
		setCarried(ItemStack.EMPTY);
		scrubLeakedPanelItems(serverPlayer.getInventory());
		if (serverPlayer.connection != null && !serverPlayer.hasDisconnected()) {
			this.sendAllDataToRemote();
			syncPlayerInventoryAndOffhand(serverPlayer, swappedHotbarSlot);
		}
	}

	/**
	 * Forces client-side resync of cursor, offhand (Inventory slot 40 &amp; InventoryMenu slot 45),
	 * optional hotbar slot, and player inventory contents.
	 */
	public static void syncPlayerInventoryAndOffhand(ServerPlayer serverPlayer, int swappedHotbarSlot) {
		if (serverPlayer.connection == null || serverPlayer.hasDisconnected()) {
			return;
		}
		serverPlayer.connection.send(new ClientboundSetCursorItemPacket(ItemStack.EMPTY));
		serverPlayer.connection.send(serverPlayer.getInventory().createInventoryUpdatePacket(Inventory.SLOT_OFFHAND));
		serverPlayer.connection.send(new ClientboundContainerSetSlotPacket(
			serverPlayer.inventoryMenu.containerId,
			serverPlayer.inventoryMenu.incrementStateId(),
			InventoryMenu.SHIELD_SLOT,
			serverPlayer.getInventory().getItem(Inventory.SLOT_OFFHAND).copy()
		));
		if (swappedHotbarSlot >= 0 && swappedHotbarSlot < Inventory.getSelectionSize()) {
			serverPlayer.connection.send(serverPlayer.getInventory().createInventoryUpdatePacket(swappedHotbarSlot));
		}
		serverPlayer.inventoryMenu.sendAllDataToRemote();
	}

	/**
	 * Clears any server-side inventory stacks stamped with the internal panel icon marker.
	 */
	public static boolean scrubLeakedPanelItems(Inventory inventory) {
		boolean scrubbed = false;
		int size = inventory.getContainerSize();
		for (int i = 0; i < size; i++) {
			if (hasPanelItemMarker(inventory.getItem(i))) {
				inventory.setItem(i, ItemStack.EMPTY);
				scrubbed = true;
			}
		}
		if (scrubbed) {
			inventory.setChanged();
		}
		return scrubbed;
	}

	public static boolean hasPanelItemMarker(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
		return customData != null && customData.copyTag().getBooleanOr(PANEL_ITEM_MARKER_KEY, false);
	}

	private static ItemStack markPanelItem(ItemStack stack) {
		if (!stack.isEmpty()) {
			CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(PANEL_ITEM_MARKER_KEY, true));
		}
		return stack;
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slotIndex) {
		return ItemStack.EMPTY;
	}

	@Override
	public boolean canDragTo(Slot slot) {
		return false;
	}

	@Override
	public boolean canTakeItemForPickAll(ItemStack carried, Slot target) {
		return false;
	}

	@Override
	public void setSelectedBundleItemIndex(int slotIndex, int selectedItemIndex) {
		// Prevent client bundle index updates on read-only panel icons.
	}

	@Override
	public boolean stillValid(Player player) {
		if (!player.isAlive() || player.isRemoved()) {
			return false;
		}
		UUID islandId = snapshot.islandId();
		return islandId == null || controller.isViewerAllowed(islandId, player.getUUID());
	}

	@Override
	public void removed(Player player) {
		setCarried(ItemStack.EMPTY);
		getContainer().clearContent();
		controller.onMenuClosed(this);
		super.removed(player);
		if (player instanceof ServerPlayer serverPlayer && serverPlayer.isAlive()) {
			scrubLeakedPanelItems(serverPlayer.getInventory());
			syncPlayerInventoryAndOffhand(serverPlayer, -1);
		}
	}

	private static boolean isPending(
		@Nullable PendingConfirmation pendingConfirm,
		ConfirmKind kind,
		String targetKey
	) {
		return pendingConfirm != null
			&& !pendingConfirm.isExpired()
			&& pendingConfirm.kind() == kind
			&& pendingConfirm.targetKey().equals(targetKey);
	}

	private static ItemStack createFillerPane() {
		ItemStack stack = new ItemStack(Items.STAINED_GLASS_PANE.gray());
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
		stack.set(DataComponents.TOOLTIP_DISPLAY, new TooltipDisplay(true, TooltipDisplay.DEFAULT.hiddenComponents()));
		return markPanelItem(stack);
	}

	private static ItemStack buildItem(String itemId, Component name, List<Component> lore, boolean glint) {
		Identifier parsed = Identifier.tryParse(itemId);
		Item resolved = parsed == null ? Items.PAPER : BuiltInRegistries.ITEM.getOptional(parsed).orElse(Items.PAPER);
		return buildItem(resolved, name, lore, glint);
	}

	private static ItemStack buildItem(Item item, Component name, List<Component> lore, boolean glint) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, name);
		if (!lore.isEmpty()) {
			stack.set(DataComponents.LORE, new ItemLore(lore));
		}
		if (glint) {
			stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		}
		return markPanelItem(stack);
	}

	private static ItemStack buildPlayerHead(
		MinecraftServer server,
		UUID targetUuid,
		Component name,
		List<Component> lore,
		boolean glint
	) {
		ItemStack stack = buildItem(Items.PLAYER_HEAD, name, lore, glint);
		ServerPlayer online = server.getPlayerList().getPlayer(targetUuid);
		ResolvableProfile profile = online != null
			? ResolvableProfile.createResolved(online.getGameProfile())
			: ResolvableProfile.createUnresolved(targetUuid);
		stack.set(DataComponents.PROFILE, profile);
		stack.set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT.withHidden(DataComponents.PROFILE, true));
		return stack;
	}

	private static MutableComponent line(String text, ChatFormatting formatting) {
		return Component.literal(text)
			.withStyle(formatting)
			.withStyle(style -> style.withItalic(false));
	}

	private static String modeLabel(FlagMode mode) {
		return mode == FlagMode.ADVANCED ? "Advanced" : "Basic";
	}

	private static String yesNo(boolean value) {
		return value ? "Yes" : "No";
	}

	private static ChatFormatting rankColor(byte rank) {
		return switch (rank) {
			case IslandRole.VISITOR -> ChatFormatting.GREEN;
			case IslandRole.COOP -> ChatFormatting.LIGHT_PURPLE;
			case IslandRole.TRUSTED -> ChatFormatting.AQUA;
			case IslandRole.MEMBER -> ChatFormatting.YELLOW;
			case IslandRole.ADMIN -> ChatFormatting.GOLD;
			case IslandRole.OWNER -> ChatFormatting.RED;
			default -> ChatFormatting.DARK_RED;
		};
	}

	/**
	 * Unmodifiable panel container slot blocking all vanilla transfers or item pickups.
	 */
	private static final class ReadOnlySlot extends Slot {
		private ReadOnlySlot(Container container, int slot, int x, int y) {
			super(container, slot, x, y);
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return false;
		}

		@Override
		public boolean mayPickup(Player player) {
			return false;
		}

		@Override
		public boolean allowModification(Player player) {
			return false;
		}

		@Override
		public Optional<ItemStack> tryRemove(int amount, int maxAmount, Player player) {
			return Optional.empty();
		}

		@Override
		public ItemStack safeTake(int amount, int maxAmount, Player player) {
			return ItemStack.EMPTY;
		}

		@Override
		public ItemStack safeClone(Player player) {
			return ItemStack.EMPTY;
		}

		@Override
		public ItemStack safeInsert(ItemStack stack, int amount) {
			return stack;
		}
	}
}
