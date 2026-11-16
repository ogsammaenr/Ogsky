package me.ogsammaenr.ogsky.limits;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import me.ogsammaenr.ogsky.core.api.OgskyCoreApi;
import me.ogsammaenr.ogsky.core.api.command.IslandCommandHelper;
import me.ogsammaenr.ogsky.core.api.command.IslandCommandRegistry;
import me.ogsammaenr.ogsky.core.api.command.IslandSubcommand;
import me.ogsammaenr.ogsky.core.api.gui.GuiOpenRequest;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Registers player ({@code /is limits}) and admin ({@code /isadmin limits ...}) Brigadier subcommands.
 */
final class LimitsCommands {
	private static final int CHAT_ENTRIES_PER_PAGE = 8;
	private static final String PERM_LIMITS = "ogsky.limits";
	private static final String PERM_ADMIN_LIMITS = "ogsky.admin.limits";

	private final OgskyCoreApi core;
	private final IslandLimitService limitService;

	LimitsCommands(OgskyCoreApi core, IslandLimitService limitService) {
		this.core = core;
		this.limitService = limitService;
	}

	void registerAll(IslandCommandRegistry registry) {
		registry.registerSubcommand(new LimitsSubcommand());
		registry.registerAdminSubcommand(new LimitsAdminSubcommand());
	}

	void sendChatLimits(
		CommandSourceStack source,
		IslandView island,
		int requestedPage,
		String rawFilter,
		@Nullable String rawQuery
	) {
		String filter = LimitHelper.normalizeFilter(rawFilter);
		String query = rawQuery == null ? "" : rawQuery.trim();
		MinecraftServer server = source.getServer();
		List<IslandLimitService.RuleStatus> allStatuses = limitService.getRuleStatuses(server, island.islandId());
		List<IslandLimitService.RuleStatus> filtered = LimitHelper.filterAndSortStatuses(
			allStatuses,
			filter,
			query,
			"usage"
		);

		if (filtered.isEmpty()) {
			String msg = query.isEmpty()
				? "[Ogsky] No limit rules match filter '" + filter + "'."
				: "[Ogsky] No limit rules matched '" + query + "'.";
			source.sendSuccess(() -> Component.literal(msg).withStyle(ChatFormatting.YELLOW), false);
			return;
		}

		LimitHelper.PageSlice slice = LimitHelper.PageSlice.of(filtered.size(), requestedPage, CHAT_ENTRIES_PER_PAGE);
		String ownerName = LimitHelper.resolvePlayerName(server, island.ownerId());
		String filterLabel = "all".equals(filter) ? "" : " | " + filter;
		String queryLabel = query.isEmpty() ? "" : " | search: " + query;
		String syncNote = limitService.isScanInProgress(island.islandId())
			? " (Scanning...)"
			: (!limitService.isSynchronized(island.islandId()) ? " (Sync Pending)" : "");

		source.sendSuccess(
			() -> Component.literal("=== Island Limits: " + ownerName + " ("
					+ slice.page() + "/" + slice.totalPages()
					+ filterLabel + queryLabel + ")" + syncNote + " ===")
				.withStyle(ChatFormatting.GOLD),
			false
		);

		for (int i = slice.start(); i < slice.end(); i++) {
			IslandLimitService.RuleStatus status = filtered.get(i);
			LimitRuleTable.LimitRule rule = status.rule();
			ChatFormatting color = LimitHelper.statusColor(status);
			String kindTag = rule.kind() == LimitRuleTable.RuleKind.BLOCK ? "[Block]" : "[Entity]";
			source.sendSuccess(
				() -> Component.literal("- " + rule.displayName() + " ")
					.withStyle(ChatFormatting.AQUA)
					.append(Component.literal(kindTag + " ").withStyle(ChatFormatting.DARK_GRAY))
					.append(Component.literal(
						LimitHelper.formatNumber(status.used()) + "/" + LimitHelper.formatNumber(status.effectiveLimit())
					).withStyle(color))
					.append(Component.literal(
						" (" + LimitHelper.formatNumber(status.remaining()) + " left)"
					).withStyle(ChatFormatting.GRAY)),
				false
			);
		}

		if (slice.totalPages() > 1) {
			int nextPage = slice.page() < slice.totalPages() ? slice.page() + 1 : 1;
			source.sendSuccess(
				() -> Component.literal("Use /is limits chat " + nextPage + " or /is limits blocks|entities|all.")
					.withStyle(ChatFormatting.DARK_GRAY),
				false
			);
		}
	}

