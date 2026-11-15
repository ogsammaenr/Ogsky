package me.ogsammaenr.ogsky.level;

import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.OgskyCoreApi;
import me.ogsammaenr.ogsky.core.api.action.IslandActionDescriptor;
import me.ogsammaenr.ogsky.core.api.gui.GuiBuildContext;
import me.ogsammaenr.ogsky.core.api.gui.GuiButton;
import me.ogsammaenr.ogsky.core.api.gui.GuiClickContext;
import me.ogsammaenr.ogsky.core.api.gui.GuiScreenModel;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import me.ogsammaenr.ogsky.core.api.level.BlockPointBreakdown;
import me.ogsammaenr.ogsky.core.api.level.IslandLevelSnapshot;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Registers {@code ogsky-level} summary lines, leaderboard/detail screens via {@code core.guis()},
 * and panel action entries via {@code core.actions()}.
 */
final class LevelGuiRegistrar {
	static final Identifier SUMMARY_CONTRIBUTOR_ID = Identifier.fromNamespaceAndPath("ogsky_level", "summary");
	static final Identifier TOP_SCREEN = Identifier.fromNamespaceAndPath("ogsky_level", "top");
	static final Identifier DETAIL_SCREEN = Identifier.fromNamespaceAndPath("ogsky_level", "detail");
	static final Identifier VALUES_SCREEN = Identifier.fromNamespaceAndPath("ogsky_level", "values");
	static final Identifier ACTION_ISLAND_LEVEL = OgskyCore.id("island_level");
	static final Identifier ACTION_ISLAND_TOP = Identifier.fromNamespaceAndPath("ogsky_level", "island_top");
	static final Identifier ACTION_BLOCK_VALUES = Identifier.fromNamespaceAndPath("ogsky_level", "block_values");

	private static final int[] DETAIL_SLOTS = {
		10, 11, 12, 13, 14, 15, 16,
		19, 20, 21, 22, 23, 24, 25
	};
	private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
		.withZone(ZoneOffset.UTC);

	private final OgskyCoreApi core;
	private final IslandLevelService levelService;
	private final LevelCommands commands;

	LevelGuiRegistrar(OgskyCoreApi core, IslandLevelService levelService, LevelCommands commands) {
		this.core = core;
		this.levelService = levelService;
		this.commands = commands;
	}

	void registerAll() {
		// 1. Slot 8 Island Summary line on /is panel
		core.guis().registerSummaryContributor(SUMMARY_CONTRIBUTOR_ID, 20, ctx -> {
			Optional<IslandLevelSnapshot> maybe = levelService.findSnapshot(ctx.islandId());
			if (maybe.isEmpty()) {
				return List.of(
					GuiButton.nonItalic("Island Level: Unranked (/is level)", ChatFormatting.YELLOW)
				);
			}
			IslandLevelSnapshot snap = maybe.get();
			OptionalInt rank = levelService.rankOf(ctx.islandId());
			String rankPart = rank.isPresent() ? " (#" + rank.getAsInt() + ")" : "";
			return List.of(
				GuiButton.nonItalic(
					"Island Level: " + LevelHelper.formatNumber(snap.levelValue())
						+ " [" + LevelHelper.formatNumber(snap.blockPoints()) + " pts]" + rankPart,
					ChatFormatting.GOLD
				)
			);
		});

		// 2. /is top Paged Leaderboard Screen
		core.guis().registerScreen(TOP_SCREEN, this::buildTopScreen);

		// 3. Island Block Breakdown Detail Screen
		core.guis().registerScreen(DETAIL_SCREEN, this::buildDetailScreen);

		// 4. /is values Paged Block Worth Screen
		core.guis().registerScreen(VALUES_SCREEN, this::buildValuesScreen);

		// 5. Panel Actions Tab Entries
		core.actions().register(new IslandActionDescriptor(
			ACTION_ISLAND_LEVEL,
			"Island Level",
			"Recalculate your island level or inspect top contributing blocks.",
			"minecraft:experience_bottle",
			20,
			false,
			ctx -> executeActionLevel(ctx.actor(), ctx.islandId())
		));

		core.actions().register(new IslandActionDescriptor(
			ACTION_ISLAND_TOP,
			"Island Top",
			"Browse the ranked Skyblock island leaderboard.",
			"minecraft:gold_block",
			22,
			false,
			ctx -> {
				if (!core.guis().openScreen(ctx.actor(), TOP_SCREEN, ctx.islandId())) {
					commands.sendChatLeaderboard(ctx.actor().createCommandSourceStack(), 1);
				}
			}
		));

		core.actions().register(new IslandActionDescriptor(
			ACTION_BLOCK_VALUES,
			"Block Values",
			"Browse point values and scoring caps for island blocks.",
			"minecraft:diamond_block",
			24,
			false,
			ctx -> {
				if (!core.guis().openScreen(ctx.actor(), VALUES_SCREEN, ctx.islandId())) {
					commands.sendChatValues(ctx.actor().createCommandSourceStack(), 1, null);
				}
			}
		));
	}

