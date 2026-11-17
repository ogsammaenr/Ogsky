package me.ogsammaenr.ogsky.warps;

import me.ogsammaenr.ogsky.core.api.OgskyCoreApi;
import me.ogsammaenr.ogsky.core.api.action.IslandActionDescriptor;
import me.ogsammaenr.ogsky.core.api.gui.GuiBuildContext;
import me.ogsammaenr.ogsky.core.api.gui.GuiButton;
import me.ogsammaenr.ogsky.core.api.gui.GuiClickAction;
import me.ogsammaenr.ogsky.core.api.gui.GuiClickContext;
import me.ogsammaenr.ogsky.core.api.gui.GuiOpenRequest;
import me.ogsammaenr.ogsky.core.api.gui.GuiScreenModel;
import me.ogsammaenr.ogsky.core.api.island.IslandOperationResult;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import me.ogsammaenr.ogsky.core.api.level.IslandLevelProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Registers {@code ogsky-warps} panel summary lines, declarative screens, and action entries
 * via UI-agnostic {@code core.guis()} and {@code core.actions()}.
 */
final class WarpsGuiRegistrar {
	static final Identifier SUMMARY_CONTRIBUTOR_ID = Identifier.fromNamespaceAndPath("ogsky_warps", "summary");
	static final Identifier SHOWCASE_SCREEN = Identifier.fromNamespaceAndPath("ogsky_warps", "showcase");
	static final Identifier MANAGE_SCREEN = Identifier.fromNamespaceAndPath("ogsky_warps", "manage");
	static final Identifier EDIT_SCREEN = Identifier.fromNamespaceAndPath("ogsky_warps", "edit");
	static final Identifier ACTION_WARP_SHOWCASE = Identifier.fromNamespaceAndPath("ogsky_warps", "showcase");
	static final Identifier ACTION_MANAGE_WARPS = Identifier.fromNamespaceAndPath("ogsky_warps", "manage_warps");

	private final OgskyCoreApi core;
	private final IslandWarpService warpService;
	private final WarpsCommands commands;

	WarpsGuiRegistrar(OgskyCoreApi core, IslandWarpService warpService, WarpsCommands commands) {
		this.core = core;
		this.warpService = warpService;
		this.commands = commands;
	}

	void registerAll() {
		core.guis().registerSummaryContributor(SUMMARY_CONTRIBUTOR_ID, 40, ctx -> {
			List<IslandWarp> warps = warpService.getIslandWarps(ctx.islandId());
			if (warps.isEmpty()) {
				return List.of(
					GuiButton.nonItalic("Warps: None set (/is setwarp)", ChatFormatting.GRAY)
				);
			}
			long active = warps.stream().filter(IslandWarp::enabled).count();
			long visits = warps.stream().mapToLong(IslandWarp::visitCount).sum();
			return List.of(
				GuiButton.nonItalic(
					"Warps: " + active + "/" + warps.size() + " active (" + visits + " visits)",
					active > 0 ? ChatFormatting.AQUA : ChatFormatting.YELLOW
				)
			);
		});

		core.guis().registerScreen(SHOWCASE_SCREEN, this::buildShowcaseScreen);
		core.guis().registerScreen(MANAGE_SCREEN, this::buildManageScreen);
		core.guis().registerScreen(EDIT_SCREEN, this::buildEditScreen);

		core.actions().register(new IslandActionDescriptor(
			ACTION_WARP_SHOWCASE,
			"Island Warps",
			"Browse and teleport to public island warps.",
			"minecraft:ender_eye",
			40,
			false,
			ctx -> executeActionShowcase(ctx.actor())
		));
		core.actions().register(new IslandActionDescriptor(
			ACTION_MANAGE_WARPS,
			"Manage Warps",
			"Inspect, customize, toggle, and locate your island warps.",
			"minecraft:lodestone",
			42,
			false,
			ctx -> executeActionManage(ctx.actor(), ctx.islandId())
		));
	}

