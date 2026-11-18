package me.ogsammaenr.ogsky.panel;

import me.ogsammaenr.ogsky.core.api.OgskyCoreApi;
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
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import me.ogsammaenr.ogui.api.Ogui;
import me.ogsammaenr.ogui.api.OguiBuildContext;
import me.ogsammaenr.ogui.api.OguiClickAction;
import me.ogsammaenr.ogui.api.OguiClickContext;
import me.ogsammaenr.ogui.api.OguiConfirmSpec;
import me.ogsammaenr.ogui.api.OguiIcon;
import me.ogsammaenr.ogui.api.OguiOpenRequest;
import me.ogsammaenr.ogui.api.OguiScreenModel;
import me.ogsammaenr.ogui.api.OguiService;
import me.ogsammaenr.ogui.api.OguiText;
import me.ogsammaenr.ogui.api.OguiTheme;
import me.ogsammaenr.ogui.api.OguiWidget;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link GuiHost} adapter bridging all declarative {@code ogsky-core} {@link GuiScreenModel} screens
 * onto {@link Ogui#service()} ({@code ogui-client} rich screens + anti-dupe vanilla {@code ChestMenu} fallback).
 */
public final class PanelGuiHost implements GuiHost {
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

	private final OgskyCoreApi core;
	private final PanelController panelController;
	private final Set<Identifier> bridgedScreens = ConcurrentHashMap.newKeySet();

	public PanelGuiHost(OgskyCoreApi core, PanelController panelController) {
		this.core = Objects.requireNonNull(core, "core");
		this.panelController = Objects.requireNonNull(panelController, "panelController");
	}

	@Override
	public boolean openScreen(ServerPlayer player, GuiOpenRequest request) {
		Objects.requireNonNull(player, "player");
		Objects.requireNonNull(request, "request");

		if (GuiService.MAIN_PANEL_SCREEN_ID.equals(request.screenId())) {
			panelController.openMainPanelFromRequest(player, request);
			return true;
		}

		boolean isBuiltInBlueprintScreen = isBuiltInBlueprintScreen(request.screenId());
		if (!isBuiltInBlueprintScreen && core.guis().findScreen(request.screenId()).isEmpty()) {
			return false;
		}

		if (!isBuiltInBlueprintScreen) {
			ensureScreenBridged(request.screenId());
		}
		OguiService ogui = Ogui.service();
		OguiOpenRequest oguiRequest = toOguiRequest(request);
		if (oguiRequest.backRequest() == null) {
			Optional<OguiOpenRequest> current = ogui.activeRequest(player);
			if (current.isPresent() && GuiService.isBuiltInScreen(current.get().screenId())) {
				oguiRequest = oguiRequest.withBackRequest(current.get());
			}
		}
		return ogui.openScreen(player, oguiRequest);
	}

	private static boolean isBuiltInBlueprintScreen(Identifier screenId) {
		return GuiService.CREATE_GALLERY_SCREEN_ID.equals(screenId)
			|| GuiService.BLUEPRINT_PREVIEW_SCREEN_ID.equals(screenId);
	}

	@Override
	public void refreshIslandScreens(UUID islandId) {
		Objects.requireNonNull(islandId, "islandId");
		String key = islandId.toString();
		Ogui.service().refreshMatchingScreens(req -> key.equals(req.contextKey()));
	}

	@Override
	public void refreshPlayerScreen(ServerPlayer player, Identifier screenId) {
		Objects.requireNonNull(player, "player");
		Objects.requireNonNull(screenId, "screenId");
		Ogui.service().refreshPlayerScreen(player, screenId);
	}

	public void onPlayerDisconnect(UUID playerUuid) {
		// Session cleanup is managed by OguiApiMod.
	}

	public void clearAll() {
		// Runtime session state is cleared by OguiApiMod on server stop.
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

	private void ensureScreenBridged(Identifier screenId) {
		if (GuiService.MAIN_PANEL_SCREEN_ID.equals(screenId)) {
			return;
		}
		if (bridgedScreens.add(screenId)) {
			Ogui.service().registerScreen(screenId, oguiCtx -> buildOguiScreen(screenId, oguiCtx));
		}
	}

	private OguiScreenModel buildOguiScreen(Identifier screenId, OguiBuildContext oguiCtx) {
		Optional<GuiScreenProvider> provider = core.guis().findScreen(screenId);
		if (provider.isEmpty()) {
			return OguiScreenModel.fixedGrid("Screen Unavailable", 3)
				.slot(13, OguiWidget.button("unavailable", OguiIcon.item(Items.BARRIER), OguiText.nonItalic("Screen Not Registered", ChatFormatting.RED))
					.onClick(OguiClickContext::goBack)
					.build())
				.build();
		}

		UUID islandId = parseUuid(oguiCtx.contextKey());
		UUID backIslandId = parseUuid(oguiCtx.backContextKey());
		if (islandId != null && !isViewerAllowed(islandId, oguiCtx.viewer().getUUID())) {
			return OguiScreenModel.fixedGrid("Access Denied", 3)
				.slot(13, OguiWidget.button("denied", OguiIcon.item(Items.BARRIER), OguiText.nonItalic("You are banned from this island.", ChatFormatting.RED))
					.onClick(OguiClickContext::close)
					.build())
				.build();
		}

		GuiBuildContext coreCtx = new GuiBuildContext(
			oguiCtx.viewer(),
			islandId,
			oguiCtx.requestedPage(),
			oguiCtx.state(),
			oguiCtx.backScreenId(),
			backIslandId
		);
		GuiScreenModel coreModel = Objects.requireNonNull(
			provider.get().build(coreCtx),
			() -> "GuiScreenProvider returned null model for " + screenId
		);
		return translateModel(coreCtx, coreModel);
	}

	private boolean isViewerAllowed(UUID islandId, UUID viewerUuid) {
		Optional<IslandView> island = core.islands().findById(islandId);
		return island.map(v -> !v.isBanned(viewerUuid)).orElse(true);
	}

	private OguiScreenModel translateModel(GuiBuildContext coreCtx, GuiScreenModel coreModel) {
		if (coreModel.layoutMode() == GuiScreenModel.LayoutMode.CUSTOM_FIXED) {
			OguiScreenModel.FixedGridBuilder fixed = OguiScreenModel.fixedGrid(
				OguiText.nonItalic(coreModel.title()),
				coreModel.rows()
			).theme(OguiTheme.builder()
				.accentColor(0xFF38BDF8)
				.subtitle("Ogsky Skyblock")
				.build());

			for (Map.Entry<Integer, GuiButton> entry : coreModel.pinnedButtons().entrySet()) {
				fixed.slot(
					entry.getKey(),
					toOguiButton("fixed_" + entry.getKey(), entry.getValue())
				);
			}
			return fixed.build();
		}

		OguiScreenModel.PagedGridBuilder paged = OguiScreenModel.pagedGrid(OguiText.nonItalic(coreModel.title()))
			.theme(OguiTheme.builder()
				.accentColor(0xFF38BDF8)
				.subtitle("Ogsky Skyblock")
				.build());

		if (GuiService.MAIN_PANEL_SCREEN_ID.equals(coreCtx.backScreenId())) {
			paged.backLabel(OguiText.nonItalic("Back to Control Panel", ChatFormatting.YELLOW));
		}

		for (Map.Entry<Integer, GuiButton> entry : coreModel.pinnedButtons().entrySet()) {
			int slot = entry.getKey();
			OguiWidget.ButtonWidget widget = toOguiButton("pin_" + slot, entry.getValue());
			if (slot >= 45) {
				paged.addFooterWidget(widget, slot);
			} else {
				paged.addHeaderWidget(widget, slot);
			}
		}

		if (coreModel.contextButton() != null) {
			paged.addFooterWidget(toOguiButton("context", coreModel.contextButton()), 47);
		}

		List<GuiButton> entries = coreModel.pagedEntries();
		for (int i = 0; i < entries.size(); i++) {
			paged.addEntry(toOguiButton("entry_" + i, entries.get(i)));
		}

		if (coreModel.emptyStateButton() != null) {
			paged.emptyState(toOguiButton("empty", coreModel.emptyStateButton()));
		}

		return paged.build();
	}

	private OguiWidget.ButtonWidget toOguiButton(String widgetId, GuiButton button) {
		OguiIcon baseIcon = (button.playerHeadUuid() != null && button.iconItemId().endsWith("player_head"))
			? OguiIcon.playerHead(button.playerHeadUuid())
			: OguiIcon.item(button.iconItemId());
		OguiIcon icon = baseIcon.withCount(button.count()).withGlowing(button.glowing());

		OguiWidget.ButtonWidget.Builder builder = OguiWidget.button(
			widgetId,
			icon,
			OguiText.nonItalic(button.name())
		);
		for (var line : button.lore()) {
			builder.lore(OguiText.nonItalic(line));
		}
		GuiConfirmSpec confirm = button.confirmSpec();
		if (confirm != null) {
			builder.confirm(OguiConfirmSpec.of(confirm.targetKey(), confirm.promptLine()));
		}
		if (button.onClick() != null) {
			builder.onClick(oguiClick -> button.onClick().accept(new BridgedClickContext(oguiClick)));
		}
		return builder.build();
	}

	private OguiOpenRequest toOguiRequest(GuiOpenRequest request) {
		OguiOpenRequest back = request.backRequest() == null ? null : toOguiRequest(request.backRequest());
		return new OguiOpenRequest(
			request.screenId(),
			uuidToString(request.islandId()),
			request.page(),
			request.state(),
			back
		);
	}

	private static @Nullable String uuidToString(@Nullable UUID uuid) {
		return uuid == null ? null : uuid.toString();
	}

	private static @Nullable UUID parseUuid(@Nullable String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(raw.trim());
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private static GuiClickAction mapClickAction(OguiClickAction action) {
		return switch (action) {
			case RIGHT -> GuiClickAction.RIGHT;
			case SHIFT_LEFT -> GuiClickAction.SHIFT_LEFT;
			case SHIFT_RIGHT -> GuiClickAction.SHIFT_RIGHT;
			case LEFT, CLIENT_INPUT -> GuiClickAction.LEFT;
		};
	}

	private final class BridgedClickContext implements GuiClickContext {
		private final OguiClickContext delegate;

		private BridgedClickContext(OguiClickContext delegate) {
			this.delegate = delegate;
		}

		@Override
		public ServerPlayer actor() {
			return delegate.actor();
		}

		@Override
		public @Nullable UUID islandId() {
			return parseUuid(delegate.contextKey());
		}

		@Override
		public GuiClickAction clickAction() {
			return mapClickAction(delegate.clickAction());
		}

		@Override
		public int currentPage() {
			return delegate.currentPage();
		}

		@Override
		public Map<String, String> state() {
			return delegate.state();
		}

		@Override
		public void refresh() {
			delegate.refresh();
		}

		@Override
		public void refreshWithState(String key, String value) {
			delegate.refreshWithState(key, value);
		}

		@Override
		public void openScreen(Identifier screenId, @Nullable UUID targetIslandId) {
			Objects.requireNonNull(screenId, "screenId");
			openScreen(GuiOpenRequest.of(screenId, targetIslandId));
		}

		@Override
		public void openScreen(GuiOpenRequest nextRequest) {
			Objects.requireNonNull(nextRequest, "nextRequest");
			if (GuiService.MAIN_PANEL_SCREEN_ID.equals(nextRequest.screenId())) {
				panelController.openMainPanelFromRequest(delegate.actor(), nextRequest);
				return;
			}
			boolean isBuiltInBlueprintScreen = isBuiltInBlueprintScreen(nextRequest.screenId());
			if (!isBuiltInBlueprintScreen && core.guis().findScreen(nextRequest.screenId()).isEmpty()) {
				return;
			}
			if (!isBuiltInBlueprintScreen) {
				ensureScreenBridged(nextRequest.screenId());
			}
			delegate.openScreen(toOguiRequest(nextRequest));
		}

		@Override
		public void goBack() {
			delegate.goBack();
		}

		@Override
		public void close() {
			delegate.close();
		}

		@Override
		public void sendFeedback(boolean success, String message) {
			PanelFeedback.applyMutationFeedback(delegate.actor(), success, message);
		}
	}
}
