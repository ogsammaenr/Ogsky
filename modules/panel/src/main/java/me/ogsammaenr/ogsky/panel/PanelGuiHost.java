package me.ogsammaenr.ogsky.panel;

import me.ogsammaenr.ogsky.core.api.OgskyCoreApi;
import me.ogsammaenr.ogsky.core.api.event.IslandDeletedEvent;
import me.ogsammaenr.ogsky.core.api.gui.GuiBuildContext;
import me.ogsammaenr.ogsky.core.api.gui.GuiButton;
import me.ogsammaenr.ogsky.core.api.gui.GuiClickAction;
import me.ogsammaenr.ogsky.core.api.gui.GuiClickContext;
import me.ogsammaenr.ogsky.core.api.gui.GuiConfirmSpec;
import me.ogsammaenr.ogsky.core.api.gui.GuiHost;
import me.ogsammaenr.ogsky.core.api.gui.GuiOpenRequest;
import me.ogsammaenr.ogsky.core.api.gui.GuiScreenModel;
import me.ogsammaenr.ogsky.core.api.gui.GuiScreenProvider;
import me.ogsammaenr.ogsky.core.api.gui.GuiService;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ContainerInput;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link GuiHost} implementation provided by {@code ogsky-panel}, rendering any module-registered
 * {@link GuiScreenModel} through {@link OgskyCustomGuiMenu} with shared dupe protection, pagination,
 * back navigation, confirmations, and live refresh support.
 */
public final class PanelGuiHost implements GuiHost {
	private static final Duration CONFIRM_TTL = Duration.ofSeconds(10);

	public record RenderedScreen(
		GuiOpenRequest request,
		GuiScreenModel model,
		int page,
		int totalPages,
		List<GuiButton> pagedSlice
	) {
		public boolean hasPreviousPage() {
			return page > 0;
		}

		public boolean hasNextPage() {
			return page + 1 < totalPages;
		}
	}

	private record PendingConfirm(Identifier screenId, String targetKey, Instant expiresAt) {
		boolean isExpired() {
			return Instant.now().isAfter(expiresAt);
		}
	}

	private final OgskyCoreApi core;
	private final PanelController panelController;
	private final PanelPreferenceRepository preferences;
	private final Map<UUID, OgskyCustomGuiMenu> openMenus = new ConcurrentHashMap<>();
	private final Map<UUID, PendingConfirm> pendingConfirmations = new ConcurrentHashMap<>();

	public PanelGuiHost(
		OgskyCoreApi core,
		PanelController panelController,
		PanelPreferenceRepository preferences
	) {
		this.core = Objects.requireNonNull(core, "core");
		this.panelController = Objects.requireNonNull(panelController, "panelController");
		this.preferences = Objects.requireNonNull(preferences, "preferences");
	}

	public void registerEvents() {
		core.events().subscribe(IslandDeletedEvent.class, e -> closeMenusForDeletedIsland(e.islandId()));
	}

	@Override
	public boolean openScreen(ServerPlayer player, GuiOpenRequest request) {
		Objects.requireNonNull(player, "player");
		Objects.requireNonNull(request, "request");

		if (GuiService.MAIN_PANEL_SCREEN_ID.equals(request.screenId())) {
			openMainPanelFromRequest(player, request);
			return true;
		}

		Optional<GuiScreenProvider> provider = core.guis().findScreen(request.screenId());
		if (provider.isEmpty()) {
			return false;
		}

		GuiOpenRequest effectiveRequest = attachMainPanelBackIfNeeded(player, request);
		RenderedScreen view = buildView(player, provider.get(), effectiveRequest);
		pendingConfirmations.remove(player.getUUID());

		player.openMenu(new SimpleMenuProvider(
			(containerId, playerInventory, p) -> new OgskyCustomGuiMenu(containerId, playerInventory, this, view),
			view.model().title()
		));
		return true;
	}

	@Override
	public void refreshIslandScreens(UUID islandId) {
		Objects.requireNonNull(islandId, "islandId");
		if (openMenus.isEmpty()) {
			return;
		}
		for (OgskyCustomGuiMenu menu : openMenus.values()) {
			if (islandId.equals(menu.request().islandId())) {
				MinecraftServer server = menu.viewer().level().getServer();
				server.execute(() -> refreshMenusForIslandOnServerThread(islandId));
				return;
			}
		}
	}