	private GuiScreenModel buildShowcaseScreen(GuiBuildContext ctx) {
		ServerPlayer viewer = ctx.viewer();
		MinecraftServer server = viewer.level().getServer();
		WarpConfig cfg = warpService.config();

		String category = WarpHelper.normalizeCategoryFilter(ctx.stateOrDefault("category", "all"), cfg);
		String sort = WarpHelper.normalizeSort(ctx.stateOrDefault("sort", "recent"));
		String query = ctx.stateOrDefault("query", "").trim();
		UUID filterIslandId = ctx.islandId();

		boolean hasLevel = core.services().find(IslandLevelProvider.class).isPresent();
		String nextSort = nextSortMode(sort, hasLevel);
		String sortLabel = sortLabel(sort);

		String title = resolveShowcaseTitle(server, cfg, category, query, filterIslandId);
		GuiScreenModel.Builder builder = GuiScreenModel.paged(title)
			.contextButton(GuiButton.builder("minecraft:comparator", sortLabel, ChatFormatting.GOLD)
				.lore(GuiButton.nonItalic("Click to cycle sort order", ChatFormatting.GRAY))
				.onClick(click -> click.refreshWithState("sort", nextSort))
				.build())
			.emptyStateButton(GuiButton.builder("minecraft:barrier", "No Active Warps Found", ChatFormatting.RED)
				.lore(GuiButton.nonItalic("Try another category or clear search filters.", ChatFormatting.GRAY))
				.build());

		// Header slot 0: All categories
		builder.pinButton(0, GuiButton.builder("minecraft:nether_star", "All Categories", ChatFormatting.YELLOW)
			.glowing("all".equals(category))
			.lore(GuiButton.nonItalic("Show warps across every category", ChatFormatting.GRAY))
			.onClick(click -> click.refreshWithState("category", "all"))
			.build());

		List<WarpConfig.CategorySpec> categories = cfg.categories;
		int directSlots = categories.size() <= 6 ? categories.size() : 5;
		for (int i = 0; i < directSlots; i++) {
			WarpConfig.CategorySpec spec = categories.get(i);
			boolean selected = spec.id.equals(category);
			builder.pinButton(i + 1, GuiButton.builder(spec.iconItemId, spec.displayName, ChatFormatting.AQUA)
				.glowing(selected)
				.lore(GuiButton.nonItalic(selected ? "Selected category" : "Click to filter by " + spec.displayName, ChatFormatting.GRAY))
				.onClick(click -> click.refreshWithState("category", spec.id))
				.build());
		}
		if (categories.size() > 6) {
			String nextCat = nextCategoryAfter(categories, category);
			builder.pinButton(6, GuiButton.builder("minecraft:hopper", "More Categories", ChatFormatting.LIGHT_PURPLE)
				.lore(GuiButton.nonItalic("Current: " + ("all".equals(category) ? "All" : cfg.categoryDisplayName(category)), ChatFormatting.GRAY))
				.onClick(click -> click.refreshWithState("category", nextCat))
				.build());
		}

		// Header slot 7: Actor's own/standing island management screen
		Optional<IslandView> actorManageIsland = resolveActorManageIsland(viewer);
		actorManageIsland.ifPresent(island -> builder.pinButton(
			7,
			GuiButton.builder("minecraft:lodestone", "My Island Warps", ChatFormatting.GREEN)
				.lore(
					GuiButton.nonItalic("Manage, edit icons, or mark warps", ChatFormatting.GRAY),
					GuiButton.nonItalic("Warps: " + warpService.getIslandWarps(island.islandId()).size()
						+ "/" + warpService.resolveMaxWarps(server, island), ChatFormatting.YELLOW)
				)
				.onClick(click -> click.openScreen(MANAGE_SCREEN, island.islandId()))
				.build()
		));

		// Header slot 8: Active filter badge / reset
		if (filterIslandId != null || !query.isEmpty()) {
			builder.pinButton(8, GuiButton.builder("minecraft:paper", "Clear Filters", ChatFormatting.RED)
				.glowing(true)
				.lore(
					GuiButton.nonItalic(filterIslandId != null ? "Filtered to single island" : "Search: '" + query + "'", ChatFormatting.YELLOW),
					GuiButton.nonItalic("Click to show all public warps", ChatFormatting.GRAY)
				)
				.onClick(click -> click.openScreen(
					new GuiOpenRequest(SHOWCASE_SCREEN, null, 0, Map.of("category", category, "sort", sort), null)
				))
				.build());
		}

		List<WarpHelper.ShowcaseEntry> entries = warpService.queryShowcaseEntries(server, category, sort, query, filterIslandId);
		for (WarpHelper.ShowcaseEntry entry : entries) {
			builder.addPagedEntry(buildShowcaseWarpButton(cfg, entry, hasLevel));
		}
		return builder.build();
	}

