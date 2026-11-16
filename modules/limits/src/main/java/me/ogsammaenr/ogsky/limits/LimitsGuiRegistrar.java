package me.ogsammaenr.ogsky.limits;

import me.ogsammaenr.ogsky.core.api.OgskyCoreApi;
import me.ogsammaenr.ogsky.core.api.action.IslandActionDescriptor;
import me.ogsammaenr.ogsky.core.api.gui.GuiBuildContext;
import me.ogsammaenr.ogsky.core.api.gui.GuiButton;
import me.ogsammaenr.ogsky.core.api.gui.GuiClickContext;
import me.ogsammaenr.ogsky.core.api.gui.GuiScreenModel;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Registers {@code ogsky-limits} panel summary lines, paged GUI screen, and panel action entry
 * via UI-agnostic {@code core.guis()} and {@code core.actions()}.
 */
final class LimitsGuiRegistrar {
	static final Identifier SUMMARY_CONTRIBUTOR_ID = Identifier.fromNamespaceAndPath("ogsky_limits", "summary");
	static final Identifier LIMITS_SCREEN = Identifier.fromNamespaceAndPath("ogsky_limits", "overview");
	static final Identifier ACTION_ISLAND_LIMITS = Identifier.fromNamespaceAndPath("ogsky_limits", "island_limits");

	private final OgskyCoreApi core;
	private final IslandLimitService limitService;
	private final LimitsCommands commands;

	LimitsGuiRegistrar(OgskyCoreApi core, IslandLimitService limitService, LimitsCommands commands) {
		this.core = core;
		this.limitService = limitService;
		this.commands = commands;
	}

	void registerAll() {
		// 1. Slot 8 Island Summary line on /is panel (gated by ogsky_limits:view_limits)
		core.guis().registerSummaryContributor(SUMMARY_CONTRIBUTOR_ID, 30, ctx -> {
			Optional<IslandView> maybeIsland = core.islands().findById(ctx.islandId());
			if (maybeIsland.isEmpty()) {
				return List.of();
			}
			IslandView island = maybeIsland.get();
			if (!limitService.canViewLimits(island, ctx.viewerUuid(), ctx.viewerAdminBypass())) {
				return List.of();
			}
			List<IslandLimitService.RuleStatus> statuses = limitService.getRuleStatusesSnapshot(ctx.islandId());
			int maxed = 0;
			int active = 0;
			for (IslandLimitService.RuleStatus s : statuses) {
				if (s.used() > 0) {
					active++;
				}
				if (s.used() >= s.effectiveLimit()) {
					maxed++;
				}
			}
			if (maxed > 0) {
				return List.of(
					GuiButton.nonItalic(
						"Limits: " + maxed + " maxed (" + active + "/" + statuses.size() + " active)",
						ChatFormatting.RED
					)
				);
			}
			return List.of(
				GuiButton.nonItalic(
					"Limits: " + active + "/" + statuses.size() + " rules active (/is limits)",
					ChatFormatting.AQUA
				)
			);
		});

		// 2. /is limits Paged/Filterable/Sortable Screen
		core.guis().registerScreen(LIMITS_SCREEN, this::buildLimitsScreen);

		// 3. Panel Actions Tab Entry
		core.actions().register(new IslandActionDescriptor(
			ACTION_ISLAND_LIMITS,
			"Island Limits",
			"Inspect island block & entity usage against configured caps.",
			"minecraft:hopper",
			30,
			false,
			ctx -> executeActionLimits(ctx.actor(), ctx.islandId())
		));
	}

