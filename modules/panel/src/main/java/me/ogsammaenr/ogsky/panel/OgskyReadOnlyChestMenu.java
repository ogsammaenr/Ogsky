package me.ogsammaenr.ogsky.panel;

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

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared read-only, dupe-proof server-side {@link ChestMenu} superclass used by both {@link OgskyPanelMenu}
 * and custom module GUIs ({@link OgskyCustomGuiMenu}).
 */
public abstract class OgskyReadOnlyChestMenu extends ChestMenu {
	public static final int DEFAULT_ROWS = 6;
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

	private final ServerPlayer viewer;
	private final int rows;
	private final int menuSize;
	private boolean forceResyncAfterClick;
	private int pendingHotbarResync = -1;

	protected OgskyReadOnlyChestMenu(int containerId, Inventory playerInventory, int rows) {
		super(menuTypeForRows(rows), containerId, playerInventory, new SimpleContainer(Math.clamp(rows, 1, 6) * 9), Math.clamp(rows, 1, 6));
		if (!(playerInventory.player instanceof ServerPlayer serverPlayer)) {
			throw new IllegalStateException("OgskyReadOnlyChestMenu requires a ServerPlayer inventory.");
		}
		this.viewer = serverPlayer;
		this.rows = Math.clamp(rows, 1, 6);
		this.menuSize = this.rows * 9;
	}

	private static MenuType<ChestMenu> menuTypeForRows(int rows) {
		return switch (Math.clamp(rows, 1, 6)) {
			case 1 -> MenuType.GENERIC_9x1;
			case 2 -> MenuType.GENERIC_9x2;
			case 3 -> MenuType.GENERIC_9x3;
			case 4 -> MenuType.GENERIC_9x4;
			case 5 -> MenuType.GENERIC_9x5;
			default -> MenuType.GENERIC_9x6;
		};
	}

	@Override
	protected Slot addSlot(Slot slot) {
		int nextIndex = this.slots.size();
		if (nextIndex < this.rows * 9) {
			return super.addSlot(new ReadOnlySlot(slot.container, nextIndex, slot.x, slot.y));
		}
		return super.addSlot(slot);
	}

	public final ServerPlayer viewer() {
		return viewer;
	}

	public final int rowCount() {
		return rows;
	}

	public final int menuSize() {
		return menuSize;
	}

	public final int contentIndexForSlot(int slotIndex) {
		for (int i = 0; i < CONTENT_SLOTS.length; i++) {
			if (CONTENT_SLOTS[i] == slotIndex) {
				return i;
			}
		}
		return -1;
	}

	protected abstract void onMenuSlotClick(ServerPlayer player, int slotIndex, int buttonNum, ContainerInput containerInput);

	protected void onMenuClosed(Player player) {
	}

	@Override
	public final void clicked(int slotIndex, int buttonNum, ContainerInput containerInput, Player player) {
		setCarried(ItemStack.EMPTY);
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		boolean validPanelSlot = slotIndex >= 0 && slotIndex < this.menuSize;
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
		onMenuSlotClick(serverPlayer, slotIndex, buttonNum, containerInput);
		setCarried(ItemStack.EMPTY);
		if (serverPlayer.containerMenu == this) {
			broadcastChanges();
			if (containerInput == ContainerInput.QUICK_MOVE) {
				// Vanilla clients predictively transfer slot contents into player inventory on Shift-Click.
				this.forceResyncAfterClick = true;
			}
		}
	}

	public final boolean consumeForceResync() {
		boolean resync = this.forceResyncAfterClick;
		this.forceResyncAfterClick = false;
		return resync;
	}

	public final int consumePendingHotbarResync() {
		int hotbar = this.pendingHotbarResync;
		this.pendingHotbarResync = -1;
		return hotbar;
	}

	/**
	 * Immediately resynchronizes the container, cursor item, offhand slot, and player inventory.
	 */
	public final void syncClientState(ServerPlayer serverPlayer, int swappedHotbarSlot) {
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

	public static boolean hasPanelItemMarker(@Nullable ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
		return customData != null && customData.copyTag().getBooleanOr(PANEL_ITEM_MARKER_KEY, false);
	}

	protected static ItemStack markPanelItem(ItemStack stack) {
		if (!stack.isEmpty()) {
			CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(PANEL_ITEM_MARKER_KEY, true));
		}
		return stack;
	}