	private GuiButton buildShowcaseWarpButton(WarpConfig cfg, WarpHelper.ShowcaseEntry entry, boolean hasLevel) {
		IslandView island = entry.island();
		IslandWarp warp = entry.warp();
		String title = warp.displayName() + " (" + entry.ownerName() + ")";
		GuiButton.Builder button = warp.iconItemId() != null
			? GuiButton.builder(warp.iconItemId(), title, ChatFormatting.AQUA)
			: GuiButton.playerHead(island.ownerId(), title, ChatFormatting.AQUA);

		List<Component> lore = new ArrayList<>();
		lore.add(GuiButton.nonItalic("Category: " + cfg.categoryDisplayName(warp.categoryId()), ChatFormatting.YELLOW));
		lore.add(GuiButton.nonItalic("Dimension: " + WarpHelper.formatDimensionName(warp.dimensionId()), ChatFormatting.GRAY));
		lore.add(GuiButton.nonItalic("Visits: " + warp.visitCount(), ChatFormatting.GOLD));
		if (hasLevel) {
			lore.add(GuiButton.nonItalic("Island Level: " + entry.islandLevel(), ChatFormatting.GREEN));
		}
		if (!warp.description().isEmpty()) {
			lore.add(GuiButton.nonItalic(warp.description(), ChatFormatting.WHITE));
		}
		lore.add(GuiButton.nonItalic("Click to teleport", ChatFormatting.GREEN));

		return button.lore(lore)
			.onClick(click -> {
				MinecraftServer srv = click.actor().level().getServer();
				IslandOperationResult<Void> result = warpService.requestTeleport(
					srv,
					click.actor(),
					island.islandId(),
					warp.slug()
				);
				click.sendFeedback(result.success(), result.message());
				click.close();
			})
			.build();
	}