	@Override
	public void refreshPlayerScreen(ServerPlayer player, Identifier screenId) {
		Objects.requireNonNull(player, "player");
		Objects.requireNonNull(screenId, "screenId");
		OgskyCustomGuiMenu menu = openMenus.get(player.getUUID());
		if (menu == null || player.containerMenu != menu) {
			return;
		}
		if (!screenId.equals(menu.request().screenId())) {
			return;
		}
		refreshMenu(menu, menu.request());
	}

	public void registerOpenMenu(OgskyCustomGuiMenu menu) {
		openMenus.put(menu.viewer().getUUID(), menu);
	}

	public void onMenuClosed(OgskyCustomGuiMenu menu) {
		openMenus.remove(menu.viewer().getUUID(), menu);
		pendingConfirmations.remove(menu.viewer().getUUID());
	}

	public void onPlayerDisconnect(UUID playerUuid) {
		openMenus.remove(playerUuid);
		pendingConfirmations.remove(playerUuid);
	}

	public void clearAll() {
		openMenus.clear();
		pendingConfirmations.clear();
	}

	public boolean isViewerAllowed(@Nullable UUID islandId, UUID viewerUuid) {
		if (islandId == null) {
			return true;
		}
		return core.islands().findById(islandId)
			.map(island -> !island.isBanned(viewerUuid))
			.orElse(true);
	}

	public static RenderedScreen paginateModel(GuiOpenRequest request, GuiScreenModel model) {
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(model, "model");
		if (model.layoutMode() == GuiScreenModel.LayoutMode.CUSTOM_FIXED) {
			return new RenderedScreen(request.withPage(0), model, 0, 1, List.of());
		}
		List<GuiButton> all = model.pagedEntries();
		int pageSize = GuiScreenModel.PAGED_GRID_SIZE;
		int totalPages = Math.max(1, (all.size() + pageSize - 1) / pageSize);
		int page = Math.clamp(request.page(), 0, totalPages - 1);
		int from = Math.min(page * pageSize, all.size());
		int to = Math.min(from + pageSize, all.size());
		return new RenderedScreen(request.withPage(page), model, page, totalPages, all.subList(from, to));
	}

	void handleClick(
		OgskyCustomGuiMenu menu,
		ServerPlayer player,
		int slotIndex,
		int buttonNum,
		ContainerInput input
	) {
		if (!player.getUUID().equals(menu.viewer().getUUID())) {
			return;
		}
		if (buttonNum != 0 && buttonNum != 1) {
			return;
		}

		RenderedScreen current = menu.view();
		GuiOpenRequest request = current.request();
		GuiScreenModel model = current.model();

		if (model.layoutMode() == GuiScreenModel.LayoutMode.PAGED_GRID) {
			if (slotIndex == OgskyReadOnlyChestMenu.SLOT_CLOSE) {
				PanelFeedback.playClickSound(player);
				pendingConfirmations.remove(player.getUUID());
				if (request.backRequest() != null) {
					openScreen(player, request.backRequest());
				} else {
					player.closeContainer();
				}
				return;
			}
			if (slotIndex == OgskyReadOnlyChestMenu.SLOT_PREV_PAGE && current.hasPreviousPage()) {
				PanelFeedback.playClickSound(player);
				pendingConfirmations.remove(player.getUUID());
				refreshMenu(menu, request.withPage(current.page() - 1));
				return;
			}
			if (slotIndex == OgskyReadOnlyChestMenu.SLOT_NEXT_PAGE && current.hasNextPage()) {
				PanelFeedback.playClickSound(player);
				pendingConfirmations.remove(player.getUUID());
				refreshMenu(menu, request.withPage(current.page() + 1));
				return;
			}
			if (slotIndex == OgskyReadOnlyChestMenu.SLOT_PAGE_INDICATOR || slotIndex == OgskyReadOnlyChestMenu.SLOT_REFRESH) {
				PanelFeedback.playClickSound(player);
				pendingConfirmations.remove(player.getUUID());
				refreshMenu(menu, request);
				return;
			}
			if (slotIndex == OgskyReadOnlyChestMenu.SLOT_CONTEXT_CONTROL && model.contextButton() != null) {
				dispatchButtonClick(menu, player, model.contextButton(), buttonNum, input);
				return;
			}
		}

		GuiButton pinned = model.pinnedButtons().get(slotIndex);
		if (pinned != null) {
			dispatchButtonClick(menu, player, pinned, buttonNum, input);
			return;
		}

		if (model.layoutMode() == GuiScreenModel.LayoutMode.PAGED_GRID) {
			int contentIdx = menu.contentIndexForSlot(slotIndex);
			if (contentIdx >= 0 && contentIdx < current.pagedSlice().size()) {
				dispatchButtonClick(menu, player, current.pagedSlice().get(contentIdx), buttonNum, input);
			}
		}
	}