	private GuiScreenModel buildTopScreen(GuiBuildContext ctx) {
		String sort = ctx.stateOrDefault("sort", "level");
		MinecraftServer server = ctx.viewer().level().getServer();
		boolean sortRecent = "recent".equals(sort);

		List<IslandLevelSnapshot> entries = new ArrayList<>(
			levelService.topIslands(levelService.config().top.maxCachedEntries)
		);
		if (sortRecent) {
			entries.sort(
				Comparator.comparing(IslandLevelSnapshot::updatedAt).reversed()
					.thenComparing((a, b) -> Long.compare(b.levelValue(), a.levelValue()))
			);
		}

		GuiScreenModel.Builder builder = GuiScreenModel.paged(
				sortRecent ? "Island Top (Recent)" : "Island Top (Level)"
			)
			.contextButton(GuiButton.builder(
					"minecraft:hopper",
					sortRecent ? "Sort: Recently Updated" : "Sort: Level & Points",
					ChatFormatting.GOLD
				)
				.lore(GuiButton.nonItalic("Click to cycle leaderboard order", ChatFormatting.GRAY))
				.onClick(click -> {
					String next = sortRecent ? "level" : "recent";
					click.refreshWithState("sort", next);
				})
				.build())
			.emptyStateButton(GuiButton.builder(
					"minecraft:barrier",
					"No Ranked Islands Yet",
					ChatFormatting.RED
				)
				.lore(GuiButton.nonItalic("Run /is level on an island to appear here.", ChatFormatting.GRAY))
				.build());

		// Pin the viewer's own primary island card in the top header row (slot 4) when available.
		Optional<IslandView> viewerIsland = core.islands().findPrimaryIsland(ctx.viewer().getUUID());
		if (viewerIsland.isPresent()) {
			IslandView own = viewerIsland.get();
			Optional<IslandLevelSnapshot> ownSnap = levelService.findSnapshot(own.islandId());
			OptionalInt ownRank = levelService.rankOf(own.islandId());
			if (ownSnap.isPresent()) {
				IslandLevelSnapshot snap = ownSnap.get();
				String rankStr = ownRank.isPresent() ? "#" + ownRank.getAsInt() : "Unranked";
				builder.pinButton(4, GuiButton.builder("minecraft:nether_star", "Your Island (" + rankStr + ")", ChatFormatting.AQUA)
					.lore(
						GuiButton.nonItalic("Level: " + LevelHelper.formatNumber(snap.levelValue()), ChatFormatting.YELLOW),
						GuiButton.nonItalic("Points: " + LevelHelper.formatNumber(snap.blockPoints()), ChatFormatting.WHITE),
						GuiButton.nonItalic("Click to inspect block breakdown", ChatFormatting.GRAY)
					)
					.glowing(true)
					.onClick(click -> click.openScreen(DETAIL_SCREEN, own.islandId()))
					.build());
			} else {
				builder.pinButton(4, GuiButton.builder("minecraft:experience_bottle", "Your Island (Unranked)", ChatFormatting.YELLOW)
					.lore(GuiButton.nonItalic("Click to inspect & calculate level", ChatFormatting.GRAY))
					.onClick(click -> click.openScreen(DETAIL_SCREEN, own.islandId()))
					.build());
			}
		}

		for (IslandLevelSnapshot entry : entries) {
			int rank = levelService.rankOf(entry.islandId()).orElse(0);
			String ownerName = LevelHelper.resolvePlayerName(server, entry.ownerId());
			String title = (rank > 0 ? "#" + rank + " " : "") + ownerName;
			builder.addPagedEntry(
				GuiButton.playerHead(entry.ownerId(), title, rank == 1 ? ChatFormatting.GOLD : ChatFormatting.AQUA)
					.lore(
						GuiButton.nonItalic("Island Level: " + LevelHelper.formatNumber(entry.levelValue()), ChatFormatting.YELLOW),
						GuiButton.nonItalic("Total Points: " + LevelHelper.formatNumber(entry.blockPoints()), ChatFormatting.WHITE),
						GuiButton.nonItalic("Next Level: " + LevelHelper.formatNumber(entry.pointsToNextLevel()) + " pts", ChatFormatting.GRAY),
						GuiButton.nonItalic("Click to view block breakdown", ChatFormatting.DARK_GRAY)
					)
					.onClick(click -> click.openScreen(DETAIL_SCREEN, entry.islandId()))
					.build()
			);
		}
		return builder.build();
	}