	private GuiScreenModel buildManageScreen(GuiBuildContext ctx) {
		ServerPlayer viewer = ctx.viewer();
		MinecraftServer server = viewer.level().getServer();
		Optional<IslandView> maybeIsland = ctx.islandId() != null
			? core.islands().findById(ctx.islandId())
			: resolveActorManageIsland(viewer);

		if (maybeIsland.isEmpty()) {
			return GuiScreenModel.fixed("Manage Warps", 3)
				.pinButton(13, GuiButton.of("minecraft:barrier", "Island Not Found", ChatFormatting.RED))
				.pinButton(22, GuiButton.builder("minecraft:oak_door", "Back", ChatFormatting.YELLOW)
					.onClick(GuiClickContext::goBack)
					.build())
				.build();
		}

		IslandView island = maybeIsland.get();
		if (!warpService.canManageWarps(island, viewer)) {
			return GuiScreenModel.fixed("Manage Warps", 3)
				.pinButton(13, GuiButton.builder("minecraft:barrier", "Access Denied", ChatFormatting.RED)
					.lore(GuiButton.nonItalic("Requires island rank matching manage_warps.", ChatFormatting.GRAY))
					.build())
				.pinButton(22, GuiButton.builder("minecraft:oak_door", "Back", ChatFormatting.YELLOW)
					.onClick(GuiClickContext::goBack)
					.build())
				.build();
		}

		WarpConfig cfg = warpService.config();
		List<IslandWarp> warps = warpService.getIslandWarps(island.islandId());
		int maxWarps = warpService.resolveMaxWarps(server, island);
		String ownerName = WarpHelper.resolvePlayerName(server, island.ownerId());

		GuiScreenModel.Builder builder = GuiScreenModel.paged("Manage Warps: " + ownerName)
			.emptyStateButton(GuiButton.builder("minecraft:compass", "No Warps Yet", ChatFormatting.YELLOW)
				.lore(
					GuiButton.nonItalic("Stand inside your island and click 'Set Warp Here'", ChatFormatting.GRAY),
					GuiButton.nonItalic("or use /is setwarp <name> [category].", ChatFormatting.GRAY)
				)
				.build());

		builder.pinButton(0, GuiButton.builder("minecraft:ender_eye", "Public Warp Showcase", ChatFormatting.AQUA)
			.lore(GuiButton.nonItalic("Browse all islands' public warps", ChatFormatting.GRAY))
			.onClick(click -> click.openScreen(SHOWCASE_SCREEN, null))
			.build());

		builder.pinButton(2, GuiButton.builder("minecraft:beacon", "Set / Move 'home' Here", ChatFormatting.GREEN)
			.lore(GuiButton.nonItalic("Creates or moves default 'home' warp to your feet", ChatFormatting.GRAY))
			.onClick(click -> {
				IslandOperationResult<IslandWarp> res = warpService.createOrUpdateWarp(
					click.actor().level().getServer(),
					click.actor(),
					island.islandId(),
					WarpConfig.DEFAULT_WARP_NAME,
					null
				);
				click.sendFeedback(res.success(), res.message());
				click.refresh();
			})
			.build());

		builder.pinButton(4, GuiButton.playerHead(island.ownerId(), ownerName + "'s Warps", ChatFormatting.GOLD)
			.lore(
				GuiButton.nonItalic("Slots Used: " + warps.size() + " / " + maxWarps, ChatFormatting.YELLOW),
				GuiButton.nonItalic("Click any warp below to edit or delete it.", ChatFormatting.GRAY)
			)
			.build());

		boolean markerActive = warpService.hasActiveMarker(viewer.getUUID());
		builder.pinButton(6, GuiButton.builder("minecraft:end_rod", "Toggle Warp Particles", ChatFormatting.LIGHT_PURPLE)
			.glowing(markerActive)
			.lore(GuiButton.nonItalic("Show particles at all warps on this island", ChatFormatting.GRAY))
			.onClick(click -> {
				IslandOperationResult<Boolean> res = warpService.toggleMarker(
					click.actor().level().getServer(),
					click.actor(),
					island.islandId(),
					null
				);
				click.sendFeedback(res.success(), res.message());
				click.refresh();
			})
			.build());

		for (IslandWarp warp : warps) {
			boolean inBounds = warpService.isWithinIslandBounds(island, warp);
			String title = warp.displayName() + (warp.isDefault() ? " [Default]" : "");
			ChatFormatting color = warp.enabled() && inBounds ? ChatFormatting.AQUA : ChatFormatting.RED;
			GuiButton.Builder item = warp.iconItemId() != null
				? GuiButton.builder(warp.iconItemId(), title, color)
				: GuiButton.playerHead(island.ownerId(), title, color);

			List<Component> lore = new ArrayList<>();
			lore.add(GuiButton.nonItalic(
				"Status: " + (!warp.enabled() ? "Disabled" : (!inBounds ? "Outside Bounds" : "Active")),
				warp.enabled() && inBounds ? ChatFormatting.GREEN : ChatFormatting.RED
			));
			lore.add(GuiButton.nonItalic("Category: " + cfg.categoryDisplayName(warp.categoryId()), ChatFormatting.YELLOW));
			lore.add(GuiButton.nonItalic("Dimension: " + WarpHelper.formatDimensionName(warp.dimensionId()), ChatFormatting.GRAY));
			lore.add(GuiButton.nonItalic("Visits: " + warp.visitCount(), ChatFormatting.GOLD));
			if (!warp.description().isEmpty()) {
				lore.add(GuiButton.nonItalic(warp.description(), ChatFormatting.WHITE));
			}
			lore.add(GuiButton.nonItalic("Click to edit warp settings", ChatFormatting.GREEN));

			builder.addPagedEntry(
				item.lore(lore)
					.glowing(warp.isDefault())
					.onClick(click -> click.openScreen(
						new GuiOpenRequest(EDIT_SCREEN, island.islandId(), 0, Map.of("warp", warp.slug()), null)
					))
					.build()
			);
		}
		return builder.build();
	}

