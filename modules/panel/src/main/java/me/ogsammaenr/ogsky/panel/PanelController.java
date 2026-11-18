package me.ogsammaenr.ogsky.panel;

import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.OgskyCoreApi;
import me.ogsammaenr.ogsky.core.api.action.IslandActionContext;
import me.ogsammaenr.ogsky.core.api.action.IslandActionDescriptor;
import me.ogsammaenr.ogsky.core.api.blueprint.BlueprintBundleView;
import me.ogsammaenr.ogsky.core.api.blueprint.BlueprintDimension;
import me.ogsammaenr.ogsky.core.api.event.BlueprintsReloadedEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandBanEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandDeletedEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandDimensionInitEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandLevelCalculatedEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandLockChangedEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandProtectionFlagChangedEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandRangeChangeEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandResetEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandSettingFlagChangedEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandUnbanEvent;
import me.ogsammaenr.ogsky.core.api.event.OgskyEventBus;
import me.ogsammaenr.ogsky.core.api.event.TeamJoinEvent;
import me.ogsammaenr.ogsky.core.api.event.TeamKickEvent;
import me.ogsammaenr.ogsky.core.api.event.TeamLeaveEvent;
import me.ogsammaenr.ogsky.core.api.event.TeamOwnerChangeEvent;
import me.ogsammaenr.ogsky.core.api.event.TeamRankChangeEvent;
import me.ogsammaenr.ogsky.core.api.flag.FlagMode;
import me.ogsammaenr.ogsky.core.api.gui.GuiOpenRequest;
import me.ogsammaenr.ogsky.core.api.gui.GuiService;
import me.ogsammaenr.ogsky.core.api.island.IslandOperationResult;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import me.ogsammaenr.ogui.api.Ogui;
import me.ogsammaenr.ogui.api.OguiAssetRef;
import me.ogsammaenr.ogui.api.OguiBuildContext;
import me.ogsammaenr.ogui.api.OguiClickAction;
import me.ogsammaenr.ogui.api.OguiClickContext;
import me.ogsammaenr.ogui.api.OguiConfirmSpec;
import me.ogsammaenr.ogui.api.OguiIcon;
import me.ogsammaenr.ogui.api.OguiOpenRequest;
import me.ogsammaenr.ogui.api.OguiScreenModel;
import me.ogsammaenr.ogui.api.OguiShortcut;
import me.ogsammaenr.ogui.api.OguiSummaryCard;
import me.ogsammaenr.ogui.api.OguiTab;
import me.ogsammaenr.ogui.api.OguiText;
import me.ogsammaenr.ogui.api.OguiTheme;
import me.ogsammaenr.ogui.api.OguiWidget;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates the main Skyblock Control Panel through {@link Ogui#service()}, handling
 * hybrid client/vanilla rendering, click mutations, conditional confirmations, and live refreshes.
 */
public final class PanelController {
	private static final Duration CONFIRM_TTL = Duration.ofSeconds(10);
	private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
		.withZone(ZoneId.systemDefault());
	private static final Identifier PANEL_SHORTCUT_ID = OgskyCore.id("panel");

	public enum ConfirmKind {
		RESET_ISLAND,
		KICK_MEMBER,
		BAN_PLAYER
	}

	public record PendingConfirmation(
		ConfirmKind kind,
		@Nullable UUID islandId,
		String targetKey,
		Instant expiresAt
	) {
		public boolean isExpired() {
			return Instant.now().isAfter(expiresAt);
		}
	}

	private final OgskyCoreApi core;
	private final PanelViewModelService viewModels;
	private final PanelPreferenceRepository preferences;
	private final PanelBlueprintAssetManager blueprintAssets;
	private final Map<UUID, PendingConfirmation> pendingConfirmations = new ConcurrentHashMap<>();

	public PanelController(
		OgskyCoreApi core,
		PanelViewModelService viewModels,
		PanelPreferenceRepository preferences
	) {
		this(core, viewModels, preferences, new PanelBlueprintAssetManager(core));
	}

	public PanelController(
		OgskyCoreApi core,
		PanelViewModelService viewModels,
		PanelPreferenceRepository preferences,
		PanelBlueprintAssetManager blueprintAssets
	) {
		this.core = Objects.requireNonNull(core, "core");
		this.viewModels = Objects.requireNonNull(viewModels, "viewModels");
		this.preferences = Objects.requireNonNull(preferences, "preferences");
		this.blueprintAssets = Objects.requireNonNull(blueprintAssets, "blueprintAssets");
	}

	public void registerOgui() {
		Ogui.service().registerScreen(GuiService.MAIN_PANEL_SCREEN_ID, this::buildMainPanelScreen);
		Ogui.service().registerScreen(GuiService.CREATE_GALLERY_SCREEN_ID, this::buildBlueprintGalleryScreen);
		Ogui.service().registerScreen(GuiService.BLUEPRINT_PREVIEW_SCREEN_ID, this::buildBlueprintPreviewScreen);
		Ogui.service().registerShortcut(new OguiShortcut(
			PANEL_SHORTCUT_ID,
			Component.literal("Skyblock Control Panel"),
			Component.literal("Open your island control panel (/is panel)"),
			player -> player.level().getServer().getCommands().performPrefixedCommand(
				player.createCommandSourceStack(),
				"is panel"
			)
		));
	}

	public void registerEvents() {
		OgskyEventBus bus = core.events();
		bus.subscribe(BlueprintsReloadedEvent.class, e -> onBlueprintsReloaded());
		bus.subscribe(IslandProtectionFlagChangedEvent.class, e -> scheduleIslandRefresh(e.islandId()));
		bus.subscribe(IslandSettingFlagChangedEvent.class, e -> scheduleIslandRefresh(e.islandId()));
		bus.subscribe(IslandLockChangedEvent.class, e -> scheduleIslandRefresh(e.islandId()));
		bus.subscribe(IslandRangeChangeEvent.class, e -> scheduleIslandRefresh(e.islandId()));
		bus.subscribe(IslandDimensionInitEvent.class, e -> scheduleIslandRefresh(e.islandId()));
		bus.subscribe(IslandLevelCalculatedEvent.class, e -> scheduleIslandRefresh(e.islandId()));
		bus.subscribe(IslandUnbanEvent.class, e -> scheduleIslandRefresh(e.islandId()));
		bus.subscribe(TeamJoinEvent.class, e -> scheduleIslandRefresh(e.islandId()));
		bus.subscribe(IslandBanEvent.class, e -> {
			if (!e.isCancelled()) {
				String islandKey = e.islandId().toString();
				Ogui.service().closeMatchingScreens(
					(viewerId, req) -> viewerId.equals(e.targetId()) && islandKey.equals(req.contextKey()),
					false,
					Component.literal("[Ogsky] Your access to that island was revoked.")
				);
				scheduleIslandRefresh(e.islandId());
			}
		});
		bus.subscribe(TeamKickEvent.class, e -> {
			if (!e.isCancelled()) {
				scheduleIslandRefresh(e.islandId());
			}
		});
		bus.subscribe(TeamLeaveEvent.class, e -> {
			if (!e.isCancelled()) {
				scheduleIslandRefresh(e.islandId());
			}
		});
		bus.subscribe(TeamRankChangeEvent.class, e -> {
			if (!e.isCancelled()) {
				scheduleIslandRefresh(e.islandId());
			}
		});
		bus.subscribe(TeamOwnerChangeEvent.class, e -> {
			if (!e.isCancelled()) {
				scheduleIslandRefresh(e.islandId());
			}
		});
		bus.subscribe(IslandResetEvent.class, e -> {
			if (!e.isCancelled()) {
				scheduleIslandRefresh(e.islandId());
			}
		});
		bus.subscribe(IslandDeletedEvent.class, e -> {
			String islandKey = e.islandId().toString();
			Ogui.service().closeMatchingScreens(
				(viewerId, req) -> islandKey.equals(req.contextKey()),
				false,
				Component.literal("[Ogsky] The island you were viewing was deleted.")
			);
		});
	}

	public void openPanel(
		ServerPlayer player,
		@Nullable UUID islandId,
		PanelTab tab,
		FlagModeFilter flagMode,
		int page
	) {
		Objects.requireNonNull(player, "player");
		Objects.requireNonNull(tab, "tab");
		Objects.requireNonNull(flagMode, "flagMode");

		pendingConfirmations.remove(player.getUUID());
		Ogui.service().openScreen(player, new OguiOpenRequest(
			GuiService.MAIN_PANEL_SCREEN_ID,
			islandId == null ? null : islandId.toString(),
			page,
			Map.of(
				"tab", tab.id(),
				"flagMode", flagMode.name()
			),
			null
		));
	}

	public void openMainPanelFromRequest(ServerPlayer player, GuiOpenRequest request) {
		PanelPreferences prefs = preferences.cached(player.getUUID())
			.orElseGet(() -> PanelPreferences.defaults(player.getUUID()));
		String tabRaw = request.state().get("tab");
		PanelTab tab = tabRaw != null ? PanelTab.fromDbKey(tabRaw) : prefs.preferredTab();
		if (request.islandId() == null) {
			tab = PanelTab.BLUEPRINTS_INFO;
		}
		FlagModeFilter flagMode = FlagModeFilter.fromDbKey(request.state().getOrDefault("flagMode", prefs.flagMode().name()));
		int page = parseIntOrZero(request.state().getOrDefault("page", Integer.toString(request.page())));
		openPanel(player, request.islandId(), tab, flagMode, page);
	}

	public void onPlayerDisconnect(UUID playerUuid) {
		pendingConfirmations.remove(playerUuid);
		preferences.evict(playerUuid);
	}

	public void clearAll() {
		pendingConfirmations.clear();
		preferences.clearCache();
	}

	public boolean isViewerAllowed(UUID islandId, UUID viewerUuid) {
		Optional<IslandView> island = core.islands().findById(islandId);
		return island.isPresent() && !island.get().isBanned(viewerUuid);
	}

	public String roleDisplayName(byte role) {
		return core.islands().roleDisplayName(role);
	}

	private OguiScreenModel buildMainPanelScreen(OguiBuildContext ctx) {
		ServerPlayer viewer = ctx.viewer();
		UUID viewerId = viewer.getUUID();
		UUID islandId = parseUuid(ctx.contextKey());

		if (islandId != null && !isViewerAllowed(islandId, viewerId)) {
			return OguiScreenModel.fixedGrid("Access Denied", 3)
				.slot(13, OguiWidget.button("denied", OguiIcon.item(Items.BARRIER), OguiText.nonItalic("You are banned from this island.", ChatFormatting.RED))
					.onClick(OguiClickContext::close)
					.build())
				.build();
		}

		PanelPreferences cachedPrefs = preferences.cached(viewerId).orElseGet(() -> PanelPreferences.defaults(viewerId));
		String rawTab = ctx.state().get("tab");
		PanelTab activeTab = rawTab != null ? PanelTab.fromDbKey(rawTab) : cachedPrefs.preferredTab();
		if (islandId == null) {
			activeTab = PanelTab.BLUEPRINTS_INFO;
		}
		FlagModeFilter flagMode = FlagModeFilter.fromDbKey(ctx.state().getOrDefault("flagMode", cachedPrefs.flagMode().name()));
		String searchQuery = ctx.stateOrDefault("search", "");

		PanelViewModelService.PanelViewData data = viewModels.buildFullData(islandId, viewer, activeTab, flagMode, searchQuery);
		PendingConfirmation pending = activeConfirmation(viewerId);

		OguiSummaryCard summaryCard = buildSummaryCard(data);
		String subtitle = data.island() == null
			? "Starter Blueprint Selection"
			: data.island().ownerName() + " • " + (data.island().locked() ? "LOCKED" : "UNLOCKED");

		OguiScreenModel.TabbedPanelBuilder builder = OguiScreenModel.tabbedPanel(
				OguiText.nonItalic("Skyblock Control Panel")
			)
			.theme(OguiTheme.builder()
				.accentColor(0xFF38BDF8)
				.subtitle(subtitle)
				.addSummaryCard(summaryCard)
				.styleTag("ogsky-panel")
				.build())
			.activeTabId(data.activeTab().id());

		for (PanelTab tab : PanelTab.values()) {
			builder.addTab(buildTab(tab, data, viewer));
		}

		if (data.island() != null) {
			boolean locked = data.island().locked();
			boolean canEditLock = data.viewerAdminBypass() || data.viewerRole() >= IslandRole.ADMIN;
			builder.addHeaderWidget(
				OguiWidget.toggle(
						"quick_lock",
						OguiIcon.item(Items.OAK_DOOR),
						OguiText.nonItalic(locked ? "Island Access: LOCKED" : "Island Access: UNLOCKED", locked ? ChatFormatting.RED : ChatFormatting.GREEN)
					)
					.activeIcon(OguiIcon.item(Items.IRON_BARS))
					.value(locked)
					.labels(
						OguiText.nonItalic("LOCKED", ChatFormatting.RED),
						OguiText.nonItalic("UNLOCKED", ChatFormatting.GREEN)
					)
					.lore(
						OguiText.nonItalic(
							locked ? "Visitors cannot enter your island." : "Visitors are allowed to enter your island.",
							ChatFormatting.GRAY
						),
						canEditLock
							? OguiText.nonItalic(locked ? "Click to UNLOCK island" : "Click to LOCK island", ChatFormatting.YELLOW)
							: OguiText.nonItalic("View only (requires Admin rank)", ChatFormatting.RED)
					)
					.onToggle((click, ignoredNext) -> handleQuickLockToggle(click, data))
					.build(),
				7
			);
		}

		if (data.activeTab() == PanelTab.PROTECTION_FLAGS || data.activeTab() == PanelTab.SETTING_FLAGS) {
			boolean advanced = data.flagMode() == FlagModeFilter.ADVANCED;
			builder.addFooterWidget(
				OguiWidget.cycle(
						"flag_mode",
						OguiIcon.item(Items.LEVER),
						OguiText.nonItalic("Mode: " + data.flagMode().displayName(), ChatFormatting.AQUA)
					)
					.options(List.of(
						new OguiWidget.CycleOption("basic", OguiText.nonItalic("Basic", ChatFormatting.GREEN), OguiIcon.item(Items.LEVER)),
						new OguiWidget.CycleOption("advanced", OguiText.nonItalic("Advanced", ChatFormatting.LIGHT_PURPLE), OguiIcon.item(Items.LEVER).withGlowing(true))
					))
					.selectedId(advanced ? "advanced" : "basic")
					.lore(
						OguiText.nonItalic(advanced ? "Showing Basic + Advanced flags" : "Showing Basic flags only", ChatFormatting.GRAY),
						OguiText.nonItalic("Click to switch between Basic and Advanced", ChatFormatting.YELLOW)
					)
					.onCycle((click, opt) -> {
						FlagModeFilter nextMode = "advanced".equals(opt.id()) ? FlagModeFilter.ADVANCED : FlagModeFilter.BASIC;
						preferences.saveFlagModeAsync(click.actor().getUUID(), nextMode);
						click.refreshWithState("flagMode", nextMode.name());
					})
					.build(),
				47
			);
		} else if (data.activeTab() == PanelTab.MEMBERS) {
			builder.addFooterWidget(
				OguiWidget.button("team_commands", "minecraft:writable_book", "Add & Transfer Commands", ChatFormatting.GOLD)
					.lore(
						OguiText.nonItalic("/is invite <player> - Invite primary member", ChatFormatting.GRAY),
						OguiText.nonItalic("/is coop <player> - Add temporary coop helper", ChatFormatting.GRAY),
						OguiText.nonItalic("/is trust <player> - Add persistent trusted helper", ChatFormatting.GRAY),
						OguiText.nonItalic("/is ban <player> | /is transfer <player>", ChatFormatting.GRAY),
						OguiText.nonItalic("Click to print commands in chat", ChatFormatting.YELLOW)
					)
					.onClick(click -> click.actor().sendSystemMessage(
						Component.literal("[Ogsky] Team commands: /is invite <player>, /is coop <player>, /is trust <player>, /is ban <player>, /is transfer <player>")
							.withStyle(ChatFormatting.YELLOW)
					))
					.build(),
				47
			);
		}

		builder.addFooterWidget(buildSearchInput(searchQuery, "Filter entries in this tab"));

		populateEntries(builder, data, pending, searchQuery);
		return builder.build();
	}

	private static OguiWidget.TextInputWidget buildSearchInput(String searchQuery, String hintText) {
		return OguiWidget.textInput(
				"search",
				OguiIcon.item(Items.COMPASS).withGlowing(!searchQuery.isBlank()),
				OguiText.nonItalic(searchQuery.isBlank() ? "Search" : "Search: " + searchQuery, ChatFormatting.AQUA)
			)
			.stateKey("search")
			.placeholder("Filter entries...")
			.value(searchQuery)
			.maxLength(32)
			.mode(OguiWidget.InputMode.SEARCH)
			.liveDebounce(true)
			.lore(OguiText.nonItalic(hintText, ChatFormatting.GRAY))
			.onSubmit((click, value) -> click.refreshWithState("search", value))
			.build();
	}

	private OguiScreenModel buildBlueprintGalleryScreen(OguiBuildContext ctx) {
		blueprintAssets.syncIfNeeded();
		ServerPlayer viewer = ctx.viewer();
		UUID contextIslandId = parseUuid(ctx.contextKey());
		String requestedMode = ctx.stateOrDefault("mode", contextIslandId != null ? "reset" : "create");
		boolean resetModeRequested = contextIslandId != null || "reset".equalsIgnoreCase(requestedMode);
		IslandView targetIsland = resolveTargetIslandForBlueprintFlow(viewer, contextIslandId, resetModeRequested);
		boolean resetMode = targetIsland != null;

		String searchQuery = ctx.stateOrDefault("search", "").trim();
		String normalizedSearch = searchQuery.toLowerCase(Locale.ROOT);
		String defaultId = core.islands().defaultBlueprintId();

		OguiTheme theme = OguiTheme.builder()
			.accentColor(resetMode ? 0xFFF59E0B : 0xFF38BDF8)
			.subtitle(resetMode
				? "Select a blueprint card to preview in 3D before resetting your island"
				: "Select an island blueprint to preview in 3D before creating")
			.styleTag("ogsky-blueprint-gallery")
			.build();

		OguiScreenModel.PagedGridBuilder builder = OguiScreenModel.pagedGrid(
				OguiText.nonItalic(resetMode ? "Reset Island • Choose Blueprint" : "Create Island • Choose Blueprint", ChatFormatting.AQUA)
			)
			.theme(theme)
			.addHeaderWidget(buildSearchInput(searchQuery, "Filter blueprints by name or ID"), 46);

		List<BlueprintBundleView> allBlueprints = core.islands().availableBlueprints();
		int idx = 0;
		for (BlueprintBundleView bundle : allBlueprints) {
			if (!matchesBlueprintSearch(bundle, normalizedSearch)) {
				continue;
			}
			boolean permitted = core.permissions().hasBlueprintPermission(viewer, bundle);
			boolean activeOnIsland = targetIsland != null && targetIsland.bundleId().equals(bundle.id());
			boolean isDefault = defaultId.equals(bundle.id());
			builder.addEntry(buildBlueprintCardWidget(
				"bp_" + idx++,
				bundle.id(),
				bundle.displayName(),
				bundle.description(),
				bundle.iconItemId(),
				bundle.hasOverworld(),
				bundle.hasNether(),
				bundle.hasEnd(),
				isDefault,
				activeOnIsland,
				permitted,
				targetIsland == null ? null : targetIsland.islandId(),
				resetMode
			));
		}

		if (!searchQuery.isBlank()) {
			builder.emptyState(
				OguiWidget.button("empty_search", "minecraft:compass", "No Matching Blueprints", ChatFormatting.YELLOW)
					.lore(
						OguiText.nonItalic("Search: '" + searchQuery + "'", ChatFormatting.GRAY),
						OguiText.nonItalic("Click to clear search filter", ChatFormatting.GREEN)
					)
					.onClick(click -> click.refreshWithState("search", ""))
					.build()
			);
		} else {
			builder.emptyState(buildNoBlueprintsEmptyWidget());
		}
		return builder.build();
	}

	private OguiScreenModel buildBlueprintPreviewScreen(OguiBuildContext ctx) {
		blueprintAssets.syncIfNeeded();
		ServerPlayer viewer = ctx.viewer();
		String bundleId = ctx.stateOrDefault("bundle", core.islands().defaultBlueprintId());
		BlueprintBundleView bundle = findBlueprintView(bundleId);
		if (bundle == null) {
			return OguiScreenModel.detailForm(OguiText.nonItalic("Blueprint Unavailable", ChatFormatting.RED))
				.theme(OguiTheme.builder()
					.accentColor(0xFFEF4444)
					.subtitle("This blueprint bundle is no longer loaded.")
					.build())
				.addControl(
					OguiWidget.button("missing_bundle", "minecraft:barrier", "Blueprint Not Found", ChatFormatting.RED)
						.enabled(false)
						.lore(
							OguiText.nonItalic("Requested bundle '" + bundleId + "' is unavailable.", ChatFormatting.GRAY),
							OguiText.nonItalic("Ask an admin to run /isadmin reload or pick another blueprint.", ChatFormatting.YELLOW)
						)
						.build()
				)
				.addAction(
					OguiWidget.button("back_to_list", "minecraft:oak_door", "Back", ChatFormatting.YELLOW)
						.onClick(OguiClickContext::goBack)
						.build()
				)
				.build();
		}

		UUID contextIslandId = parseUuid(ctx.contextKey());
		String requestedMode = ctx.stateOrDefault("mode", contextIslandId != null ? "reset" : "create");
		boolean resetModeRequested = contextIslandId != null || "reset".equalsIgnoreCase(requestedMode);
		IslandView targetIsland = resolveTargetIslandForBlueprintFlow(viewer, contextIslandId, resetModeRequested);
		boolean resetMode = targetIsland != null;

		List<BlueprintDimension> supportedDims = supportedDimensions(bundle);
		BlueprintDimension activeDim = resolveActiveDimension(
			ctx.stateOrDefault("dim", BlueprintDimension.OVERWORLD.key()),
			supportedDims
		);
		PanelBlueprintAssetManager.BundleAssets assets = blueprintAssets.bundleAssets(bundle.id());
		PanelBlueprintAssetManager.DimensionStructureAsset dimAsset = assets.structureInfo(activeDim);
		OguiAssetRef shellRef = dimAsset != null ? dimAsset.shellRef() : null;

		boolean permitted = core.permissions().hasBlueprintPermission(viewer, bundle);
		boolean isDefault = bundle.id().equals(core.islands().defaultBlueprintId());
		boolean activeOnIsland = targetIsland != null && targetIsland.bundleId().equals(bundle.id());

		String boundsLabel = dimAsset != null
			? dimAsset.sizeX() + "x" + dimAsset.sizeY() + "x" + dimAsset.sizeZ() + " (" + dimAsset.blockCount() + " blocks)"
			: "Empty / no block geometry";
		String statusLabel = !bundle.hasOverworld()
			? "Missing overworld.nbt"
			: (!permitted
				? "Locked (" + bundle.permissionNode() + ")"
				: (activeOnIsland ? "Currently Active" : (isDefault ? "Default Starter" : "Ready")));

		OguiSummaryCard summaryCard = OguiSummaryCard.of(
			OguiIcon.item(bundle.iconItemId()),
			OguiText.nonItalic(bundle.displayName(), ChatFormatting.AQUA),
			List.of(
				OguiText.nonItalic(activeDim.displayName() + ": " + boundsLabel, ChatFormatting.GRAY),
				OguiText.nonItalic("Status: " + statusLabel, permitted && bundle.hasOverworld() ? ChatFormatting.GREEN : ChatFormatting.RED)
			)
		);

		OguiTheme theme = OguiTheme.builder()
			.accentColor(resetMode ? 0xFFF59E0B : 0xFF22C55E)
			.subtitle(bundle.description().isBlank()
				? "Interactive 3D Preview • Drag to Orbit & Scroll to Zoom"
				: bundle.description())
			.addSummaryCard(summaryCard)
			.styleTag("ogsky-blueprint-preview")
			.build();

		OguiWidget.StructurePreviewWidget heroWidget = OguiWidget.structurePreview(
				"island_3d_view",
				OguiIcon.item(bundle.iconItemId()),
				OguiText.nonItalic(bundle.displayName() + " (" + activeDim.displayName() + ")", ChatFormatting.GREEN)
			)
			.structureAsset(shellRef)
			.initialRotation(42.0F, 26.0F)
			.autoRotate(false)
			.lore(
				OguiText.nonItalic("Viewing: " + activeDim.displayName(), ChatFormatting.DARK_AQUA),
				OguiText.nonItalic("Structure: " + boundsLabel, ChatFormatting.GRAY),
				OguiText.nonItalic("Controls: Drag to rotate, Scroll to zoom, R to reset", ChatFormatting.YELLOW)
			)
			.build();

		OguiScreenModel.DetailFormBuilder form = OguiScreenModel.detailForm(
				OguiText.nonItalic("Blueprint Preview: " + bundle.displayName(), ChatFormatting.AQUA)
			)
			.theme(theme)
			.heroCard(heroWidget)
			.addControl(buildDimensionControl(activeDim, supportedDims))
			.addControl(buildBlueprintSpecsControl(bundle, dimAsset, activeDim, permitted, isDefault));

		form.addAction(buildPreviewActionButton(viewer, bundle, targetIsland, resetMode, permitted));
		return form.build();
	}

	private OguiTab buildTab(PanelTab tab, PanelViewModelService.PanelViewData data, ServerPlayer viewer) {
		OguiIcon icon = switch (tab) {
			case ACTIONS -> OguiIcon.item(Items.NETHER_STAR);
			case PROTECTION_FLAGS -> OguiIcon.item(Items.IRON_SWORD);
			case SETTING_FLAGS -> OguiIcon.item(Items.REDSTONE);
			case MEMBERS -> OguiIcon.playerHead(viewer.getUUID());
			case BLUEPRINTS_INFO -> OguiIcon.item(Items.GRASS_BLOCK);
		};
		boolean hasIsland = data.islandId() != null;
		boolean permitted = core.permissions().hasPermission(viewer, tab.requiredPermission(), 0);
		boolean accessible = permitted && (hasIsland || tab == PanelTab.BLUEPRINTS_INFO);

		List<Component> lore = new ArrayList<>();
		lore.add(OguiText.nonItalic(tabSubtitle(tab), ChatFormatting.GRAY));
		if (!hasIsland && tab != PanelTab.BLUEPRINTS_INFO) {
			lore.add(OguiText.nonItalic("Requires an island first", ChatFormatting.RED));
		} else if (!permitted) {
			lore.add(OguiText.nonItalic("Missing permission", ChatFormatting.RED));
		}

		return OguiTab.builder(tab.id(), icon, OguiText.nonItalic(tab.displayName()))
			.lore(lore)
			.enabled(true)
			.onSelect(click -> {
				ServerPlayer actor = click.actor();
				if (data.islandId() == null && tab != PanelTab.BLUEPRINTS_INFO) {
					PanelFeedback.sendFeedback(actor, false, "Create an island first from Blueprints & Info.");
					return;
				}
				if (!core.permissions().hasPermission(actor, tab.requiredPermission(), 0)) {
					PanelFeedback.sendFeedback(actor, false, "You do not have permission to view " + tab.displayName() + ".");
					return;
				}
				if (tab == data.activeTab()) {
					return;
				}
				pendingConfirmations.remove(actor.getUUID());
				preferences.saveTabAsync(actor.getUUID(), tab);
				click.refreshWithStates(Map.of("tab", tab.id(), "search", ""));
			})
			.build();
	}

	private OguiSummaryCard buildSummaryCard(PanelViewModelService.PanelViewData data) {
		PanelSnapshot.IslandSummary summary = data.island();
		if (summary == null) {
			return OguiSummaryCard.of(
				OguiIcon.item(Items.BARRIER),
				OguiText.nonItalic("No Skyblock Island", ChatFormatting.RED),
				List.of(
					OguiText.nonItalic("You do not currently belong to an island here.", ChatFormatting.GRAY),
					OguiText.nonItalic("Pick a starter blueprint below or run /is create.", ChatFormatting.YELLOW)
				)
			);
		}
		List<Component> lore = new ArrayList<>();
		lore.add(OguiText.nonItalic("Owner: " + summary.ownerName(), ChatFormatting.GRAY));
		lore.add(OguiText.nonItalic("Grid Cell: (" + summary.gridX() + ", " + summary.gridZ() + ")", ChatFormatting.GRAY));
		lore.add(OguiText.nonItalic("Center: (" + summary.centerX() + ", " + summary.centerZ() + ")", ChatFormatting.GRAY));
		lore.add(OguiText.nonItalic("Size: " + summary.sideLength() + "x" + summary.sideLength() + " (halfSize=" + summary.halfSize() + ")", ChatFormatting.GRAY));
		lore.add(OguiText.nonItalic("Blueprint: " + summary.bundleId(), ChatFormatting.GRAY));
		lore.add(OguiText.nonItalic("Locked: " + (summary.locked() ? "Yes" : "No"), summary.locked() ? ChatFormatting.RED : ChatFormatting.GREEN));
		lore.add(OguiText.nonItalic("Members: " + summary.memberCount() + " | Banned: " + summary.bannedCount(), ChatFormatting.GRAY));
		lore.add(OguiText.nonItalic(
			"Dims: OW=" + yesNo(summary.overworldReady()) + " | Nether=" + yesNo(summary.netherReady()) + " | End=" + yesNo(summary.endReady()),
			ChatFormatting.DARK_AQUA
		));
		lore.add(OguiText.nonItalic("Created: " + DATE_FORMATTER.format(summary.createdAt()), ChatFormatting.DARK_GRAY));
		for (Component line : summary.contributedLines()) {
			lore.add(OguiText.nonItalic(line));
		}
		return OguiSummaryCard.of(
			OguiIcon.playerHead(summary.ownerId()),
			OguiText.nonItalic("Island Overview", ChatFormatting.GOLD),
			lore
		);
	}

	private void populateEntries(
		OguiScreenModel.TabbedPanelBuilder builder,
		PanelViewModelService.PanelViewData data,
		@Nullable PendingConfirmation pending,
		String searchQuery
	) {
		switch (data.activeTab()) {
			case ACTIONS -> {
				List<PanelSnapshot.ActionEntry> entries = data.actions();
				for (int i = 0; i < entries.size(); i++) {
					PanelSnapshot.ActionEntry entry = entries.get(i);
					builder.addEntry(buildActionWidget(i, entry, data));
				}
			}
			case PROTECTION_FLAGS -> {
				List<PanelSnapshot.ProtectionFlagEntry> entries = data.protectionFlags();
				for (int i = 0; i < entries.size(); i++) {
					builder.addEntry(buildProtectionFlagWidget(i, entries.get(i), data));
				}
			}
			case SETTING_FLAGS -> {
				List<PanelSnapshot.SettingFlagEntry> entries = data.settingFlags();
				for (int i = 0; i < entries.size(); i++) {
					builder.addEntry(buildSettingFlagWidget(i, entries.get(i), data));
				}
			}
			case MEMBERS -> {
				List<PanelSnapshot.MemberEntry> entries = data.members();
				for (int i = 0; i < entries.size(); i++) {
					builder.addEntry(buildMemberWidget(i, entries.get(i), data, pending));
				}
			}
			case BLUEPRINTS_INFO -> {
				blueprintAssets.syncIfNeeded();
				List<PanelSnapshot.BlueprintEntry> entries = data.blueprints();
				for (int i = 0; i < entries.size(); i++) {
					builder.addEntry(buildBlueprintWidget(i, entries.get(i), data));
				}
			}
		}

		if (!searchQuery.isBlank()) {
			builder.emptyState(
				OguiWidget.button("empty_search", "minecraft:compass", "No Matching Entries", ChatFormatting.YELLOW)
					.lore(
						OguiText.nonItalic("Search: '" + searchQuery + "'", ChatFormatting.GRAY),
						OguiText.nonItalic("Click to clear search filter", ChatFormatting.GREEN)
					)
					.onClick(click -> click.refreshWithState("search", ""))
					.build()
			);
		} else if (data.activeTab() == PanelTab.BLUEPRINTS_INFO) {
			builder.emptyState(buildNoBlueprintsEmptyWidget());
		}
	}

	private static OguiWidget.ButtonWidget buildNoBlueprintsEmptyWidget() {
		return OguiWidget.button("empty_blueprints", "minecraft:structure_block", "No Starter Blueprints Loaded", ChatFormatting.GOLD)
			.lore(
				OguiText.nonItalic("No blueprint bundles with overworld.nbt were found.", ChatFormatting.GRAY),
				OguiText.nonItalic("Add templates under config/ogsky/blueprints/<id>/", ChatFormatting.YELLOW),
				OguiText.nonItalic("then run /isadmin reload as an operator.", ChatFormatting.YELLOW)
			)
			.build();
	}

	private OguiWidget.ButtonWidget buildActionWidget(
		int index,
		PanelSnapshot.ActionEntry entry,
		PanelViewModelService.PanelViewData data
	) {
		List<Component> lore = new ArrayList<>();
		if (entry.activeState() != null) {
			boolean active = entry.activeState();
			lore.add(OguiText.nonItalic("State: " + (active ? "LOCKED" : "UNLOCKED"), active ? ChatFormatting.RED : ChatFormatting.GREEN));
		}
		if (!entry.description().isBlank()) {
			lore.add(OguiText.nonItalic(entry.description(), ChatFormatting.GRAY));
		}
		lore.add(OguiText.nonItalic("Action ID: " + entry.actionId(), ChatFormatting.DARK_GRAY));
		lore.add(OguiText.nonItalic("Click to execute", ChatFormatting.YELLOW));
		ChatFormatting titleColor = entry.activeState() == null
			? ChatFormatting.AQUA
			: (entry.activeState() ? ChatFormatting.RED : ChatFormatting.GREEN);
		boolean glint = Boolean.TRUE.equals(entry.activeState());

		OguiWidget.ButtonWidget.Builder b = OguiWidget.button(
			"action_" + index,
			OguiIcon.item(entry.iconItemId()).withGlowing(glint),
			OguiText.nonItalic(entry.title(), titleColor)
		).lore(lore);
		if (entry.activeState() != null) {
			b.badge(entry.activeState() ? "LOCKED" : "OPEN", entry.activeState() ? ChatFormatting.RED : ChatFormatting.GREEN);
		}
		b.onClick(click -> handleActionClick(click, data, entry));
		return b.build();
	}

	private OguiWidget.ButtonWidget buildProtectionFlagWidget(
		int index,
		PanelSnapshot.ProtectionFlagEntry entry,
		PanelViewModelService.PanelViewData data
	) {
		ChatFormatting rankColor = rankColor(entry.minRank());
		List<Component> lore = new ArrayList<>();
		lore.add(OguiText.nonItalic("Required Rank: " + entry.minRankName(), rankColor));
		lore.add(OguiText.nonItalic("Default: " + entry.defaultMinRankName() + " | Mode: " + modeLabel(entry.mode()), ChatFormatting.DARK_GRAY));
		if (entry.editable()) {
			lore.add(OguiText.nonItalic("Left-click: Raise rank | Right-click: Lower rank", ChatFormatting.YELLOW));
			lore.add(OguiText.nonItalic("Shift-click: Reset to default", ChatFormatting.GOLD));
		} else {
			lore.add(OguiText.nonItalic("View only (requires Admin rank)", ChatFormatting.RED));
		}
		boolean modified = entry.minRank() != entry.defaultMinRank();
		return OguiWidget.button(
				"pflag_" + index,
				OguiIcon.item(entry.iconItemId()).withGlowing(modified),
				OguiText.nonItalic(entry.title(), ChatFormatting.GOLD)
			)
			.badge(entry.minRankName(), rankColor)
			.lore(lore)
			.onClick(click -> handleProtectionFlagClick(click, data, entry))
			.build();
	}

	private OguiWidget.ButtonWidget buildSettingFlagWidget(
		int index,
		PanelSnapshot.SettingFlagEntry entry,
		PanelViewModelService.PanelViewData data
	) {
		List<Component> lore = new ArrayList<>();
		lore.add(OguiText.nonItalic(
			"State: " + (entry.enabled() ? "Enabled" : "Disabled"),
			entry.enabled() ? ChatFormatting.GREEN : ChatFormatting.RED
		));
		lore.add(OguiText.nonItalic("Default: " + (entry.defaultValue() ? "Enabled" : "Disabled") + " | Mode: " + modeLabel(entry.mode()), ChatFormatting.DARK_GRAY));
		if (entry.editable()) {
			lore.add(OguiText.nonItalic("Click: Toggle setting | Shift-click: Reset default", ChatFormatting.YELLOW));
		} else {
			lore.add(OguiText.nonItalic("View only (requires Admin rank)", ChatFormatting.RED));
		}
		return OguiWidget.button(
				"sflag_" + index,
				OguiIcon.item(entry.iconItemId()).withGlowing(entry.enabled()),
				OguiText.nonItalic(entry.title(), entry.enabled() ? ChatFormatting.GREEN : ChatFormatting.GRAY)
			)
			.badge(entry.enabled() ? "ON" : "OFF", entry.enabled() ? ChatFormatting.GREEN : ChatFormatting.RED)
			.lore(lore)
			.onClick(click -> handleSettingFlagClick(click, data, entry))
			.build();
	}

	private OguiWidget.ButtonWidget buildMemberWidget(
		int index,
		PanelSnapshot.MemberEntry entry,
		PanelViewModelService.PanelViewData data,
		@Nullable PendingConfirmation pendingConfirm
	) {
		ChatFormatting roleColor = switch (entry.kind()) {
			case PRIMARY_MEMBER -> rankColor(entry.role());
			case SECONDARY_HELPER -> ChatFormatting.LIGHT_PURPLE;
			case BANNED_PLAYER -> ChatFormatting.DARK_RED;
		};
		List<Component> lore = new ArrayList<>();
		lore.add(OguiText.nonItalic("Role: " + entry.roleName(), roleColor));
		lore.add(OguiText.nonItalic("Category: " + switch (entry.kind()) {
			case PRIMARY_MEMBER -> "Primary Member";
			case SECONDARY_HELPER -> "Secondary Helper";
			case BANNED_PLAYER -> "Banned Player";
		}, ChatFormatting.GRAY));

		boolean confirmKick = isPending(pendingConfirm, ConfirmKind.KICK_MEMBER, entry.playerUuid().toString());
		boolean confirmBan = isPending(pendingConfirm, ConfirmKind.BAN_PLAYER, entry.playerUuid().toString());
		if (entry.canUnban()) {
			lore.add(OguiText.nonItalic("Click: Unban player", ChatFormatting.GREEN));
		}
		if (entry.kind() == PanelSnapshot.MemberEntry.Kind.SECONDARY_HELPER && entry.canRemoveOrKick()) {
			lore.add(OguiText.nonItalic("Click: Remove " + entry.roleName() + " role", ChatFormatting.YELLOW));
		}
		if (entry.canPromote()) {
			lore.add(OguiText.nonItalic("Left-click: Promote to Admin", ChatFormatting.GREEN));
		}
		if (entry.canDemote()) {
			lore.add(OguiText.nonItalic("Right-click: Demote to Member", ChatFormatting.GOLD));
		}
		if (entry.kind() == PanelSnapshot.MemberEntry.Kind.PRIMARY_MEMBER && entry.canRemoveOrKick()) {
			lore.add(OguiText.nonItalic(
				confirmKick ? "CONFIRM: Shift-Left-click again to KICK!" : "Shift-Left-click: Kick member",
				confirmKick ? ChatFormatting.RED : ChatFormatting.YELLOW
			));
		}
		if (entry.canBan()) {
			lore.add(OguiText.nonItalic(
				confirmBan ? "CONFIRM: Shift-Right-click again to BAN!" : "Shift-Right-click: Ban player",
				confirmBan ? ChatFormatting.DARK_RED : ChatFormatting.RED
			));
		}

		return OguiWidget.button(
				"member_" + index,
				OguiIcon.playerHead(entry.playerUuid()).withGlowing(confirmKick || confirmBan),
				OguiText.nonItalic(entry.displayName(), roleColor)
			)
			.badge(entry.roleName(), roleColor)
			.lore(lore)
			.onClick(click -> handleMemberClick(click, data, entry))
			.build();
	}

	private OguiWidget.ButtonWidget buildBlueprintWidget(
		int index,
		PanelSnapshot.BlueprintEntry entry,
		PanelViewModelService.PanelViewData data
	) {
		return buildBlueprintCardWidget(
			"bp_" + index,
			entry.id(),
			entry.displayName(),
			entry.description(),
			entry.iconItemId(),
			entry.hasOverworld(),
			entry.hasNether(),
			entry.hasEnd(),
			entry.defaultBundle(),
			entry.activeOnIsland(),
			entry.permitted(),
			data.islandId(),
			data.islandId() != null
		);
	}

	private OguiWidget.ButtonWidget buildBlueprintCardWidget(
		String widgetId,
		String bundleId,
		String displayName,
		String description,
		String iconItemId,
		boolean hasOverworld,
		boolean hasNether,
		boolean hasEnd,
		boolean defaultBundle,
		boolean activeOnIsland,
		boolean permitted,
		@Nullable UUID targetIslandId,
		boolean resetMode
	) {
		List<Component> lore = new ArrayList<>();
		if (!description.isBlank()) {
			lore.add(OguiText.nonItalic(description, ChatFormatting.GRAY));
		}
		lore.add(OguiText.nonItalic(
			"Dimensions: OW=" + yesNo(hasOverworld) + " | Nether=" + yesNo(hasNether) + " | End=" + yesNo(hasEnd),
			ChatFormatting.DARK_AQUA
		));
		if (activeOnIsland) {
			lore.add(OguiText.nonItalic("Currently active on this island", ChatFormatting.GREEN));
		}
		if (defaultBundle) {
			lore.add(OguiText.nonItalic("Default server starter blueprint", ChatFormatting.AQUA));
		}
		if (!hasOverworld) {
			lore.add(OguiText.nonItalic("Unavailable: missing overworld.nbt", ChatFormatting.RED));
		} else if (!permitted) {
			lore.add(OguiText.nonItalic("Locked: missing blueprint permission (preview only)", ChatFormatting.RED));
		} else if (resetMode) {
			lore.add(OguiText.nonItalic("Click for 3D preview & island reset", ChatFormatting.GOLD));
		} else {
			lore.add(OguiText.nonItalic("Click for 3D preview & island creation", ChatFormatting.GREEN));
		}

		OguiAssetRef previewPng = blueprintAssets.previewPng(bundleId);
		OguiWidget.ButtonWidget.Builder b = OguiWidget.button(
				widgetId,
				OguiIcon.item(iconItemId).withGlowing(activeOnIsland),
				OguiText.nonItalic(displayName, activeOnIsland ? ChatFormatting.GREEN : ChatFormatting.AQUA)
			)
			.previewImage(previewPng)
			.lore(lore);
		if (!hasOverworld) {
			b.badge("INCOMPLETE", ChatFormatting.RED);
		} else if (!permitted) {
			b.badge("LOCKED", ChatFormatting.RED);
		} else if (activeOnIsland) {
			b.badge("ACTIVE", ChatFormatting.GREEN);
		} else if (defaultBundle) {
			b.badge("DEFAULT", ChatFormatting.AQUA);
		} else if (hasNether || hasEnd) {
			b.badge("MULTI-DIM", ChatFormatting.GOLD);
		}
		b.onClick(click -> openBlueprintPreview(click, bundleId, targetIslandId, resetMode));
		return b.build();
	}

	private void handleQuickLockToggle(OguiClickContext click, PanelViewModelService.PanelViewData data) {
		PanelSnapshot.IslandSummary summary = data.island();
		if (summary == null || data.islandId() == null) {
			return;
		}
		ServerPlayer player = click.actor();
		boolean canEdit = data.viewerAdminBypass() || data.viewerRole() >= IslandRole.ADMIN;
		if (!canEdit) {
			PanelFeedback.sendFeedback(player, false, "Only island admins, the owner, or bypass staff can modify island lock.");
			return;
		}
		boolean nextLocked = !summary.locked();
		boolean bypass = core.permissions().isBypassActive(player);
		IslandOperationResult<Boolean> result = core.islands().setSettingFlag(
			player.level().getServer(),
			player.getUUID(),
			data.islandId(),
			PanelViewModelService.ISLAND_LOCK_FLAG,
			nextLocked,
			bypass
		);
		PanelFeedback.applyMutationFeedback(player, result.success(), result.message());
		click.refresh();
	}

	private void handleActionClick(
		OguiClickContext click,
		PanelViewModelService.PanelViewData data,
		PanelSnapshot.ActionEntry entry
	) {
		if (data.islandId() == null) {
			return;
		}
		ServerPlayer player = click.actor();
		Optional<IslandActionDescriptor> descriptor = core.actions().find(entry.actionId());
		if (descriptor.isEmpty()) {
			PanelFeedback.playErrorSound(player);
			click.refresh();
			return;
		}
		descriptor.get().handler().execute(new IslandActionContext(player, data.islandId()));
		if (entry.closePanelOnExecute()) {
			click.close();
		} else {
			boolean stillMainPanel = Ogui.service().activeRequest(player)
				.map(req -> GuiService.MAIN_PANEL_SCREEN_ID.equals(req.screenId()))
				.orElse(false);
			if (stillMainPanel) {
				click.refresh();
			}
		}
	}

	private void handleProtectionFlagClick(
		OguiClickContext click,
		PanelViewModelService.PanelViewData data,
		PanelSnapshot.ProtectionFlagEntry entry
	) {
		if (data.islandId() == null) {
			return;
		}
		ServerPlayer player = click.actor();
		if (!entry.editable()) {
			PanelFeedback.sendFeedback(player, false, "Only island admins, the owner, or bypass staff can modify protection flags.");
			return;
		}
		OguiClickAction action = click.clickAction();
		byte nextRank;
		if (action.isShift()) {
			nextRank = entry.defaultMinRank();
		} else if (action.isRight()) {
			nextRank = (byte) ((entry.minRank() + 6) % 7);
		} else {
			nextRank = (byte) ((entry.minRank() + 1) % 7);
		}
		boolean bypass = core.permissions().isBypassActive(player);
		IslandOperationResult<Byte> result = core.islands().setProtectionRank(
			player.getUUID(),
			data.islandId(),
			entry.flagId(),
			nextRank,
			bypass
		);
		PanelFeedback.applyMutationFeedback(player, result.success(), result.message());
		click.refresh();
	}

	private void handleSettingFlagClick(
		OguiClickContext click,
		PanelViewModelService.PanelViewData data,
		PanelSnapshot.SettingFlagEntry entry
	) {
		if (data.islandId() == null) {
			return;
		}
		ServerPlayer player = click.actor();
		if (!entry.editable()) {
			PanelFeedback.sendFeedback(player, false, "Only island admins, the owner, or bypass staff can modify island settings.");
			return;
		}
		boolean nextValue = click.clickAction().isShift() ? entry.defaultValue() : !entry.enabled();
		boolean bypass = core.permissions().isBypassActive(player);
		IslandOperationResult<Boolean> result = core.islands().setSettingFlag(
			player.level().getServer(),
			player.getUUID(),
			data.islandId(),
			entry.flagId(),
			nextValue,
			bypass
		);
		PanelFeedback.applyMutationFeedback(player, result.success(), result.message());
		click.refresh();
	}

	private void handleMemberClick(
		OguiClickContext click,
		PanelViewModelService.PanelViewData data,
		PanelSnapshot.MemberEntry entry
	) {
		UUID islandId = data.islandId();
		if (islandId == null) {
			return;
		}
		ServerPlayer player = click.actor();
		MinecraftServer server = player.level().getServer();
		boolean bypass = core.permissions().isBypassActive(player);
		UUID actorId = player.getUUID();
		UUID targetId = entry.playerUuid();
		OguiClickAction action = click.clickAction();

		if (entry.kind() == PanelSnapshot.MemberEntry.Kind.BANNED_PLAYER) {
			if (!entry.canUnban()) {
				PanelFeedback.sendFeedback(player, false, "Only island admins or the owner can unban players.");
				return;
			}
			pendingConfirmations.remove(actorId);
			IslandOperationResult<?> result = core.islands().unbanPlayer(islandId, actorId, targetId, bypass);
			PanelFeedback.applyMutationFeedback(player, result.success(), result.message());
			click.refresh();
			return;
		}

		if (action == OguiClickAction.SHIFT_RIGHT) {
			if (!entry.canBan()) {
				PanelFeedback.sendFeedback(player, false, "You cannot ban this player.");
				return;
			}
			if (!consumeOrPromptConfirmation(
				player,
				ConfirmKind.BAN_PLAYER,
				islandId,
				targetId.toString(),
				"Shift-Right-click " + entry.displayName() + " again within 10s to confirm BAN!"
			)) {
				click.refresh();
				return;
			}
			IslandOperationResult<?> result = core.islands().banPlayer(server, islandId, actorId, targetId, bypass);
			PanelFeedback.applyMutationFeedback(player, result.success(), result.message());
			click.refresh();
			return;
		}

		if (entry.kind() == PanelSnapshot.MemberEntry.Kind.SECONDARY_HELPER) {
			if (!entry.canRemoveOrKick()) {
				PanelFeedback.sendFeedback(player, false, "Only island admins or the owner can remove secondary helpers.");
				return;
			}
			pendingConfirmations.remove(actorId);
			IslandOperationResult<?> result = entry.role() == IslandRole.COOP
				? core.islands().removeCoop(server, islandId, actorId, targetId, bypass)
				: core.islands().removeTrust(server, islandId, actorId, targetId, bypass);
			PanelFeedback.applyMutationFeedback(player, result.success(), result.message());
			click.refresh();
			return;
		}

		if (action == OguiClickAction.SHIFT_LEFT) {
			if (!entry.canRemoveOrKick()) {
				PanelFeedback.sendFeedback(player, false, "You cannot kick this member.");
				return;
			}
			if (!consumeOrPromptConfirmation(
				player,
				ConfirmKind.KICK_MEMBER,
				islandId,
				targetId.toString(),
				"Shift-Left-click " + entry.displayName() + " again within 10s to confirm KICK!"
			)) {
				click.refresh();
				return;
			}
			IslandOperationResult<?> result = core.islands().kickMember(server, islandId, actorId, targetId, bypass);
			PanelFeedback.applyMutationFeedback(player, result.success(), result.message());
			click.refresh();
			return;
		}

		pendingConfirmations.remove(actorId);
		if (action.isLeft()) {
			if (!entry.canPromote()) {
				PanelFeedback.sendFeedback(player, false, "Only the island owner can promote members to Admin.");
				return;
			}
			IslandOperationResult<?> result = core.islands().promoteMember(islandId, actorId, targetId, bypass);
			PanelFeedback.applyMutationFeedback(player, result.success(), result.message());
		} else {
			if (!entry.canDemote()) {
				PanelFeedback.sendFeedback(player, false, "Only the island owner can demote Admins.");
				return;
			}
			IslandOperationResult<?> result = core.islands().demoteMember(islandId, actorId, targetId, bypass);
			PanelFeedback.applyMutationFeedback(player, result.success(), result.message());
		}
		click.refresh();
	}

	private static void openBlueprintPreview(
		OguiClickContext click,
		String bundleId,
		@Nullable UUID islandId,
		boolean resetMode
	) {
		OguiOpenRequest req = OguiOpenRequest.of(
				GuiService.BLUEPRINT_PREVIEW_SCREEN_ID,
				islandId == null ? null : islandId.toString()
			)
			.withState("bundle", bundleId)
			.withState("dim", BlueprintDimension.OVERWORLD.key())
			.withState("mode", resetMode ? "reset" : "create");
		click.openScreen(req);
	}

	private OguiWidget buildDimensionControl(
		BlueprintDimension activeDim,
		List<BlueprintDimension> supportedDims
	) {
		if (supportedDims.size() <= 1) {
			return OguiWidget.button(
					"preview_dim_info",
					OguiIcon.item(dimensionIconId(activeDim)),
					OguiText.nonItalic("Dimension: " + activeDim.displayName(), ChatFormatting.AQUA)
				)
				.enabled(false)
				.lore(OguiText.nonItalic("Only " + activeDim.displayName() + " structure is included in this bundle.", ChatFormatting.GRAY))
				.build();
		}
		OguiWidget.CycleWidget.Builder cycle = OguiWidget.cycle(
				"preview_dim",
				OguiIcon.item(Items.ENDER_PEARL),
				OguiText.nonItalic("Preview Dimension", ChatFormatting.AQUA)
			)
			.lore(OguiText.nonItalic("Switch which island dimension to inspect in 3D", ChatFormatting.GRAY));
		for (BlueprintDimension dim : supportedDims) {
			cycle.addOption(dim.key(), OguiText.nonItalic(dim.displayName(), dimensionColor(dim)), OguiIcon.item(dimensionIconId(dim)));
		}
		cycle.selectedId(activeDim.key());
		cycle.onCycle((click, opt) -> click.refreshWithState("dim", opt.id()));
		return cycle.build();
	}

	private static OguiWidget.ButtonWidget buildBlueprintSpecsControl(
		BlueprintBundleView bundle,
		PanelBlueprintAssetManager.@Nullable DimensionStructureAsset dimAsset,
		BlueprintDimension activeDim,
		boolean permitted,
		boolean isDefault
	) {
		List<Component> lore = new ArrayList<>();
		lore.add(OguiText.nonItalic("ID: " + bundle.id(), ChatFormatting.DARK_GRAY));
		if (!bundle.description().isBlank()) {
			lore.add(OguiText.nonItalic(bundle.description(), ChatFormatting.GRAY));
		}
		lore.add(OguiText.nonItalic(
			"Dimensions: OW=" + yesNo(bundle.hasOverworld()) + " | Nether=" + yesNo(bundle.hasNether()) + " | End=" + yesNo(bundle.hasEnd()),
			ChatFormatting.DARK_AQUA
		));
		if (dimAsset != null) {
			lore.add(OguiText.nonItalic(
				activeDim.displayName() + " Bounds: " + dimAsset.sizeX() + "x" + dimAsset.sizeY() + "x" + dimAsset.sizeZ()
					+ " (" + dimAsset.blockCount() + " blocks)",
				ChatFormatting.GRAY
			));
		}
		lore.add(OguiText.nonItalic("Default Starter: " + yesNo(isDefault), ChatFormatting.AQUA));
		lore.add(OguiText.nonItalic(
			permitted ? "Permission: Unlocked" : "Permission Required: " + bundle.permissionNode(),
			permitted ? ChatFormatting.GREEN : ChatFormatting.RED
		));
		return OguiWidget.button(
				"blueprint_specs",
				OguiIcon.item(bundle.iconItemId()),
				OguiText.nonItalic("Blueprint Specifications", ChatFormatting.GOLD)
			)
			.enabled(false)
			.lore(lore)
			.build();
	}

	private OguiWidget.ButtonWidget buildPreviewActionButton(
		ServerPlayer viewer,
		BlueprintBundleView bundle,
		@Nullable IslandView targetIsland,
		boolean resetMode,
		boolean permitted
	) {
		if (!permitted) {
			return OguiWidget.button(
					"locked_blueprint",
					OguiIcon.item(Items.BARRIER),
					OguiText.nonItalic("Locked: Missing Permission", ChatFormatting.RED)
				)
				.enabled(false)
				.badge("LOCKED", ChatFormatting.RED)
				.lore(OguiText.nonItalic("Requires permission: " + bundle.permissionNode(), ChatFormatting.GRAY))
				.build();
		}
		if (!bundle.hasOverworld()) {
			return OguiWidget.button(
					"incomplete_blueprint",
					OguiIcon.item(Items.STRUCTURE_VOID),
					OguiText.nonItalic("Overworld Template Missing", ChatFormatting.RED)
				)
				.enabled(false)
				.lore(OguiText.nonItalic("This bundle has no overworld.nbt and cannot be used.", ChatFormatting.GRAY))
				.build();
		}
		if (!resetMode || targetIsland == null) {
			return OguiWidget.button(
					"create_island_confirm",
					OguiIcon.item(Items.EMERALD_BLOCK),
					OguiText.nonItalic("Create Island", ChatFormatting.GREEN)
				)
				.badge("CONFIRM", ChatFormatting.GREEN)
				.lore(
					OguiText.nonItalic("Creates your Skyblock island with '" + bundle.displayName() + "'.", ChatFormatting.GRAY),
					OguiText.nonItalic("Click again within 10s to confirm!", ChatFormatting.YELLOW)
				)
				.confirm(OguiConfirmSpec.of(
					"confirm_create_" + bundle.id(),
					OguiText.nonItalic("Click again to confirm creating this island!", ChatFormatting.GREEN)
				))
				.onClick(click -> executeCreateFromUi(click, bundle))
				.build();
		}

		boolean bypass = core.permissions().isBypassActive(viewer);
		boolean canReset = bypass || targetIsland.ownerId().equals(viewer.getUUID());
		if (!canReset) {
			return OguiWidget.button(
					"reset_owner_only",
					OguiIcon.item(Items.BARRIER),
					OguiText.nonItalic("Owner Only", ChatFormatting.RED)
				)
				.enabled(false)
				.lore(OguiText.nonItalic("Only the island owner can reset this island.", ChatFormatting.GRAY))
				.build();
		}
		return OguiWidget.button(
				"reset_island_confirm",
				OguiIcon.item(Items.TNT),
				OguiText.nonItalic("Reset Island", ChatFormatting.GOLD)
			)
			.badge("DANGER", ChatFormatting.RED)
			.lore(
				OguiText.nonItalic("Resets your island to '" + bundle.displayName() + "'.", ChatFormatting.RED),
				OguiText.nonItalic("Click again within 10s to confirm!", ChatFormatting.YELLOW)
			)
			.confirm(OguiConfirmSpec.of(
				"confirm_reset_" + bundle.id(),
				OguiText.nonItalic("Click again to confirm RESETTING your island!", ChatFormatting.RED)
			))
			.onClick(click -> executeResetFromUi(click, targetIsland.islandId(), bundle))
			.build();
	}

	private void executeCreateFromUi(OguiClickContext click, BlueprintBundleView bundle) {
		ServerPlayer player = click.actor();
		if (!core.permissions().hasBlueprintPermission(player, bundle)) {
			PanelFeedback.sendFeedback(player, false, "You do not have permission to use blueprint '" + bundle.id() + "'.");
			return;
		}
		MinecraftServer server = player.level().getServer();
		IslandOperationResult<IslandView> result = core.islands().createIsland(
			server,
			player,
			player.getUUID(),
			bundle.id()
		);
		PanelFeedback.applyMutationFeedback(player, result.success(), result.message());
		if (result.success()) {
			click.close();
		} else {
			click.refresh();
		}
	}

	private void executeResetFromUi(OguiClickContext click, UUID islandId, BlueprintBundleView bundle) {
		ServerPlayer player = click.actor();
		if (!core.permissions().hasBlueprintPermission(player, bundle)) {
			PanelFeedback.sendFeedback(player, false, "You do not have permission to use blueprint '" + bundle.id() + "'.");
			return;
		}
		boolean bypass = core.permissions().isBypassActive(player);
		Optional<IslandView> target = core.islands().findById(islandId);
		if (target.isEmpty()) {
			PanelFeedback.sendFeedback(player, false, "Target island no longer exists.");
			click.close();
			return;
		}
		if (!target.get().ownerId().equals(player.getUUID()) && !bypass) {
			PanelFeedback.sendFeedback(player, false, "Only the island owner can reset the island.");
			return;
		}
		MinecraftServer server = player.level().getServer();
		IslandOperationResult<IslandView> result = core.islands().resetIsland(
			server,
			player.getUUID(),
			islandId,
			bundle.id(),
			bypass
		);
		PanelFeedback.applyMutationFeedback(player, result.success(), result.message());
		if (result.success()) {
			click.close();
		} else {
			click.refresh();
		}
	}

	private @Nullable IslandView resolveTargetIslandForBlueprintFlow(
		ServerPlayer viewer,
		@Nullable UUID contextIslandId,
		boolean resetModeRequested
	) {
		if (contextIslandId != null) {
			Optional<IslandView> byId = core.islands().findById(contextIslandId);
			if (byId.isPresent()) {
				return byId.get();
			}
		}
		if (resetModeRequested) {
			return core.islands().findPrimaryIsland(viewer.getUUID()).orElse(null);
		}
		return null;
	}

	private @Nullable BlueprintBundleView findBlueprintView(String bundleId) {
		if (bundleId == null || bundleId.isBlank()) {
			return null;
		}
		String normalized = bundleId.trim().toLowerCase(Locale.ROOT);
		for (BlueprintBundleView bundle : core.islands().availableBlueprints()) {
			if (bundle.id().equalsIgnoreCase(normalized)) {
				return bundle;
			}
		}
		return null;
	}

	private static boolean matchesBlueprintSearch(BlueprintBundleView bundle, String normalizedSearch) {
		if (normalizedSearch.isEmpty()) {
			return true;
		}
		return bundle.id().toLowerCase(Locale.ROOT).contains(normalizedSearch)
			|| bundle.displayName().toLowerCase(Locale.ROOT).contains(normalizedSearch)
			|| bundle.description().toLowerCase(Locale.ROOT).contains(normalizedSearch);
	}

	private static List<BlueprintDimension> supportedDimensions(BlueprintBundleView bundle) {
		List<BlueprintDimension> dims = new ArrayList<>(3);
		for (BlueprintDimension dim : BlueprintDimension.values()) {
			if (dim.isSupportedBy(bundle)) {
				dims.add(dim);
			}
		}
		if (dims.isEmpty()) {
			dims.add(BlueprintDimension.OVERWORLD);
		}
		return List.copyOf(dims);
	}

	private static BlueprintDimension resolveActiveDimension(
		String requestedKey,
		List<BlueprintDimension> supportedDims
	) {
		BlueprintDimension parsed = BlueprintDimension.fromKey(requestedKey).orElse(supportedDims.getFirst());
		if (supportedDims.contains(parsed)) {
			return parsed;
		}
		return supportedDims.getFirst();
	}

	private static String dimensionIconId(BlueprintDimension dim) {
		return switch (dim) {
			case OVERWORLD -> "minecraft:grass_block";
			case NETHER -> "minecraft:netherrack";
			case END -> "minecraft:end_stone";
		};
	}

	private static ChatFormatting dimensionColor(BlueprintDimension dim) {
		return switch (dim) {
			case OVERWORLD -> ChatFormatting.GREEN;
			case NETHER -> ChatFormatting.RED;
			case END -> ChatFormatting.LIGHT_PURPLE;
		};
	}

	private void onBlueprintsReloaded() {
		blueprintAssets.invalidate();
		blueprintAssets.syncIfNeeded();
		Ogui.service().refreshMatchingScreens(req ->
			GuiService.CREATE_GALLERY_SCREEN_ID.equals(req.screenId())
				|| GuiService.BLUEPRINT_PREVIEW_SCREEN_ID.equals(req.screenId())
				|| GuiService.MAIN_PANEL_SCREEN_ID.equals(req.screenId())
		);
	}

	private boolean consumeOrPromptConfirmation(
		ServerPlayer player,
		ConfirmKind kind,
		@Nullable UUID islandId,
		String targetKey,
		String promptMessage
	) {
		UUID playerUuid = player.getUUID();
		PendingConfirmation existing = pendingConfirmations.get(playerUuid);
		if (existing != null
			&& !existing.isExpired()
			&& existing.kind() == kind
			&& Objects.equals(existing.islandId(), islandId)
			&& existing.targetKey().equals(targetKey)) {
			pendingConfirmations.remove(playerUuid);
			return true;
		}
		pendingConfirmations.put(
			playerUuid,
			new PendingConfirmation(kind, islandId, targetKey, Instant.now().plus(CONFIRM_TTL))
		);
		Ogui.service().sendFeedback(player, true, Component.literal("[Ogsky] " + promptMessage).withStyle(ChatFormatting.YELLOW));
		return false;
	}

	private @Nullable PendingConfirmation activeConfirmation(UUID playerUuid) {
		PendingConfirmation pending = pendingConfirmations.get(playerUuid);
		if (pending != null && pending.isExpired()) {
			pendingConfirmations.remove(playerUuid, pending);
			return null;
		}
		return pending;
	}

	private void scheduleIslandRefresh(UUID islandId) {
		String islandKey = islandId.toString();
		Ogui.service().refreshMatchingScreens(req -> islandKey.equals(req.contextKey()));
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

	private static String tabSubtitle(PanelTab tab) {
		return switch (tab) {
			case ACTIONS -> "Teleport, lock & quick island actions";
			case PROTECTION_FLAGS -> "Configure who can interact on your island";
			case SETTING_FLAGS -> "Toggle island behavior & environment rules";
			case MEMBERS -> "Manage members, coop, trust & bans";
			case BLUEPRINTS_INFO -> "Create or reset your Skyblock island";
		};
	}
}