	private GuiScreenModel buildLimitsScreen(GuiBuildContext ctx) {
		ServerPlayer viewer = ctx.viewer();
		MinecraftServer server = viewer.level().getServer();
		UUID islandId = ctx.islandId();
		Optional<IslandView> maybeIsland = islandId != null
			? core.islands().findById(islandId)
			: core.islands().findPrimaryIsland(viewer.getUUID());

		if (maybeIsland.isEmpty()) {
			return GuiScreenModel.fixed("Island Limits", 3)
				.pinButton(13, GuiButton.of("minecraft:barrier", "Island Not Found", ChatFormatting.RED))
				.pinButton(22, GuiButton.builder("minecraft:oak_door", "Back", ChatFormatting.YELLOW)
					.onClick(GuiClickContext::goBack)
					.build())
				.build();
		}

		IslandView island = maybeIsland.get();
		if (!limitService.canViewLimits(island, viewer)) {
			return GuiScreenModel.fixed("Island Limits", 3)
				.pinButton(13, GuiButton.builder("minecraft:barrier", "Access Denied", ChatFormatting.RED)
					.lore(GuiButton.nonItalic("Viewing limits requires higher island access (view_limits).", ChatFormatting.GRAY))
					.build())
				.pinButton(22, GuiButton.builder("minecraft:oak_door", "Back", ChatFormatting.YELLOW)
					.onClick(GuiClickContext::goBack)
					.build())
				.build();
		}

		String filter = LimitHelper.normalizeFilter(ctx.stateOrDefault("filter", "all"));
		String sort = LimitHelper.normalizeSort(ctx.stateOrDefault("sort", "usage"));
		String query = ctx.stateOrDefault("query", "").trim();

		List<IslandLimitService.RuleStatus> allStatuses = limitService.getRuleStatuses(server, island.islandId());
		List<IslandLimitService.RuleStatus> entries = LimitHelper.filterAndSortStatuses(allStatuses, filter, query, sort);

		final String nextFilter;
		final String filterLabel;
		final String filterIcon;
		switch (filter) {
			case "blocks" -> {
				filterLabel = "Filter: Blocks Only";
				filterIcon = "minecraft:bricks";
				nextFilter = "entities";
			}
			case "entities" -> {
				filterLabel = "Filter: Entities Only";
				filterIcon = "minecraft:armadillo_scute";
				nextFilter = "all";
			}
			default -> {
				filterLabel = "Filter: All Limits";
				filterIcon = "minecraft:chest";
				nextFilter = "blocks";
			}
		}

		final String nextSort;
		final String sortLabel;
		switch (sort) {
			case "name" -> {
				sortLabel = "Sort: Alphabetical (A-Z)";
				nextSort = "kind";
			}
			case "kind" -> {
				sortLabel = "Sort: Category";
				nextSort = "usage";
			}
			default -> {
				sortLabel = "Sort: Highest Usage %";
				nextSort = "name";
			}
		}

		String ownerName = LimitHelper.resolvePlayerName(server, island.ownerId());
		String title = query.isEmpty()
			? "Limits: " + ownerName
			: "Limits (" + query + ")";
		GuiScreenModel.Builder builder = GuiScreenModel.paged(title)
			.contextButton(GuiButton.builder("minecraft:comparator", sortLabel, ChatFormatting.GOLD)
				.lore(GuiButton.nonItalic("Click to cycle sort order", ChatFormatting.GRAY))
				.onClick(click -> click.refreshWithState("sort", nextSort))
				.build())
			.emptyStateButton(GuiButton.builder("minecraft:barrier", "No Matching Limits", ChatFormatting.RED)
				.lore(GuiButton.nonItalic(
					query.isEmpty()
						? "No rules match the current filter."
						: "No rules match search '" + query + "'.",
					ChatFormatting.GRAY
				))
				.build());

		// Header slot 2: Block/Entity filter toggle
		builder.pinButton(2, GuiButton.builder(filterIcon, filterLabel, ChatFormatting.AQUA)
			.lore(GuiButton.nonItalic("Click to cycle: All / Blocks / Entities", ChatFormatting.GRAY))
			.onClick(click -> click.refreshWithState("filter", nextFilter))
			.build());

		// Header slot 4: Island Limit Overview & optional search clear
		boolean scanning = limitService.isScanInProgress(island.islandId());
		boolean synced = limitService.isSynchronized(island.islandId());
		int maxedCount = 0;
		for (IslandLimitService.RuleStatus s : allStatuses) {
			if (s.used() >= s.effectiveLimit()) {
				maxedCount++;
			}
		}
		List<Component> overviewLore = new ArrayList<>();
		overviewLore.add(GuiButton.nonItalic("Island Owner: " + ownerName, ChatFormatting.WHITE));
		overviewLore.add(GuiButton.nonItalic("Tracked Rules: " + allStatuses.size() + " (" + maxedCount + " maxed)", ChatFormatting.YELLOW));
		overviewLore.add(GuiButton.nonItalic(
			"Sync Status: " + (scanning ? "Scanning now..." : (synced ? "Synchronized" : "Pending initial scan")),
			scanning ? ChatFormatting.YELLOW : (synced ? ChatFormatting.GREEN : ChatFormatting.GRAY)
		));
		if (!query.isEmpty()) {
			overviewLore.add(GuiButton.nonItalic("Search Filter: '" + query + "' (Click to clear)", ChatFormatting.AQUA));
		}
		GuiButton.Builder overviewBtn = GuiButton.playerHead(island.ownerId(), ownerName + "'s Limits", ChatFormatting.GOLD)
			.lore(overviewLore);
		if (!query.isEmpty()) {
			overviewBtn.glowing(true).onClick(click -> click.refreshWithState("query", ""));
		}
		builder.pinButton(4, overviewBtn.build());

		// Header slot 6: Rescan trigger (authorized island admins or admins with bypass/ogsky.admin.limits)
		boolean canRescan = island.roleOf(viewer.getUUID()) >= IslandRole.ADMIN
			|| core.permissions().isBypassActive(viewer)
			|| core.permissions().hasPermission(viewer, "ogsky.admin.limits", 2);
		if (canRescan) {
			builder.pinButton(6, GuiButton.builder(
					scanning ? "minecraft:clock" : "minecraft:recovery_compass",
					scanning ? "Scanning in Progress..." : "Rescan Island Limits",
					scanning ? ChatFormatting.YELLOW : ChatFormatting.GREEN
				)
				.lore(GuiButton.nonItalic(
					scanning
						? "Counting live and unloaded chunks..."
						: "Click to re-synchronize counters from chunks",
					ChatFormatting.GRAY
				))
				.glowing(scanning)
				.onClick(click -> triggerRescanFromGui(click, island))
				.build());
		}

		for (IslandLimitService.RuleStatus status : entries) {
			LimitRuleTable.LimitRule rule = status.rule();
			ChatFormatting color = LimitHelper.statusColor(status);
			List<Component> lore = new ArrayList<>();
			String kindLabel = rule.kind() == LimitRuleTable.RuleKind.BLOCK ? "Block Limit" : "Entity Limit";
			if (rule.groupRule()) {
				kindLabel += rule.key().startsWith("#") ? " (Tag)" : " (Group)";
			}
			lore.add(GuiButton.nonItalic("Category: " + kindLabel, ChatFormatting.GRAY));
			lore.add(GuiButton.nonItalic(
				"Used: " + LimitHelper.formatNumber(status.used())
					+ " / " + LimitHelper.formatNumber(status.effectiveLimit())
					+ " (" + LimitHelper.formatNumber(status.remaining()) + " left)",
				color
			));
			lore.add(GuiButton.nonItalic(LimitHelper.usageBar(status.used(), status.effectiveLimit()), color));
			lore.add(GuiButton.nonItalic("Rule: " + rule.key(), ChatFormatting.DARK_GRAY));
			if (rule.sourceEntries().size() > 1) {
				int previewCount = Math.min(3, rule.sourceEntries().size());
				StringBuilder preview = new StringBuilder();
				for (int i = 0; i < previewCount; i++) {
					if (i > 0) {
						preview.append(", ");
					}
					preview.append(rule.sourceEntries().get(i));
				}
				if (rule.sourceEntries().size() > previewCount) {
					preview.append(" (+").append(rule.sourceEntries().size() - previewCount).append(" more)");
				}
				lore.add(GuiButton.nonItalic("Includes: " + preview, ChatFormatting.DARK_GRAY));
			}
			if (status.scanning()) {
				lore.add(GuiButton.nonItalic("Syncing chunk counters...", ChatFormatting.YELLOW));
			}

			builder.addPagedEntry(
				GuiButton.builder(rule.iconItemId(), rule.displayName(), color)
					.lore(lore)
					.glowing(status.used() >= status.effectiveLimit() && status.effectiveLimit() > 0)
					.build()
			);
		}
		return builder.build();
	}