	private GuiScreenModel buildEditScreen(GuiBuildContext ctx) {
		ServerPlayer viewer = ctx.viewer();
		UUID islandId = ctx.islandId();
		String slug = ctx.stateOrDefault("warp", "");
		Optional<IslandView> maybeIsland = islandId == null ? Optional.empty() : core.islands().findById(islandId);
		Optional<IslandWarp> maybeWarp = islandId == null ? Optional.empty() : warpService.findWarp(islandId, slug);

		if (maybeIsland.isEmpty() || maybeWarp.isEmpty() || !warpService.canManageWarps(maybeIsland.get(), viewer)) {
			return GuiScreenModel.fixed("Edit Warp", 3)
				.pinButton(13, GuiButton.of("minecraft:barrier", "Warp Unavailable", ChatFormatting.RED))
				.pinButton(22, GuiButton.builder("minecraft:oak_door", "Back", ChatFormatting.YELLOW)
					.onClick(GuiClickContext::goBack)
					.build())
				.build();
		}

		IslandView island = maybeIsland.get();
		IslandWarp warp = maybeWarp.get();
		WarpConfig cfg = warpService.config();
		MinecraftServer server = viewer.level().getServer();
		boolean inBounds = warpService.isWithinIslandBounds(island, warp);

		GuiScreenModel.Builder builder = GuiScreenModel.fixed("Edit Warp: " + warp.displayName(), 3);

		// Slot 4: Overview preview
		GuiButton.Builder preview = warp.iconItemId() != null
			? GuiButton.builder(warp.iconItemId(), warp.displayName(), ChatFormatting.GOLD)
			: GuiButton.playerHead(island.ownerId(), warp.displayName(), ChatFormatting.GOLD);
		builder.pinButton(4, preview
			.lore(
				GuiButton.nonItalic("Category: " + cfg.categoryDisplayName(warp.categoryId()), ChatFormatting.YELLOW),
				GuiButton.nonItalic("Dimension: " + WarpHelper.formatDimensionName(warp.dimensionId()), ChatFormatting.GRAY),
				GuiButton.nonItalic(
					String.format("Pos: %.1f, %.1f, %.1f", warp.worldX(island.centerX()), warp.y(), warp.worldZ(island.centerZ())),
					ChatFormatting.GRAY
				),
				GuiButton.nonItalic("Visits: " + warp.visitCount() + (inBounds ? "" : " (OUT OF BOUNDS)"), ChatFormatting.GOLD)
			)
			.build());

		// Slot 10: Teleport
		builder.pinButton(10, GuiButton.builder("minecraft:ender_pearl", "Teleport Here", ChatFormatting.GREEN)
			.lore(GuiButton.nonItalic("Teleport to this warp now", ChatFormatting.GRAY))
			.onClick(click -> {
				IslandOperationResult<Void> res = warpService.requestTeleport(
					click.actor().level().getServer(),
					click.actor(),
					island.islandId(),
					warp.slug()
				);
				click.sendFeedback(res.success(), res.message());
				click.close();
			})
			.build());

		// Slot 11: Particle Marker
		builder.pinButton(11, GuiButton.builder("minecraft:end_rod", "Show Particle Marker", ChatFormatting.LIGHT_PURPLE)
			.lore(GuiButton.nonItalic("Highlight this warp coordinate with particles", ChatFormatting.GRAY))
			.onClick(click -> {
				IslandOperationResult<Boolean> res = warpService.toggleMarker(
					click.actor().level().getServer(),
					click.actor(),
					island.islandId(),
					warp.slug()
				);
				click.sendFeedback(res.success(), res.message());
				click.refresh();
			})
			.build());

		// Slot 12: Toggle Enabled
		builder.pinButton(12, GuiButton.builder(
				warp.enabled() ? "minecraft:lime_dye" : "minecraft:gray_dye",
				warp.enabled() ? "Status: Enabled" : "Status: Disabled",
				warp.enabled() ? ChatFormatting.GREEN : ChatFormatting.RED
			)
			.lore(GuiButton.nonItalic("Click to enable or disable public access", ChatFormatting.GRAY))
			.onClick(click -> {
				IslandOperationResult<IslandWarp> res = warpService.toggleEnabled(
					island.islandId(),
					click.actor(),
					warp.slug(),
					null,
					false
				);
				click.sendFeedback(res.success(), res.message());
				click.refresh();
			})
			.build());

		// Slot 13: Move Warp to Current Position
		builder.pinButton(13, GuiButton.builder("minecraft:compass", "Move to Your Position", ChatFormatting.AQUA)
			.lore(GuiButton.nonItalic("Updates warp coordinates & look direction", ChatFormatting.GRAY))
			.onClick(click -> {
				IslandOperationResult<IslandWarp> res = warpService.moveWarpHere(
					island.islandId(),
					click.actor(),
					warp.slug()
				);
				click.sendFeedback(res.success(), res.message());
				click.refresh();
			})
			.build());

		// Slot 14: Cycle Category
		String nextCat = nextCategoryId(cfg.categories, warp.categoryId());
		WarpConfig.CategorySpec currentCatSpec = cfg.findCategory(warp.categoryId());
		String catIcon = currentCatSpec != null ? currentCatSpec.iconItemId : "minecraft:oak_sign";
		builder.pinButton(14, GuiButton.builder(catIcon, "Category: " + cfg.categoryDisplayName(warp.categoryId()), ChatFormatting.YELLOW)
			.lore(GuiButton.nonItalic("Click to change to: " + cfg.categoryDisplayName(nextCat), ChatFormatting.GRAY))
			.onClick(click -> {
				IslandOperationResult<IslandWarp> res = warpService.editCategory(
					island.islandId(),
					click.actor(),
					warp.slug(),
					nextCat
				);
				click.sendFeedback(res.success(), res.message());
				click.refresh();
			})
			.build());

		// Slot 15: Icon Picker (Left = main hand item, Right = reset to owner player head)
		String currentIconLabel = warp.iconItemId() == null ? "Owner Player Head" : warp.iconItemId();
		builder.pinButton(15, GuiButton.builder("minecraft:item_frame", "Icon: " + currentIconLabel, ChatFormatting.AQUA)
			.lore(
				GuiButton.nonItalic("Left-Click: Set to held item", ChatFormatting.GREEN),
				GuiButton.nonItalic("Right-Click: Reset to owner head", ChatFormatting.YELLOW)
			)
			.onClick(click -> {
				final String targetIcon;
				if (click.clickAction() == GuiClickAction.RIGHT || click.clickAction() == GuiClickAction.SHIFT_RIGHT) {
					targetIcon = "head";
				} else {
					ItemStack held = click.actor().getMainHandItem();
					if (held.isEmpty()) {
						click.sendFeedback(false, "Hold an item in your main hand first (or right-click to reset).");
						return;
					}
					targetIcon = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
				}
				IslandOperationResult<IslandWarp> res = warpService.editIcon(
					island.islandId(),
					click.actor(),
					warp.slug(),
					targetIcon
				);
				click.sendFeedback(res.success(), res.message());
				click.refresh();
			})
			.build());

		// Slot 16: Default Warp Toggle
		builder.pinButton(16, GuiButton.builder("minecraft:nether_star", "Set Default Warp", ChatFormatting.GOLD)
			.glowing(warp.isDefault())
			.lore(GuiButton.nonItalic(
				warp.isDefault() ? "Already the default /is warp target" : "Click to make default for /is warp <player>",
				ChatFormatting.GRAY
			))
			.onClick(click -> {
				IslandOperationResult<IslandWarp> res = warpService.setDefaultWarp(
					island.islandId(),
					click.actor(),
					warp.slug()
				);
				click.sendFeedback(res.success(), res.message());
				click.refresh();
			})
			.build());

		// Slot 22: Back
		builder.pinButton(22, GuiButton.builder("minecraft:oak_door", "Back", ChatFormatting.YELLOW)
			.onClick(GuiClickContext::goBack)
			.build());

		// Slot 26: Delete with 2-click confirmation
		builder.pinButton(26, GuiButton.builder("minecraft:tnt", "Delete Warp", ChatFormatting.RED)
			.confirm("delete:" + warp.slug(), "Click again within 10s to permanently delete")
			.onClick(click -> {
				IslandOperationResult<Void> res = warpService.deleteWarp(
					island.islandId(),
					click.actor(),
					warp.slug(),
					false
				);
				click.sendFeedback(res.success(), res.message());
				if (res.success()) {
					click.goBack();
				}
			})
			.build());

		return builder.build();
	}