	private int executeLimits(
		CommandContext<CommandSourceStack> ctx,
		IslandCommandHelper helper,
		int requestedPage,
		String rawFilter,
		@Nullable String rawQuery,
		boolean forceChat
	) {
		CommandSourceStack source = ctx.getSource();
		if (!(source.getEntity() instanceof ServerPlayer player)) {
			source.sendFailure(Component.literal("[Ogsky] Use /isadmin limits inspect <player> from console."));
			return 0;
		}

		Optional<IslandView> targetOpt = LimitHelper.resolveContextIsland(
			player,
			helper,
			core.flags(),
			core.permissions()
		);
		if (targetOpt.isEmpty()) {
			source.sendFailure(Component.literal("[Ogsky] You do not have or stand on an accessible island."));
			return 0;
		}
		IslandView island = targetOpt.get();
		if (!limitService.canViewLimits(island, player)) {
			source.sendFailure(Component.literal("[Ogsky] You do not have permission to view limits on this island."));
			return 0;
		}

		String filter = LimitHelper.normalizeFilter(rawFilter);
		String query = rawQuery == null ? "" : rawQuery.trim();
		if (!forceChat && core.guis().isAvailable()) {
			Map<String, String> state = new LinkedHashMap<>();
			state.put("filter", filter);
			if (!query.isEmpty()) {
				state.put("query", query);
			}
			GuiOpenRequest req = new GuiOpenRequest(
				LimitsGuiRegistrar.LIMITS_SCREEN,
				island.islandId(),
				Math.max(0, requestedPage - 1),
				state,
				null
			);
			if (core.guis().openScreen(player, req)) {
				return Command.SINGLE_SUCCESS;
			}
		}

		sendChatLimits(source, island, requestedPage, filter, query);
		return Command.SINGLE_SUCCESS;
	}

	private int executeAdminReload(CommandContext<CommandSourceStack> ctx) {
		MinecraftServer server = ctx.getSource().getServer();
		limitService.reload(server);
		LimitRuleTable table = limitService.ruleTable();
		ctx.getSource().sendSuccess(
			() -> Component.literal("[Ogsky] Reloaded config/ogsky/limits.json ("
					+ table.blockRuleCount() + " block rule(s), "
					+ table.entityRuleCount() + " entity rule(s)).")
				.withStyle(ChatFormatting.GREEN),
			true
		);
		return Command.SINGLE_SUCCESS;
	}

	private int executeAdminRescan(CommandContext<CommandSourceStack> ctx, String ownerArg) {
		CommandSourceStack source = ctx.getSource();
		MinecraftServer server = source.getServer();
		UUID ownerUuid = LimitHelper.resolveTargetUuid(server, ownerArg);
		if (ownerUuid == null) {
			source.sendFailure(Component.literal("[Ogsky] Player '" + ownerArg + "' was not found."));
			return 0;
		}
		Optional<IslandView> island = core.islands().findPrimaryIsland(ownerUuid);
		if (island.isEmpty()) {
			source.sendFailure(Component.literal("[Ogsky] " + ownerArg + " does not have an island."));
			return 0;
		}
		IslandView target = island.get();
		source.sendSuccess(
			() -> Component.literal("[Ogsky] Rescanning blocks and entities for " + ownerArg + "'s island...")
				.withStyle(ChatFormatting.YELLOW),
			true
		);
		limitService.requestRescan(server, target.islandId(), true).whenComplete((res, err) -> {
			if (err != null || res == null || res.aborted()) {
				source.sendFailure(Component.literal("[Ogsky] Limit rescan failed or was aborted for " + ownerArg + "."));
				return;
			}
			source.sendSuccess(
				() -> Component.literal("[Ogsky] Limit rescan completed for " + ownerArg + " ("
						+ res.totalChunksScanned() + " chunk(s) scanned).")
					.withStyle(ChatFormatting.GREEN),
				true
			);
		});
		return Command.SINGLE_SUCCESS;
	}

	private int executeAdminRescanAll(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		int queued = limitService.rescanAll(source.getServer());
		source.sendSuccess(
			() -> Component.literal("[Ogsky] Queued limit rescan for " + queued + " island(s).")
				.withStyle(ChatFormatting.GREEN),
			true
		);
		return Command.SINGLE_SUCCESS;
	}