	@Override
	public final ItemStack quickMoveStack(Player player, int slotIndex) {
		return ItemStack.EMPTY;
	}

	@Override
	public final boolean canDragTo(Slot slot) {
		return false;
	}

	@Override
	public final boolean canTakeItemForPickAll(ItemStack carried, Slot target) {
		return false;
	}

	@Override
	public final void setSelectedBundleItemIndex(int slotIndex, int selectedItemIndex) {
		// Prevent client bundle index updates on read-only panel icons.
	}

	@Override
	public void removed(Player player) {
		setCarried(ItemStack.EMPTY);
		getContainer().clearContent();
		onMenuClosed(player);
		super.removed(player);
		if (player instanceof ServerPlayer serverPlayer && serverPlayer.isAlive()) {
			scrubLeakedPanelItems(serverPlayer.getInventory());
			syncPlayerInventoryAndOffhand(serverPlayer, -1);
		}
	}

	protected final void fillBackground(Container container) {
		ItemStack filler = createFillerPane();
		for (int i = 0; i < this.menuSize; i++) {
			container.setItem(i, filler.copy());
		}
	}

	protected static ItemStack createFillerPane() {
		ItemStack stack = new ItemStack(Items.STAINED_GLASS_PANE.gray());
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
		stack.set(DataComponents.TOOLTIP_DISPLAY, new TooltipDisplay(true, TooltipDisplay.DEFAULT.hiddenComponents()));
		return markPanelItem(stack);
	}

	protected static ItemStack buildItem(String itemId, Component name, List<Component> lore, boolean glint) {
		return buildItem(itemId, 1, name, lore, glint);
	}

	protected static ItemStack buildItem(String itemId, int count, Component name, List<Component> lore, boolean glint) {
		Identifier parsed = Identifier.tryParse(itemId);
		Item resolved = parsed == null ? Items.PAPER : BuiltInRegistries.ITEM.getOptional(parsed).orElse(Items.PAPER);
		return buildItem(resolved, count, name, lore, glint);
	}

	protected static ItemStack buildItem(Item item, Component name, List<Component> lore, boolean glint) {
		return buildItem(item, 1, name, lore, glint);
	}

	protected static ItemStack buildItem(Item item, int count, Component name, List<Component> lore, boolean glint) {
		ItemStack stack = new ItemStack(item, Math.clamp(count, 1, 99));
		stack.set(DataComponents.CUSTOM_NAME, Objects.requireNonNull(name, "name"));
		if (lore != null && !lore.isEmpty()) {
			stack.set(DataComponents.LORE, new ItemLore(lore));
		}
		if (glint) {
			stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		}
		return markPanelItem(stack);
	}

	protected static ItemStack buildPlayerHead(
		MinecraftServer server,
		UUID targetUuid,
		Component name,
		List<Component> lore,
		boolean glint
	) {
		return buildPlayerHead(server, targetUuid, 1, name, lore, glint);
	}

	protected static ItemStack buildPlayerHead(
		MinecraftServer server,
		UUID targetUuid,
		int count,
		Component name,
		List<Component> lore,
		boolean glint
	) {
		ItemStack stack = buildItem(Items.PLAYER_HEAD, count, name, lore, glint);
		ServerPlayer online = server.getPlayerList().getPlayer(targetUuid);
		ResolvableProfile profile = online != null
			? ResolvableProfile.createResolved(online.getGameProfile())
			: ResolvableProfile.createUnresolved(targetUuid);
		stack.set(DataComponents.PROFILE, profile);
		stack.set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT.withHidden(DataComponents.PROFILE, true));
		return stack;
	}

	protected static MutableComponent line(String text, ChatFormatting formatting) {
		return Component.literal(text)
			.withStyle(formatting)
			.withStyle(style -> style.withItalic(false));
	}

	/**
	 * Unmodifiable container slot blocking all vanilla transfers or item pickups.
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