	private void executeActionShowcase(ServerPlayer actor) {
		if (!core.guis().openScreen(actor, SHOWCASE_SCREEN, null)) {
			commands.sendChatShowcase(actor.createCommandSourceStack(), 1, "all", "recent", null, null, null);
		}
	}

	private void executeActionManage(ServerPlayer actor, UUID islandId) {
		Optional<IslandView> maybeIsland = core.islands().findById(islandId);
		if (maybeIsland.isEmpty()) {
			actor.sendSystemMessage(Component.literal("[Ogsky Warps] Island does not exist.").withStyle(ChatFormatting.RED));
			return;
		}
		if (!warpService.canManageWarps(maybeIsland.get(), actor)) {
			actor.sendSystemMessage(Component.literal("[Ogsky Warps] Island protection (manage_warps) prevents managing warps.")
				.withStyle(ChatFormatting.RED));
			return;
		}
		if (!core.guis().openScreen(actor, MANAGE_SCREEN, islandId)) {
			commands.sendChatManageSummary(actor.createCommandSourceStack(), maybeIsland.get());
		}
	}

	private Optional<IslandView> resolveActorManageIsland(ServerPlayer viewer) {
		Optional<IslandView> standing = core.islands().findAt(
			viewer.level().dimension(),
			viewer.getBlockX(),
			viewer.getBlockZ()
		);
		if (standing.isPresent() && (standing.get().roleOf(viewer.getUUID()) > IslandRole.VISITOR
			|| warpService.canManageWarps(standing.get(), viewer))) {
			return standing;
		}
		return core.islands().findPrimaryIsland(viewer.getUUID());
	}