	private void triggerRescanFromGui(GuiClickContext click, IslandView island) {
		ServerPlayer actor = click.actor();
		MinecraftServer server = actor.level().getServer();
		click.sendFeedback(true, "Rescanning island blocks and entities...");
		limitService.requestRescan(server, island.islandId(), true).whenComplete((res, err) -> {
			if (!actor.hasDisconnected()) {
				if (err != null || res == null || res.aborted()) {
					actor.sendSystemMessage(Component.literal("[Ogsky] Limit rescan aborted.").withStyle(ChatFormatting.RED));
				} else {
					actor.sendSystemMessage(
						Component.literal("[Ogsky] Island limits synchronized (" + res.totalChunksScanned() + " chunks).")
							.withStyle(ChatFormatting.GREEN)
					);
				}
			}
		});
		click.refresh();
	}

	private void executeActionLimits(ServerPlayer actor, UUID islandId) {
		Optional<IslandView> maybeIsland = core.islands().findById(islandId);
		if (maybeIsland.isEmpty()) {
			actor.sendSystemMessage(Component.literal("[Ogsky] Island does not exist.").withStyle(ChatFormatting.RED));
			return;
		}
		IslandView island = maybeIsland.get();
		if (!limitService.canViewLimits(island, actor)) {
			actor.sendSystemMessage(Component.literal("[Ogsky] You do not have permission to view limits on this island.")
				.withStyle(ChatFormatting.RED));
			return;
		}
		if (!core.guis().openScreen(actor, LIMITS_SCREEN, islandId)) {
			commands.sendChatLimits(actor.createCommandSourceStack(), island, 1, "all", null);
		}
	}
}
