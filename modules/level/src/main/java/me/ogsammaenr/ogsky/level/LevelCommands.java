package me.ogsammaenr.ogsky.level;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import me.ogsammaenr.ogsky.core.api.OgskyCoreApi;
import me.ogsammaenr.ogsky.core.api.command.IslandCommandHelper;
import me.ogsammaenr.ogsky.core.api.command.IslandCommandRegistry;
import me.ogsammaenr.ogsky.core.api.command.IslandSubcommand;
import me.ogsammaenr.ogsky.core.api.gui.GuiOpenRequest;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import me.ogsammaenr.ogsky.core.api.level.BlockPointBreakdown;
import me.ogsammaenr.ogsky.core.api.level.IslandLevelSnapshot;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Registers {@code /is level}, {@code /is top}, {@code /is values}, and {@code /isadmin level} via {@code core.commands()}.
 */
final class LevelCommands {
	private static final String PERM_LEVEL = "ogsky.command.level";
	private static final String PERM_TOP = "ogsky.command.top";
	private static final String PERM_VALUES = "ogsky.command.values";
	private static final String PERM_ADMIN_LEVEL = "ogsky.admin.level";
	private static final String PERM_BYPASS_COOLDOWN = "ogsky.level.bypass_cooldown";

	private record PageSlice(int page, int totalPages, int start, int end) {
		private static PageSlice of(int totalItems, int requestedPage, int perPage) {
			int safePerPage = Math.max(1, perPage);
			int totalPages = Math.max(1, (totalItems + safePerPage - 1) / safePerPage);
			int page = Math.max(1, Math.min(requestedPage, totalPages));
			int start = (page - 1) * safePerPage;
			int end = Math.min(start + safePerPage, totalItems);
			return new PageSlice(page, totalPages, start, end);
		}
	}

	private static final SuggestionProvider<CommandSourceStack> SUGGEST_PLAYERS =
		(ctx, builder) -> SharedSuggestionProvider.suggest(ctx.getSource().getServer().getPlayerNames(), builder);

	private final OgskyCoreApi core;
	private final IslandLevelService levelService;

	LevelCommands(OgskyCoreApi core, IslandLevelService levelService) {
		this.core = core;
		this.levelService = levelService;
	}

	void registerAll(IslandCommandRegistry registry) {
		registry.registerSubcommand(new LevelSubcommand());
		registry.registerSubcommand(new TopSubcommand());
		registry.registerSubcommand(new ValuesSubcommand());
		registry.registerAdminSubcommand(new AdminLevelSubcommand());
	}

	void sendChatLeaderboard(CommandSourceStack source, int requestedPage) {
		MinecraftServer server = source.getServer();
		int maxEntries = levelService.config().top.maxCachedEntries;
		List<IslandLevelSnapshot> entries = levelService.topIslands(maxEntries);
		if (entries.isEmpty()) {
			source.sendSuccess(
				() -> Component.literal("[Ogsky] No islands have been ranked yet. Run /is level!").withStyle(ChatFormatting.YELLOW),
				false
			);
			return;
		}
		PageSlice slice = PageSlice.of(entries.size(), requestedPage, levelService.config().top.chatEntriesPerPage);

		source.sendSuccess(
			() -> Component.literal("=== Skyblock Island Top (" + slice.page() + "/" + slice.totalPages() + ") ===")
				.withStyle(ChatFormatting.GOLD),
			false
		);
		for (int i = slice.start(); i < slice.end(); i++) {
			IslandLevelSnapshot entry = entries.get(i);
			int rank = i + 1;
			String ownerName = LevelHelper.resolvePlayerName(server, entry.ownerId());
			source.sendSuccess(
				() -> Component.literal("#" + rank + " ")
					.withStyle(ChatFormatting.YELLOW)
					.append(Component.literal(ownerName).withStyle(ChatFormatting.AQUA))
					.append(Component.literal(" - Level " + LevelHelper.formatNumber(entry.levelValue())
						+ " (" + LevelHelper.formatNumber(entry.blockPoints()) + " pts)")
						.withStyle(ChatFormatting.GRAY)),
				false
			);
		}
	}