	private String resolveShowcaseTitle(
		MinecraftServer server,
		WarpConfig cfg,
		String category,
		String query,
		@Nullable UUID filterIslandId
	) {
		if (filterIslandId != null) {
			String ownerName = core.islands().findById(filterIslandId)
				.map(island -> WarpHelper.resolvePlayerName(server, island.ownerId()))
				.orElseGet(() -> filterIslandId.toString().substring(0, 8));
			return "Warps: " + ownerName;
		}
		if (!query.isEmpty()) {
			return "Warps (" + query + ")";
		}
		if (!"all".equals(category)) {
			return "Warps: " + cfg.categoryDisplayName(category);
		}
		return "Island Warps";
	}

	private static String nextSortMode(String current, boolean hasLevel) {
		return switch (current) {
			case "recent" -> "popular";
			case "popular" -> hasLevel ? "level" : "alpha";
			case "level" -> "alpha";
			default -> "recent";
		};
	}

	private static String sortLabel(String sort) {
		return switch (sort) {
			case "popular" -> "Sort: Popularity (Visits)";
			case "level" -> "Sort: Island Level";
			case "alpha" -> "Sort: Alphabetical (A-Z)";
			default -> "Sort: Most Recent";
		};
	}

	private static String nextCategoryId(List<WarpConfig.CategorySpec> categories, String currentId) {
		for (int i = 0; i < categories.size(); i++) {
			if (categories.get(i).id.equals(currentId)) {
				return categories.get((i + 1) % categories.size()).id;
			}
		}
		return categories.getFirst().id;
	}

	private static String nextCategoryAfter(List<WarpConfig.CategorySpec> categories, String currentId) {
		if ("all".equals(currentId)) {
			return categories.size() > 5 ? categories.get(5).id : categories.getFirst().id;
		}
		for (int i = 0; i < categories.size(); i++) {
			if (categories.get(i).id.equals(currentId)) {
				if (i + 1 >= categories.size()) {
					return "all";
				}
				return categories.get(i + 1).id;
			}
		}
		return "all";
	}
}