	private void dispatchButtonClick(
		OgskyCustomGuiMenu menu,
		ServerPlayer player,
		GuiButton button,
		int buttonNum,
		ContainerInput input
	) {
		if (button.onClick() == null) {
			return;
		}
		GuiConfirmSpec confirmSpec = button.confirmSpec();
		if (confirmSpec != null) {
			String activeKey = activeConfirmKey(player.getUUID(), menu.request().screenId());
			if (!confirmSpec.targetKey().equals(activeKey)) {
				pendingConfirmations.put(
					player.getUUID(),
					new PendingConfirm(menu.request().screenId(), confirmSpec.targetKey(), Instant.now().plus(CONFIRM_TTL))
				);
				PanelFeedback.playClickSound(player);
				refreshMenu(menu, menu.request());
				return;
			}
			pendingConfirmations.remove(player.getUUID());
		}

		PanelFeedback.playClickSound(player);
		GuiClickAction action = mapClickAction(buttonNum, input);
		GuiClickContext ctx = new GuiClickContextImpl(menu, player, action);
		button.onClick().accept(ctx);
		if (openMenus.get(player.getUUID()) == menu && player.containerMenu == menu) {
			menu.broadcastChanges();
		}
	}

	private void refreshMenu(OgskyCustomGuiMenu menu, GuiOpenRequest targetRequest) {
		Optional<GuiScreenProvider> provider = core.guis().findScreen(targetRequest.screenId());
		if (provider.isEmpty()) {
			menu.viewer().closeContainer();
			return;
		}
		RenderedScreen nextView = buildView(menu.viewer(), provider.get(), targetRequest);
		String confirmKey = activeConfirmKey(menu.viewer().getUUID(), targetRequest.screenId());
		menu.renderView(nextView, menu.viewer().level().getServer(), confirmKey);
	}

	private RenderedScreen buildView(
		ServerPlayer viewer,
		GuiScreenProvider provider,
		GuiOpenRequest request
	) {
		GuiBuildContext context = new GuiBuildContext(
			viewer,
			request.islandId(),
			request.page(),
			request.state(),
			request.backScreenId(),
			request.backIslandId()
		);
		GuiScreenModel model = Objects.requireNonNull(
			provider.build(context),
			() -> "GuiScreenProvider returned null model for " + request.screenId()
		);
		return paginateModel(request, model);
	}

	private void openMainPanelFromRequest(ServerPlayer player, GuiOpenRequest request) {
		PanelPreferences prefs = preferences.cached(player.getUUID())
			.orElseGet(() -> PanelPreferences.defaults(player.getUUID()));
		String tabRaw = request.state().get("tab");
		PanelTab tab = tabRaw != null ? PanelTab.fromDbKey(tabRaw) : prefs.preferredTab();
		if (request.islandId() == null) {
			tab = PanelTab.BLUEPRINTS_INFO;
		}
		FlagModeFilter flagMode = FlagModeFilter.fromDbKey(request.state().getOrDefault("flagMode", prefs.flagMode().name()));
		int page = parseIntOrZero(request.state().get("page"));
		panelController.openPanel(player, request.islandId(), tab, flagMode, page);
	}

	private static GuiOpenRequest attachMainPanelBackIfNeeded(ServerPlayer player, GuiOpenRequest request) {
		if (request.backRequest() != null) {
			return request;
		}
		if (player.containerMenu instanceof OgskyPanelMenu panelMenu) {
			PanelSnapshot snap = panelMenu.snapshot();
			GuiOpenRequest back = new GuiOpenRequest(
				GuiService.MAIN_PANEL_SCREEN_ID,
				snap.islandId(),
				0,
				Map.of(
					"tab", snap.activeTab().id(),
					"flagMode", snap.flagMode().name(),
					"page", Integer.toString(snap.page())
				),
				null
			);
			return request.withBackRequest(back);
		}
		return request;
	}

	private void refreshMenusForIslandOnServerThread(UUID islandId) {
		for (OgskyCustomGuiMenu menu : openMenus.values()) {
			if (!islandId.equals(menu.request().islandId())) {
				continue;
			}
			ServerPlayer viewer = menu.viewer();
			if (viewer.containerMenu != menu) {
				openMenus.remove(viewer.getUUID(), menu);
				continue;
			}
			if (!isViewerAllowed(islandId, viewer.getUUID())) {
				PanelFeedback.sendFeedback(viewer, false, "Your access to that island was revoked.");
				viewer.closeContainer();
				continue;
			}
			refreshMenu(menu, menu.request());
		}
	}