	private GuiScreenModel buildDetailScreen(GuiBuildContext ctx) {
		MinecraftServer server = ctx.viewer().level().getServer();
		UUID islandId = ctx.islandId();
		Optional<IslandView> maybeIsland = islandId != null ? core.islands().findById(islandId) : Optional.empty();
		if (maybeIsland.isEmpty()) {
			return GuiScreenModel.fixed("Island Level Details", 3)
				.pinButton(13, GuiButton.of("minecraft:barrier", "Island Not Found", ChatFormatting.RED))
				.pinButton(22, GuiButton.builder("minecraft:oak_door", "Back", ChatFormatting.YELLOW)
					.onClick(GuiClickContext::goBack)
					.build())
				.build();
		}

		IslandView island = maybeIsland.get();
		String ownerName = LevelHelper.resolvePlayerName(server, island.ownerId());
		Optional<IslandLevelSnapshot> maybeSnap = levelService.findSnapshot(islandId);
		OptionalInt maybeRank = levelService.rankOf(islandId);
		boolean scanning = levelService.isScanInProgress(islandId);
		boolean canRecalculate = island.roleOf(ctx.viewer().getUUID()) > IslandRole.VISITOR
			|| core.permissions().isBypassActive(ctx.viewer());

		GuiScreenModel.Builder builder = GuiScreenModel.fixed("Level Breakdown: " + ownerName, 4);

		// Header slot 4: Overview player head
		List<Component> overviewLore = new ArrayList<>();
		if (maybeSnap.isPresent()) {
			IslandLevelSnapshot snap = maybeSnap.get();
			overviewLore.add(GuiButton.nonItalic("Level: " + LevelHelper.formatNumber(snap.levelValue()), ChatFormatting.YELLOW));
			overviewLore.add(GuiButton.nonItalic("Block Points: " + LevelHelper.formatNumber(snap.blockPoints()), ChatFormatting.WHITE));
			overviewLore.add(GuiButton.nonItalic(
				"Next Level: " + LevelHelper.formatNumber(snap.pointsToNextLevel()) + " pts ("
					+ LevelHelper.formatNumber(snap.pointsPerLevel()) + "/lvl)",
				ChatFormatting.GRAY
			));
			overviewLore.add(GuiButton.nonItalic(
				"Rank: " + (maybeRank.isPresent() ? "#" + maybeRank.getAsInt() : "Unranked"),
				ChatFormatting.AQUA
			));
			overviewLore.add(GuiButton.nonItalic("Updated: " + TIME_FMT.format(snap.updatedAt()), ChatFormatting.DARK_GRAY));
		} else {
			overviewLore.add(GuiButton.nonItalic("Status: Not scanned yet", ChatFormatting.GRAY));
		}
		overviewLore.add(GuiButton.nonItalic(
			"Dimensions: OW=" + yesNo(island.isOverworldReady())
				+ " Nether=" + yesNo(island.isNetherReady())
				+ " End=" + yesNo(island.isEndReady()),
			ChatFormatting.DARK_GRAY
		));
		builder.pinButton(4, GuiButton.playerHead(island.ownerId(), ownerName + "'s Island", ChatFormatting.GOLD)
			.lore(overviewLore.toArray(Component[]::new))
			.build());

		// Header slot 8: Recalculate button for island members & bypass admins
		if (canRecalculate) {
			builder.pinButton(8, GuiButton.builder(
					scanning ? "minecraft:clock" : "minecraft:experience_bottle",
					scanning ? "Scanning in Progress..." : "Recalculate Island Level",
					scanning ? ChatFormatting.YELLOW : ChatFormatting.GREEN
				)
				.lore(GuiButton.nonItalic(
					scanning
						? "Blocks are currently being scanned..."
						: "Click to scan island blocks now",
					ChatFormatting.GRAY
				))
				.glowing(scanning)
				.onClick(click -> triggerScanFromGui(click, island))
				.build());
		}

		// Middle rows (10..16, 19..25): Top contributing block breakdown
		List<BlockPointBreakdown> topBlocks = maybeSnap.map(IslandLevelSnapshot::topBlocks).orElse(List.of());
		if (topBlocks.isEmpty()) {
			builder.pinButton(13, GuiButton.builder("minecraft:paper", "No Scored Blocks Recorded", ChatFormatting.YELLOW)
				.lore(GuiButton.nonItalic("Run /is level or click Recalculate.", ChatFormatting.GRAY))
				.build());
		} else {
			int count = Math.min(topBlocks.size(), DETAIL_SLOTS.length);
			for (int i = 0; i < count; i++) {
				BlockPointBreakdown b = topBlocks.get(i);
				List<Component> blockLore = new ArrayList<>();
				blockLore.add(GuiButton.nonItalic(
					"Points Earned: +" + LevelHelper.formatNumber(b.pointsEarned()),
					ChatFormatting.GOLD
				));
				blockLore.add(GuiButton.nonItalic(
					"Scored Count: " + LevelHelper.formatNumber(b.countedForPoints()),
					ChatFormatting.WHITE
				));
				if (b.countedForPoints() < b.rawCount()) {
					blockLore.add(GuiButton.nonItalic(
						"Placed Total: " + LevelHelper.formatNumber(b.rawCount()) + " (score capped)",
						ChatFormatting.GRAY
					));
				}
				builder.pinButton(
					DETAIL_SLOTS[i],
					GuiButton.builder(
							LevelHelper.resolveItemIconId(b.blockId()),
							"#" + (i + 1) + " " + LevelHelper.formatBlockDisplayName(b.blockId()),
							ChatFormatting.AQUA
						)
						.lore(blockLore.toArray(Component[]::new))
						.build()
				);
			}
		}

		// Footer row (27..35)
		builder.pinButton(27, GuiButton.builder("minecraft:oak_door", "Back", ChatFormatting.YELLOW)
			.onClick(GuiClickContext::goBack)
			.build());
		builder.pinButton(31, GuiButton.builder("minecraft:gold_block", "Open Island Top", ChatFormatting.GOLD)
			.lore(GuiButton.nonItalic("View global ranked islands", ChatFormatting.GRAY))
			.onClick(click -> click.openScreen(TOP_SCREEN, island.islandId()))
			.build());
		builder.pinButton(33, GuiButton.builder("minecraft:diamond_block", "Block Point Values", ChatFormatting.AQUA)
			.lore(GuiButton.nonItalic("View all configured block values", ChatFormatting.GRAY))
			.onClick(click -> click.openScreen(VALUES_SCREEN, island.islandId()))
			.build());
		builder.pinButton(35, GuiButton.builder("minecraft:sunflower", "Refresh", ChatFormatting.GREEN)
			.onClick(GuiClickContext::refresh)
			.build());

		return builder.build();
	}