	void sendChatValues(CommandSourceStack source, int requestedPage, @Nullable String rawQuery) {
		LevelValuationTable table = levelService.valuationTable();
		String query = rawQuery == null ? "" : rawQuery.trim();
		List<LevelValuationTable.BlockValueEntry> entries = LevelHelper.filterValuedBlocks(table.valuedBlocks(), query);
		if (entries.isEmpty()) {
			String emptyMsg = query.isEmpty()
				? "[Ogsky] No positive block point values are configured in config/ogsky/level.json."
				: "[Ogsky] No valued blocks matched '" + query + "'.";
			source.sendSuccess(() -> Component.literal(emptyMsg).withStyle(ChatFormatting.YELLOW), false);
			return;
		}

		PageSlice slice = PageSlice.of(entries.size(), requestedPage, levelService.config().top.chatEntriesPerPage);
		String filterSuffix = query.isEmpty() ? "" : " | Filter: " + query;
		String defaultSuffix = table.defaultBlockValue() > 0L
			? " | Other blocks: +" + LevelHelper.formatNumber(table.defaultBlockValue()) + " pts"
			: "";
		source.sendSuccess(
			() -> Component.literal("=== Island Block Values ("
					+ slice.page() + "/" + slice.totalPages()
					+ " | " + LevelHelper.formatNumber(table.pointsPerLevel()) + " pts/level"
					+ filterSuffix
					+ defaultSuffix
					+ ") ===")
				.withStyle(ChatFormatting.GOLD),
			false
		);
		for (int i = slice.start(); i < slice.end(); i++) {
			LevelValuationTable.BlockValueEntry entry = entries.get(i);
			String displayName = LevelHelper.formatBlockDisplayName(entry.blockId());
			String capNote = entry.hasScoringCap()
				? " (Max: " + LevelHelper.formatNumber(entry.maxCountForPoints()) + " blocks)"
				: "";
			source.sendSuccess(
				() -> Component.literal("- " + displayName + " ")
					.withStyle(ChatFormatting.AQUA)
					.append(Component.literal("(" + entry.blockId() + "): ").withStyle(ChatFormatting.DARK_GRAY))
					.append(Component.literal("+" + LevelHelper.formatNumber(entry.points()) + " pts").withStyle(ChatFormatting.YELLOW))
					.append(Component.literal(capNote).withStyle(ChatFormatting.GRAY)),
				false
			);
		}
		if (slice.totalPages() > 1) {
			int nextPage = slice.page() < slice.totalPages() ? slice.page() + 1 : 1;
			source.sendSuccess(
				() -> Component.literal("Use /is values " + nextPage + " or /is values search <block> to browse.")
					.withStyle(ChatFormatting.DARK_GRAY),
				false
			);
		}
	}

