package me.ogsammaenr.ogsky.panel;

import me.ogsammaenr.ogsky.core.api.gui.GuiButton;
import me.ogsammaenr.ogsky.core.api.gui.GuiOpenRequest;
import me.ogsammaenr.ogsky.core.api.gui.GuiScreenModel;
import me.ogsammaenr.ogsky.core.api.gui.GuiService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Hardened read-only {@link net.minecraft.world.inventory.ChestMenu} renderer for any module-defined
 * {@link GuiScreenModel} registered through {@link me.ogsammaenr.ogsky.core.api.gui.GuiService}.
 */
public final class OgskyCustomGuiMenu extends OgskyReadOnlyChestMenu {
	private final PanelGuiHost host;
	private PanelGuiHost.RenderedScreen view;

	public OgskyCustomGuiMenu(
		int containerId,
		Inventory playerInventory,
		PanelGuiHost host,
		PanelGuiHost.RenderedScreen initialView
	) {
		super(containerId, playerInventory, initialView.model().rows());
		this.host = Objects.requireNonNull(host, "host");
		this.view = Objects.requireNonNull(initialView, "initialView");
		this.host.registerOpenMenu(this);
		renderView(initialView, viewer().level().getServer(), null);
	}

	public GuiOpenRequest request() {
		return view.request();
	}

	public PanelGuiHost.RenderedScreen view() {
		return view;
	}

	public void renderView(
		PanelGuiHost.RenderedScreen nextView,
		MinecraftServer server,
		@Nullable String pendingConfirmKey
	) {
		this.view = Objects.requireNonNull(nextView, "nextView");
		GuiScreenModel model = nextView.model();
		Container container = getContainer();
		fillBackground(container);

		if (model.layoutMode() == GuiScreenModel.LayoutMode.PAGED_GRID) {
			renderPinnedButtons(container, server, model.pinnedButtons(), pendingConfirmKey);
			renderPagedContent(container, server, model, nextView.pagedSlice(), pendingConfirmKey);
			renderPagedFooter(container, server, nextView, pendingConfirmKey);
		} else {
			renderPinnedButtons(container, server, model.pinnedButtons(), pendingConfirmKey);
		}

		setCarried(ItemStack.EMPTY);
		broadcastChanges();
	}

	private void renderPinnedButtons(
		Container container,
		MinecraftServer server,
		Map<Integer, GuiButton> pinned,
		@Nullable String pendingConfirmKey
	) {
		for (Map.Entry<Integer, GuiButton> entry : pinned.entrySet()) {
			int slot = entry.getKey();
			if (slot >= 0 && slot < menuSize()) {
				container.setItem(slot, toItemStack(entry.getValue(), server, pendingConfirmKey));
			}
		}
	}

	private void renderPagedContent(
		Container container,
		MinecraftServer server,
		GuiScreenModel model,
		List<GuiButton> slice,
		@Nullable String pendingConfirmKey
	) {
		if (slice.isEmpty()) {
			if (model.emptyStateButton() != null) {
				container.setItem(22, toItemStack(model.emptyStateButton(), server, pendingConfirmKey));
			}
			return;
		}
		for (int i = 0; i < slice.size() && i < CONTENT_SLOTS.length; i++) {
			container.setItem(CONTENT_SLOTS[i], toItemStack(slice.get(i), server, pendingConfirmKey));
		}
	}

	private void renderPagedFooter(
		Container container,
		MinecraftServer server,
		PanelGuiHost.RenderedScreen screen,
		@Nullable String pendingConfirmKey
	) {
		GuiScreenModel model = screen.model();
		GuiOpenRequest request = screen.request();
		if (request.backRequest() != null) {
			boolean toMainPanel = GuiService.MAIN_PANEL_SCREEN_ID.equals(request.backScreenId());
			container.setItem(
				SLOT_CLOSE,
				buildItem(
					Items.OAK_DOOR,
					line(toMainPanel ? "Back to Control Panel" : "Back", ChatFormatting.YELLOW),
					List.of(line("Click to return", ChatFormatting.GRAY)),
					false
				)
			);
		} else {
			container.setItem(
				SLOT_CLOSE,
				buildItem(
					Items.BARRIER,
					line("Close", ChatFormatting.RED),
					List.of(line("Click to close menu", ChatFormatting.GRAY)),
					false
				)
			);
		}

		if (model.contextButton() != null) {
			container.setItem(SLOT_CONTEXT_CONTROL, toItemStack(model.contextButton(), server, pendingConfirmKey));
		}

		if (screen.hasPreviousPage()) {
			container.setItem(
				SLOT_PREV_PAGE,
				buildItem(
					Items.ARROW,
					line("Previous Page", ChatFormatting.YELLOW),
					List.of(line("Go to page " + screen.page(), ChatFormatting.GRAY)),
					false
				)
			);
		}

		container.setItem(
			SLOT_PAGE_INDICATOR,
			buildItem(
				Items.PAPER,
				line("Page " + (screen.page() + 1) + " / " + screen.totalPages(), ChatFormatting.WHITE),
				List.of(
					line("Entries: " + model.pagedEntries().size(), ChatFormatting.GRAY),
					line("Click to refresh", ChatFormatting.YELLOW)
				),
				false
			)
		);

		if (screen.hasNextPage()) {
			container.setItem(
				SLOT_NEXT_PAGE,
				buildItem(
					Items.ARROW,
					line("Next Page", ChatFormatting.YELLOW),
					List.of(line("Go to page " + (screen.page() + 2), ChatFormatting.GRAY)),
					false
				)
			);
		}

		container.setItem(
			SLOT_REFRESH,
			buildItem(
				Items.CLOCK,
				line("Refresh", ChatFormatting.GREEN),
				List.of(line("Reload latest screen data", ChatFormatting.GRAY)),
				false
			)
		);
	}

	private static ItemStack toItemStack(
		GuiButton button,
		MinecraftServer server,
		@Nullable String pendingConfirmKey
	) {
		boolean awaitingConfirm = button.confirmSpec() != null
			&& button.confirmSpec().targetKey().equals(pendingConfirmKey);
		List<Component> lore = new ArrayList<>(button.lore().size() + (awaitingConfirm ? 1 : 0));
		lore.addAll(button.lore());
		if (awaitingConfirm) {
			lore.add(button.confirmSpec().promptLine());
		}
		boolean glint = button.glowing() || awaitingConfirm;
		if (button.playerHeadUuid() != null && button.iconItemId().endsWith("player_head")) {
			return buildPlayerHead(server, button.playerHeadUuid(), button.count(), button.name(), lore, glint);
		}
		return buildItem(button.iconItemId(), button.count(), button.name(), lore, glint);
	}

	@Override
	protected void onMenuSlotClick(ServerPlayer player, int slotIndex, int buttonNum, ContainerInput containerInput) {
		host.handleClick(this, player, slotIndex, buttonNum, containerInput);
	}

	@Override
	protected void onMenuClosed(Player player) {
		host.onMenuClosed(this);
	}

	@Override
	public boolean stillValid(Player player) {
		if (!player.isAlive() || player.isRemoved()) {
			return false;
		}
		return host.isViewerAllowed(view.request().islandId(), player.getUUID());
	}
}