	private GuiScreenModel buildValuesScreen(GuiBuildContext ctx) {
		String sort = ctx.stateOrDefault("sort", "points");
		String query = ctx.stateOrDefault("query", "").trim();
		LevelValuationTable table = levelService.valuationTable();

		List<LevelValuationTable.BlockValueEntry> entries = new ArrayList<>(
			LevelHelper.filterValuedBlocks(table.valuedBlocks(), query)
		);
		switch (sort) {
			case "name" -> entries.sort(
				Comparator.comparing(
						(LevelValuationTable.BlockValueEntry e) -> LevelHelper.formatBlockDisplayName(e.blockId()),
						String.CASE_INSENSITIVE_ORDER
					)
					.thenComparing(e -> e.blockId().toString())
			);
			case "points_asc" -> entries.sort(
				Comparator.comparingLong(LevelValuationTable.BlockValueEntry::points)
					.thenComparing(e -> e.blockId().toString())
			);
			default -> sort = "points";
		}

		final String sortLabel;
		final String nextSort;
		switch (sort) {
			case "name" -> {
				sortLabel = "Sort: Alphabetical (A-Z)";
				nextSort = "points_asc";
			}
			case "points_asc" -> {
				sortLabel = "Sort: Lowest Points";
				nextSort = "points";
			}
			default -> {
				sortLabel = "Sort: Highest Points";
				nextSort = "name";
			}
		}

		String title = query.isBlank() ? "Block Point Values" : "Block Values: " + query;
		GuiScreenModel.Builder builder = GuiScreenModel.paged(title)
			.contextButton(GuiButton.builder("minecraft:hopper", sortLabel, ChatFormatting.GOLD)
				.lore(GuiButton.nonItalic("Click to switch block order", ChatFormatting.GRAY))
				.onClick(click -> click.refreshWithState("sort", nextSort))
				.build())
			.emptyStateButton(GuiButton.builder("minecraft:barrier", "No Valued Blocks Found", ChatFormatting.RED)
				.lore(GuiButton.nonItalic(
					query.isBlank()
						? "Check config/ogsky/level.json blockValues."
						: "No blocks match filter '" + query + "'.",
					ChatFormatting.GRAY
				))
				.build());

		List<Component> formulaLore = new ArrayList<>();
		formulaLore.add(GuiButton.nonItalic(
			"Formula: " + LevelHelper.formatNumber(table.pointsPerLevel()) + " pts = 1 level",
			ChatFormatting.YELLOW
		));
		formulaLore.add(GuiButton.nonItalic(
			"Showing: " + LevelHelper.formatNumber(entries.size()) + " block(s)",
			ChatFormatting.WHITE
		));
		if (table.defaultBlockValue() > 0L) {
			formulaLore.add(GuiButton.nonItalic(
				"Unlisted Blocks: +" + LevelHelper.formatNumber(table.defaultBlockValue()) + " pts each",
				ChatFormatting.GREEN
			));
		}
		if (!query.isBlank()) {
			formulaLore.add(GuiButton.nonItalic(
				"Filter: '" + query + "' (Click to clear)",
				ChatFormatting.AQUA
			));
		}
		formulaLore.add(GuiButton.nonItalic("Config: config/ogsky/level.json", ChatFormatting.DARK_GRAY));

		GuiButton.Builder infoButton = GuiButton.builder("minecraft:writable_book", "Level Point Formula", ChatFormatting.GOLD)
			.lore(formulaLore.toArray(Component[]::new));
		if (!query.isBlank()) {
			infoButton.glowing(true).onClick(click -> click.refreshWithState("query", ""));
		}
		builder.pinButton(4, infoButton.build());

		for (LevelValuationTable.BlockValueEntry entry : entries) {
			ChatFormatting tierColor = entry.points() >= 100L
				? ChatFormatting.GOLD
				: (entry.points() >= 15L ? ChatFormatting.AQUA : ChatFormatting.GREEN);
			List<Component> lore = new ArrayList<>();
			lore.add(GuiButton.nonItalic(
				"Value: +" + LevelHelper.formatNumber(entry.points()) + " pts",
				ChatFormatting.YELLOW
			));
			lore.add(GuiButton.nonItalic("Block ID: " + entry.blockId(), ChatFormatting.DARK_GRAY));
			if (entry.hasScoringCap()) {
				lore.add(GuiButton.nonItalic(
					"Score Cap: Max " + LevelHelper.formatNumber(entry.maxCountForPoints()) + " blocks",
					ChatFormatting.GRAY
				));
				lore.add(GuiButton.nonItalic(
					"Max Points: +" + LevelHelper.formatNumber(entry.points() * entry.maxCountForPoints()) + " pts",
					ChatFormatting.GRAY
				));
			} else {
				lore.add(GuiButton.nonItalic("Score Cap: Unlimited", ChatFormatting.DARK_GRAY));
			}
			builder.addPagedEntry(
				GuiButton.builder(
						LevelHelper.resolveItemIconId(entry.blockId()),
						LevelHelper.formatBlockDisplayName(entry.blockId()),
						tierColor
					)
					.lore(lore.toArray(Component[]::new))
					.build()
			);
		}
		return builder.build();
	}