	void sendIslandLevelSummary(
		CommandSourceStack source,
		MinecraftServer server,
		IslandLevelSnapshot snapshot,
		String prefixLabel
	) {
		String ownerName = LevelHelper.resolvePlayerName(server, snapshot.ownerId());
		OptionalInt rank = levelService.rankOf(snapshot.islandId());
		String rankText = rank.isPresent() ? "Rank #" + rank.getAsInt() : "Unranked";

		source.sendSuccess(
			() -> Component.literal("[Ogsky] " + prefixLabel + " (" + ownerName + "): ")
				.withStyle(ChatFormatting.GOLD)
				.append(Component.literal("Level " + LevelHelper.formatNumber(snapshot.levelValue()))
					.withStyle(ChatFormatting.GREEN))
				.append(Component.literal(" (" + LevelHelper.formatNumber(snapshot.blockPoints()) + " pts | " + rankText + ")")
					.withStyle(ChatFormatting.GRAY)),
			false
		);
		source.sendSuccess(
			() -> Component.literal("  Next level in " + LevelHelper.formatNumber(snapshot.pointsToNextLevel())
					+ " pts (" + LevelHelper.formatNumber(snapshot.pointsPerLevel()) + " pts/level)")
				.withStyle(ChatFormatting.DARK_GRAY),
			false
		);
		List<BlockPointBreakdown> topBlocks = snapshot.topBlocks();
		int previewLimit = Math.min(3, topBlocks.size());
		for (int i = 0; i < previewLimit; i++) {
			BlockPointBreakdown b = topBlocks.get(i);
			String blockName = LevelHelper.formatBlockDisplayName(b.blockId());
			String capNote = b.countedForPoints() < b.rawCount()
				? " (capped from " + LevelHelper.formatNumber(b.rawCount()) + ")"
				: "";
			source.sendSuccess(
				() -> Component.literal("  - " + blockName + ": x" + LevelHelper.formatNumber(b.countedForPoints())
						+ capNote + " -> +" + LevelHelper.formatNumber(b.pointsEarned()) + " pts")
					.withStyle(ChatFormatting.GRAY),
				false
			);
		}
	}

	private int executeOwnLevel(CommandContext<CommandSourceStack> ctx, IslandCommandHelper helper) {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player;
		try {
			player = source.getPlayerOrException();
		} catch (Exception e) {
			source.sendFailure(Component.literal("[Ogsky] Specify a player from console: /is level <player>"));
			return 0;
		}

		Optional<IslandView> targetOpt = LevelHelper.resolveContextIsland(
			player,
			helper,
			core.flags(),
			core.permissions()
		);
		if (targetOpt.isEmpty()) {
			source.sendFailure(Component.literal("[Ogsky] You do not have an island yet. Use /is create to start."));
			return 0;
		}
		IslandView target = targetOpt.get();
		MinecraftServer server = source.getServer();

		boolean isMember = target.roleOf(player.getUUID()) > IslandRole.VISITOR;
		boolean bypassCooldown = core.permissions().isBypassActive(player)
			|| core.permissions().hasPermission(player, PERM_BYPASS_COOLDOWN, 2);

		if (!isMember && !bypassCooldown) {
			Optional<IslandLevelSnapshot> cached = levelService.findSnapshot(target.islandId());
			if (cached.isEmpty()) {
				source.sendFailure(Component.literal("[Ogsky] That island has not calculated its level yet."));
				return 0;
			}
			sendIslandLevelSummary(source, server, cached.get(), "Island Level");
			return Command.SINGLE_SUCCESS;
		}

		source.sendSuccess(
			() -> Component.literal("[Ogsky] Scanning island blocks for level calculation...").withStyle(ChatFormatting.YELLOW),
			false
		);
		levelService.requestScan(server, target, bypassCooldown, true).thenAccept(outcome -> {
			if (player.hasDisconnected()) {
				return;
			}
			if (!outcome.success()) {
				player.sendSystemMessage(Component.literal("[Ogsky] " + outcome.message()).withStyle(ChatFormatting.RED));
				if (outcome.snapshot() != null) {
					sendIslandLevelSummary(player.createCommandSourceStack(), server, outcome.snapshot(), "Last Cached Level");
				}
				return;
			}
			if (outcome.snapshot() != null) {
				sendIslandLevelSummary(player.createCommandSourceStack(), server, outcome.snapshot(), "Island Level");
			}
		});
		return Command.SINGLE_SUCCESS;
	}