	private int executeAdminInspect(CommandContext<CommandSourceStack> ctx, String ownerArg, int page) {
		CommandSourceStack source = ctx.getSource();
		MinecraftServer server = source.getServer();
		UUID ownerUuid = LimitHelper.resolveTargetUuid(server, ownerArg);
		if (ownerUuid == null) {
			source.sendFailure(Component.literal("[Ogsky] Player '" + ownerArg + "' was not found."));
			return 0;
		}
		Optional<IslandView> island = core.islands().findPrimaryIsland(ownerUuid);
		if (island.isEmpty()) {
			source.sendFailure(Component.literal("[Ogsky] " + ownerArg + " does not have an island."));
			return 0;
		}
		sendChatLimits(source, island.get(), page, "all", null);
		return Command.SINGLE_SUCCESS;
	}

	private final class LimitsSubcommand implements IslandSubcommand {
		@Override
		public String name() {
			return "limits";
		}

		@Override
		public List<String> aliases() {
			return List.of("limit");
		}

		@Override
		public String permissionNode() {
			return PERM_LIMITS;
		}

		@Override
		public void register(LiteralArgumentBuilder<CommandSourceStack> node, IslandCommandHelper helper) {
			node.executes(ctx -> executeLimits(ctx, helper, 1, "all", null, false))
				.then(Commands.argument("page", IntegerArgumentType.integer(1))
					.executes(ctx -> executeLimits(
						ctx,
						helper,
						IntegerArgumentType.getInteger(ctx, "page"),
						"all",
						null,
						false
					)))
				.then(Commands.literal("all")
					.executes(ctx -> executeLimits(ctx, helper, 1, "all", null, false))
					.then(Commands.argument("page", IntegerArgumentType.integer(1))
						.executes(ctx -> executeLimits(
							ctx,
							helper,
							IntegerArgumentType.getInteger(ctx, "page"),
							"all",
							null,
							false
						))))
				.then(Commands.literal("blocks")
					.executes(ctx -> executeLimits(ctx, helper, 1, "blocks", null, false))
					.then(Commands.argument("page", IntegerArgumentType.integer(1))
						.executes(ctx -> executeLimits(
							ctx,
							helper,
							IntegerArgumentType.getInteger(ctx, "page"),
							"blocks",
							null,
							false
						))))
				.then(Commands.literal("entities")
					.executes(ctx -> executeLimits(ctx, helper, 1, "entities", null, false))
					.then(Commands.argument("page", IntegerArgumentType.integer(1))
						.executes(ctx -> executeLimits(
							ctx,
							helper,
							IntegerArgumentType.getInteger(ctx, "page"),
							"entities",
							null,
							false
						))))
				.then(Commands.literal("chat")
					.executes(ctx -> executeLimits(ctx, helper, 1, "all", null, true))
					.then(Commands.argument("page", IntegerArgumentType.integer(1))
						.executes(ctx -> executeLimits(
							ctx,
							helper,
							IntegerArgumentType.getInteger(ctx, "page"),
							"all",
							null,
							true
						))))
				.then(Commands.literal("search")
					.then(Commands.argument("query", StringArgumentType.word())
						.executes(ctx -> executeLimits(
							ctx,
							helper,
							1,
							"all",
							StringArgumentType.getString(ctx, "query"),
							false
						))
						.then(Commands.argument("page", IntegerArgumentType.integer(1))
							.executes(ctx -> executeLimits(
								ctx,
								helper,
								IntegerArgumentType.getInteger(ctx, "page"),
								"all",
								StringArgumentType.getString(ctx, "query"),
								false
							)))));
		}
	}

	private final class LimitsAdminSubcommand implements IslandSubcommand {
		@Override
		public String name() {
			return "limits";
		}

		@Override
		public String permissionNode() {
			return PERM_ADMIN_LIMITS;
		}

		@Override
		public void register(LiteralArgumentBuilder<CommandSourceStack> node, IslandCommandHelper helper) {
			node.requires(src -> helper.hasPermission(src, PERM_ADMIN_LIMITS, 2))
				.then(Commands.literal("reload")
					.executes(LimitsCommands.this::executeAdminReload))
				.then(Commands.literal("rescanall")
					.executes(LimitsCommands.this::executeAdminRescanAll))
				.then(Commands.literal("rescan")
					.then(Commands.argument("player", StringArgumentType.word())
						.executes(ctx -> executeAdminRescan(ctx, StringArgumentType.getString(ctx, "player")))))
				.then(Commands.literal("inspect")
					.then(Commands.argument("player", StringArgumentType.word())
						.executes(ctx -> executeAdminInspect(ctx, StringArgumentType.getString(ctx, "player"), 1))
						.then(Commands.argument("page", IntegerArgumentType.integer(1))
							.executes(ctx -> executeAdminInspect(
								ctx,
								StringArgumentType.getString(ctx, "player"),
								IntegerArgumentType.getInteger(ctx, "page")
							)))));
		}
	}
}