	private void triggerScanFromGui(GuiClickContext click, IslandView island) {
		ServerPlayer actor = click.actor();
		boolean bypassCooldown = core.permissions().isBypassActive(actor)
			|| core.permissions().hasPermission(actor, "ogsky.level.bypass_cooldown", 2);
		MinecraftServer server = actor.level().getServer();

		levelService.requestScan(server, island, bypassCooldown, true).thenAccept(outcome -> {
			if (actor.hasDisconnected()) {
				return;
			}
			if (!outcome.success()) {
				actor.sendSystemMessage(Component.literal("[Ogsky] " + outcome.message()).withStyle(ChatFormatting.RED));
				return;
			}
			if (outcome.snapshot() != null) {
				actor.sendSystemMessage(
					Component.literal("[Ogsky] Island level updated: "
							+ LevelHelper.formatNumber(outcome.snapshot().levelValue())
							+ " (" + LevelHelper.formatNumber(outcome.snapshot().blockPoints()) + " pts).")
						.withStyle(ChatFormatting.GREEN)
				);
			}
			core.guis().refreshIslandScreens(island.islandId());
		});
		click.sendFeedback(true, "Scanning island blocks...");
		click.refresh();
	}

	private void executeActionLevel(ServerPlayer actor, UUID islandId) {
		if (core.guis().openScreen(actor, DETAIL_SCREEN, islandId)) {
			return;
		}
		Optional<IslandView> island = core.islands().findById(islandId);
		if (island.isEmpty()) {
			actor.sendSystemMessage(Component.literal("[Ogsky] Island does not exist.").withStyle(ChatFormatting.RED));
			return;
		}
		boolean bypassCooldown = core.permissions().isBypassActive(actor)
			|| core.permissions().hasPermission(actor, "ogsky.level.bypass_cooldown", 2);
		levelService.requestScan(actor.level().getServer(), island.get(), bypassCooldown, true).thenAccept(outcome -> {
			if (actor.hasDisconnected()) {
				return;
			}
			if (!outcome.success()) {
				actor.sendSystemMessage(Component.literal("[Ogsky] " + outcome.message()).withStyle(ChatFormatting.RED));
				return;
			}
			if (outcome.snapshot() != null) {
				commands.sendIslandLevelSummary(actor.createCommandSourceStack(), actor.level().getServer(), outcome.snapshot(), "Island Level");
			}
		});
	}

	private static String yesNo(boolean value) {
		return value ? "Yes" : "No";
	}
}