	private int executeOtherLevel(CommandContext<CommandSourceStack> ctx, String playerArg) {
		CommandSourceStack source = ctx.getSource();
		MinecraftServer server = source.getServer();
		UUID targetUuid = LevelHelper.resolveTargetUuid(server, playerArg);
		if (targetUuid == null) {
			source.sendFailure(Component.literal("[Ogsky] Player '" + playerArg + "' was not found."));
			return 0;
		}
		Optional<IslandView> island = core.islands().findPrimaryIsland(targetUuid);
		if (island.isEmpty()) {
			source.sendFailure(Component.literal("[Ogsky] " + playerArg + " does not have an island."));
			return 0;
		}
		Optional<IslandLevelSnapshot> snapshot = levelService.findSnapshot(island.get().islandId());
		if (snapshot.isEmpty()) {
			source.sendFailure(Component.literal("[Ogsky] " + playerArg + "'s island has not calculated its level yet."));
			return 0;
		}
		sendIslandLevelSummary(source, server, snapshot.get(), "Island Level");
		return Command.SINGLE_SUCCESS;
	}

	private int executeTop(CommandContext<CommandSourceStack> ctx, IslandCommandHelper helper, int requestedPage) {
		CommandSourceStack source = ctx.getSource();
		if (source.getEntity() instanceof ServerPlayer player) {
			UUID contextIslandId = LevelHelper.resolveContextIsland(player, helper, core.flags(), core.permissions())
				.map(IslandView::islandId)
				.orElse(null);
			GuiOpenRequest req = GuiOpenRequest.of(
				LevelGuiRegistrar.TOP_SCREEN,
				contextIslandId,
				Math.max(0, requestedPage - 1)
			);
			if (core.guis().openScreen(player, req)) {
				return Command.SINGLE_SUCCESS;
			}
		}
		sendChatLeaderboard(source, requestedPage);
		return Command.SINGLE_SUCCESS;
	}

	private int executeValues(
		CommandContext<CommandSourceStack> ctx,
		IslandCommandHelper helper,
		int requestedPage,
		@Nullable String rawQuery
	) {
		CommandSourceStack source = ctx.getSource();
		String query = rawQuery == null ? "" : rawQuery.trim();
		if (source.getEntity() instanceof ServerPlayer player) {
			UUID contextIslandId = LevelHelper.resolveContextIsland(player, helper, core.flags(), core.permissions())
				.map(IslandView::islandId)
				.orElse(null);
			Map<String, String> state = query.isEmpty() ? Map.of() : Map.of("query", query);
			GuiOpenRequest req = new GuiOpenRequest(
				LevelGuiRegistrar.VALUES_SCREEN,
				contextIslandId,
				Math.max(0, requestedPage - 1),
				state,
				null
			);
			if (core.guis().openScreen(player, req)) {
				return Command.SINGLE_SUCCESS;
			}
		}
		sendChatValues(source, requestedPage, query);
		return Command.SINGLE_SUCCESS;
	}

	private int executeAdminScan(CommandContext<CommandSourceStack> ctx, String ownerArg) {
		CommandSourceStack source = ctx.getSource();
		MinecraftServer server = source.getServer();
		UUID ownerUuid = LevelHelper.resolveTargetUuid(server, ownerArg);
		if (ownerUuid == null) {
			source.sendFailure(Component.literal("[Ogsky] Player '" + ownerArg + "' was not found."));
			return 0;
		}
		Optional<IslandView> island = core.islands().findPrimaryIsland(ownerUuid);
		if (island.isEmpty()) {
			source.sendFailure(Component.literal("[Ogsky] " + ownerArg + " does not have an island."));
			return 0;
		}
		source.sendSuccess(
			() -> Component.literal("[Ogsky] Running admin island level scan for " + ownerArg + "...")
				.withStyle(ChatFormatting.YELLOW),
			false
		);
		levelService.requestScan(server, island.get(), true, false).thenAccept(outcome -> {
			if (!outcome.success()) {
				source.sendFailure(Component.literal("[Ogsky] " + outcome.message()));
				return;
			}
			if (outcome.snapshot() != null) {
				sendIslandLevelSummary(source, server, outcome.snapshot(), "Admin Scan Result");
			}
		});
		return Command.SINGLE_SUCCESS;
	}