	private void closeMenusForDeletedIsland(UUID islandId) {
		if (openMenus.isEmpty()) {
			return;
		}
		for (OgskyCustomGuiMenu menu : openMenus.values()) {
			if (islandId.equals(menu.request().islandId())) {
				MinecraftServer server = menu.viewer().level().getServer();
				server.execute(() -> {
					for (OgskyCustomGuiMenu open : openMenus.values()) {
						if (islandId.equals(open.request().islandId()) && open.viewer().containerMenu == open) {
							PanelFeedback.sendFeedback(open.viewer(), false, "The island you were viewing was deleted.");
							open.viewer().closeContainer();
						}
					}
				});
				return;
			}
		}
	}

	private @Nullable String activeConfirmKey(UUID playerUuid, Identifier screenId) {
		PendingConfirm confirm = pendingConfirmations.get(playerUuid);
		if (confirm == null) {
			return null;
		}
		if (confirm.isExpired() || !screenId.equals(confirm.screenId())) {
			pendingConfirmations.remove(playerUuid, confirm);
			return null;
		}
		return confirm.targetKey();
	}

	private static GuiClickAction mapClickAction(int buttonNum, ContainerInput input) {
		if (input == ContainerInput.QUICK_MOVE) {
			return buttonNum == 1 ? GuiClickAction.SHIFT_RIGHT : GuiClickAction.SHIFT_LEFT;
		}
		return buttonNum == 1 ? GuiClickAction.RIGHT : GuiClickAction.LEFT;
	}

	private static int parseIntOrZero(@Nullable String raw) {
		if (raw == null || raw.isBlank()) {
			return 0;
		}
		try {
			return Math.max(0, Integer.parseInt(raw.trim()));
		} catch (NumberFormatException ignored) {
			return 0;
		}
	}

	private final class GuiClickContextImpl implements GuiClickContext {
		private final OgskyCustomGuiMenu menu;
		private final ServerPlayer actor;
		private final GuiClickAction clickAction;

		private GuiClickContextImpl(
			OgskyCustomGuiMenu menu,
			ServerPlayer actor,
			GuiClickAction clickAction
		) {
			this.menu = menu;
			this.actor = actor;
			this.clickAction = clickAction;
		}

		@Override
		public ServerPlayer actor() {
			return actor;
		}

		@Override
		public @Nullable UUID islandId() {
			return menu.request().islandId();
		}

		@Override
		public GuiClickAction clickAction() {
			return clickAction;
		}

		@Override
		public int currentPage() {
			return menu.view().page();
		}

		@Override
		public Map<String, String> state() {
			return menu.request().state();
		}

		@Override
		public void refresh() {
			if (openMenus.get(actor.getUUID()) == menu && actor.containerMenu == menu) {
				refreshMenu(menu, menu.request());
			}
		}

		@Override
		public void refreshWithState(String key, String value) {
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(value, "value");
			pendingConfirmations.remove(actor.getUUID());
			if (openMenus.get(actor.getUUID()) == menu && actor.containerMenu == menu) {
				refreshMenu(menu, menu.request().withState(key, value));
			}
		}

		@Override
		public void openScreen(Identifier screenId, @Nullable UUID targetIslandId) {
			Objects.requireNonNull(screenId, "screenId");
			openScreen(GuiOpenRequest.of(screenId, targetIslandId));
		}

		@Override
		public void openScreen(GuiOpenRequest nextRequest) {
			Objects.requireNonNull(nextRequest, "nextRequest");
			GuiOpenRequest resolved = nextRequest;
			if (resolved.backRequest() == null && !GuiService.MAIN_PANEL_SCREEN_ID.equals(resolved.screenId())) {
				resolved = resolved.withBackRequest(menu.request());
			}
			PanelGuiHost.this.openScreen(actor, resolved);
		}

		@Override
		public void goBack() {
			GuiOpenRequest back = menu.request().backRequest();
			if (back != null) {
				PanelGuiHost.this.openScreen(actor, back);
			} else {
				actor.closeContainer();
			}
		}

		@Override
		public void close() {
			actor.closeContainer();
		}

		@Override
		public void sendFeedback(boolean success, String message) {
			PanelFeedback.applyMutationFeedback(actor, success, message);
		}
	}
}