	private int executeAdminScanAll(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		int queued = levelService.startScanAll(source.getServer());
		source.sendSuccess(
			() -> Component.literal("[Ogsky] Queued " + queued + " island(s) for sequential level recalculation.")
				.withStyle(ChatFormatting.GREEN),
			true
		);
		return Command.SINGLE_SUCCESS;
	}

	private int executeAdminReload(CommandContext<CommandSourceStack> ctx) {
		levelService.reloadConfig();
		ctx.getSource().sendSuccess(
			() -> Component.literal("[Ogsky] Reloaded config/ogsky/level.json ("
					+ levelService.config().blockValues.size() + " block value rule(s), "
					+ levelService.config().formula.pointsPerLevel + " pts/level).")
				.withStyle(ChatFormatting.GREEN),
			true
		);
		return Command.SINGLE_SUCCESS;
	}

	private final class LevelSubcommand implements IslandSubcommand {
		@Override
		public String name() {
			return "level";
		}

		@Override
		public List<String> aliases() {
			return List.of("lvl");
		}

		@Override
		public String permissionNode() {
			return PERM_LEVEL;
		}

		@Override
		public void register(LiteralArgumentBuilder<CommandSourceStack> node, IslandCommandHelper helper) {
			node.executes(ctx -> executeOwnLevel(ctx, helper))
				.then(Commands.argument("player", StringArgumentType.word())
					.suggests(SUGGEST_PLAYERS)
					.executes(ctx -> executeOtherLevel(ctx, StringArgumentType.getString(ctx, "player"))));
		}
	}

	private final class TopSubcommand implements IslandSubcommand {
		@Override
		public String name() {
			return "top";
		}

		@Override
		public List<String> aliases() {
			return List.of("leaderboard");
		}

		@Override
		public String permissionNode() {
			return PERM_TOP;
		}

		@Override
		public void register(LiteralArgumentBuilder<CommandSourceStack> node, IslandCommandHelper helper) {
			node.executes(ctx -> executeTop(ctx, helper, 1))
				.then(Commands.argument("page", IntegerArgumentType.integer(1))
					.executes(ctx -> executeTop(ctx, helper, IntegerArgumentType.getInteger(ctx, "page"))));
		}
	}

	private final class ValuesSubcommand implements IslandSubcommand {
		@Override
		public String name() {
			return "values";
		}

		@Override
		public List<String> aliases() {
			return List.of("value", "worth");
		}

		@Override
		public String permissionNode() {
			return PERM_VALUES;
		}

		@Override
		public void register(LiteralArgumentBuilder<CommandSourceStack> node, IslandCommandHelper helper) {
			node.executes(ctx -> executeValues(ctx, helper, 1, null))
				.then(Commands.argument("page", IntegerArgumentType.integer(1))
					.executes(ctx -> executeValues(ctx, helper, IntegerArgumentType.getInteger(ctx, "page"), null)))
				.then(Commands.literal("search")
					.then(Commands.argument("query", StringArgumentType.greedyString())
						.executes(ctx -> executeValues(ctx, helper, 1, StringArgumentType.getString(ctx, "query")))));
		}
	}

	private final class AdminLevelSubcommand implements IslandSubcommand {
		@Override
		public String name() {
			return "level";
		}

		@Override
		public String permissionNode() {
			return PERM_ADMIN_LEVEL;
		}

		@Override
		public void register(LiteralArgumentBuilder<CommandSourceStack> node, IslandCommandHelper helper) {
			node.then(Commands.literal("scan")
					.then(Commands.argument("owner", StringArgumentType.word())
						.suggests(SUGGEST_PLAYERS)
						.executes(ctx -> executeAdminScan(ctx, StringArgumentType.getString(ctx, "owner")))))
				.then(Commands.literal("scanall")
					.executes(LevelCommands.this::executeAdminScanAll))
				.then(Commands.literal("reload")
					.executes(LevelCommands.this::executeAdminReload));
		}
	}
}
