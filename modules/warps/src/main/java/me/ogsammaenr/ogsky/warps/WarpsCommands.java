package me.ogsammaenr.ogsky.warps;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
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
import me.ogsammaenr.ogsky.core.api.island.IslandOperationResult;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import me.ogsammaenr.ogsky.core.api.level.IslandLevelProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
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
 * Registers {@code /is warp}, {@code /is warps}, {@code /is setwarp}, {@code /is delwarp},
 * and {@code /isadmin warps} Brigadier subcommands.
 */
final class WarpsCommands {
	private static final String PERM_WARP = "ogsky.command.warp";
	private static final String PERM_WARPS = "ogsky.command.warps";
	private static final String PERM_SETWARP = "ogsky.command.setwarp";
	private static final String PERM_DELWARP = "ogsky.command.delwarp";
	private static final String PERM_ADMIN_WARPS = "ogsky.admin.warps";

	private final OgskyCoreApi core;
	private final IslandWarpService warpService;

	WarpsCommands(OgskyCoreApi core, IslandWarpService warpService) {
		this.core = core;
		this.warpService = warpService;
	}

	void registerAll(IslandCommandRegistry registry) {
		registry.registerSubcommand(new WarpSubcommand());
		registry.registerSubcommand(new WarpsSubcommand());
		registry.registerSubcommand(new SetWarpSubcommand());
		registry.registerSubcommand(new DelWarpSubcommand());
		registry.registerAdminSubcommand(new AdminWarpsSubcommand());
	}

	void sendChatShowcase(
		CommandSourceStack source,
		int requestedPage,
		String rawCategory,
		String rawSort,
		@Nullable String rawQuery,
		@Nullable UUID filterIslandId,
		@Nullable String headerTitle
	) {
		MinecraftServer server = source.getServer();
		WarpConfig cfg = warpService.config();
		String category = WarpHelper.normalizeCategoryFilter(rawCategory, cfg);
		String sort = WarpHelper.normalizeSort(rawSort);
		String query = rawQuery == null ? "" : rawQuery.trim();
		List<WarpHelper.ShowcaseEntry> entries = warpService.queryShowcaseEntries(server, category, sort, query, filterIslandId);
		if (entries.isEmpty()) {
			source.sendSuccess(
				() -> Component.literal("[Ogsky Warps] No active warps matched your criteria.")
					.withStyle(ChatFormatting.YELLOW),
				false
			);
			return;
		}
		boolean hasLevel = core.services().find(IslandLevelProvider.class).isPresent();
		WarpHelper.PageSlice slice = WarpHelper.PageSlice.of(entries.size(), requestedPage, cfg.chatEntriesPerPage);
		String title = headerTitle != null ? headerTitle : "Island Warps";
		String filterInfo = "all".equals(category) ? "" : " | " + cfg.categoryDisplayName(category);
		String queryInfo = query.isEmpty() ? "" : " | search: " + query;

		source.sendSuccess(
			() -> Component.literal("=== " + title + " (" + slice.page() + "/" + slice.totalPages()
					+ " | " + sort + filterInfo + queryInfo + ") ===")
				.withStyle(ChatFormatting.GOLD),
			false
		);

		for (int i = slice.start(); i < slice.end(); i++) {
			WarpHelper.ShowcaseEntry entry = entries.get(i);
			IslandWarp warp = entry.warp();
			String dim = WarpHelper.formatDimensionName(warp.dimensionId());
			String cat = cfg.categoryDisplayName(warp.categoryId());
			String levelText = hasLevel ? " | Lvl " + entry.islandLevel() : "";
			String descText = warp.description().isEmpty() ? "" : " - " + warp.description();
			source.sendSuccess(
				() -> Component.literal("- " + warp.displayName())
					.withStyle(ChatFormatting.AQUA)
					.append(Component.literal(" by " + entry.ownerName()).withStyle(ChatFormatting.WHITE))
					.append(Component.literal(" [" + cat + ", " + dim + ", " + warp.visitCount() + " visits" + levelText + "]")
						.withStyle(ChatFormatting.GRAY))
					.append(Component.literal(descText).withStyle(ChatFormatting.DARK_GRAY)),
				false
			);
		}
		source.sendSuccess(
			() -> Component.literal("Use /is warp <player> [warp] to teleport or /is warps chat <page> for more.")
				.withStyle(ChatFormatting.DARK_GRAY),
			false
		);
	}

	void sendChatManageSummary(CommandSourceStack source, IslandView island) {
		MinecraftServer server = source.getServer();
		List<IslandWarp> warps = warpService.getIslandWarps(island.islandId());
		int max = warpService.resolveMaxWarps(server, island);
		String ownerName = WarpHelper.resolvePlayerName(server, island.ownerId());
		source.sendSuccess(
			() -> Component.literal("=== Island Warps: " + ownerName + " (" + warps.size() + "/" + max + ") ===")
				.withStyle(ChatFormatting.GOLD),
			false
		);
		if (warps.isEmpty()) {
			source.sendSuccess(
				() -> Component.literal("No warps set. Stand inside your island and run /is setwarp [name].")
					.withStyle(ChatFormatting.GRAY),
				false
			);
			return;
		}
		for (IslandWarp warp : warps) {
			boolean inBounds = warpService.isWithinIslandBounds(island, warp);
			String stateLabel = !warp.enabled() ? "DISABLED" : (!inBounds ? "OUT_OF_BOUNDS" : "ACTIVE");
			ChatFormatting stateColor = warp.enabled() && inBounds ? ChatFormatting.GREEN : ChatFormatting.RED;
			String defaultTag = warp.isDefault() ? " [Default]" : "";
			String dim = WarpHelper.formatDimensionName(warp.dimensionId());
			source.sendSuccess(
				() -> Component.literal("- " + warp.displayName() + defaultTag + " ")
					.withStyle(ChatFormatting.AQUA)
					.append(Component.literal("[" + stateLabel + "] ").withStyle(stateColor))
					.append(Component.literal(
						warpService.config().categoryDisplayName(warp.categoryId())
							+ " | " + dim
							+ " | " + warp.visitCount() + " visits"
					).withStyle(ChatFormatting.GRAY)),
				false
			);
		}
	}

	private int executeWarpTeleport(
		CommandContext<CommandSourceStack> ctx,
		String targetPlayerName,
		@Nullable String warpName
	) {
		ServerPlayer actor = requirePlayer(ctx.getSource());
		if (actor == null) {
			return 0;
		}
		MinecraftServer server = ctx.getSource().getServer();
		Optional<IslandView> targetIsland = resolvePlayerIsland(ctx.getSource(), server, targetPlayerName);
		if (targetIsland.isEmpty()) {
			return 0;
		}
		IslandView island = targetIsland.get();
		IslandOperationResult<Void> result = warpService.requestTeleport(server, actor, island.islandId(), warpName);
		if (!result.success() && "multiple_warps".equals(result.code())) {
			if (!openPlayerWarpsUi(actor, island.islandId())) {
				sendChatShowcase(
					ctx.getSource(),
					1,
					"all",
					"recent",
					null,
					island.islandId(),
					WarpHelper.resolvePlayerName(server, island.ownerId()) + "'s Warps"
				);
			}
			return Command.SINGLE_SUCCESS;
		}
		return reportResult(ctx.getSource(), result);
	}

	private int executeOpenShowcase(
		CommandContext<CommandSourceStack> ctx,
		int page,
		String category,
		String sort,
		@Nullable String query,
		@Nullable UUID filterIslandId,
		boolean forceChat
	) {
		CommandSourceStack source = ctx.getSource();
		if (forceChat || !(source.getEntity() instanceof ServerPlayer player)) {
			sendChatShowcase(source, page, category, sort, query, filterIslandId, null);
			return Command.SINGLE_SUCCESS;
		}
		Map<String, String> state = new LinkedHashMap<>();
		state.put("category", WarpHelper.normalizeCategoryFilter(category, warpService.config()));
		state.put("sort", WarpHelper.normalizeSort(sort));
		if (query != null && !query.isBlank()) {
			state.put("query", query.trim());
		}
		boolean opened = core.guis().openScreen(
			player,
			new GuiOpenRequest(WarpsGuiRegistrar.SHOWCASE_SCREEN, filterIslandId, Math.max(0, page - 1), state, null)
		);
		if (!opened) {
			sendChatShowcase(source, page, category, sort, query, filterIslandId, null);
		}
		return Command.SINGLE_SUCCESS;
	}

	private boolean openPlayerWarpsUi(ServerPlayer player, UUID islandId) {
		Map<String, String> state = Map.of("category", "all", "sort", "recent");
		return core.guis().openScreen(
			player,
			new GuiOpenRequest(WarpsGuiRegistrar.SHOWCASE_SCREEN, islandId, 0, state, null)
		);
	}

	private int executeOpenManage(CommandContext<CommandSourceStack> ctx, IslandCommandHelper helper) {
		ServerPlayer actor = requirePlayer(ctx.getSource());
		if (actor == null) {
			return 0;
		}
		Optional<IslandView> island = resolveManageTargetIsland(actor, helper);
		if (island.isEmpty()) {
			ctx.getSource().sendFailure(Component.literal("[Ogsky Warps] Stand on an island you can manage or create an island first."));
			return 0;
		}
		if (!core.guis().openScreen(actor, WarpsGuiRegistrar.MANAGE_SCREEN, island.get().islandId())) {
			sendChatManageSummary(ctx.getSource(), island.get());
		}
		return Command.SINGLE_SUCCESS;
	}

	private int executePlayerWarpsList(CommandContext<CommandSourceStack> ctx, String targetName) {
		MinecraftServer server = ctx.getSource().getServer();
		Optional<IslandView> targetIsland = resolvePlayerIsland(ctx.getSource(), server, targetName);
		if (targetIsland.isEmpty()) {
			return 0;
		}
		return executeOpenShowcase(ctx, 1, "all", "recent", null, targetIsland.get().islandId(), false);
	}

	private int executeSetWarp(
		CommandContext<CommandSourceStack> ctx,
		IslandCommandHelper helper,
		@Nullable String rawName,
		@Nullable String rawCategory
	) {
		ServerPlayer actor = requirePlayer(ctx.getSource());
		if (actor == null) {
			return 0;
		}
		Optional<IslandView> island = helper.islandAt(actor).or(() -> helper.primaryIsland(actor));
		if (island.isEmpty()) {
			ctx.getSource().sendFailure(Component.literal("[Ogsky Warps] You must be inside a Skyblock island to set a warp."));
			return 0;
		}
		return reportResult(
			ctx.getSource(),
			warpService.createOrUpdateWarp(ctx.getSource().getServer(), actor, island.get().islandId(), rawName, rawCategory)
		);
	}

	private int executeDeleteWarp(
		CommandContext<CommandSourceStack> ctx,
		IslandCommandHelper helper,
		@Nullable String rawName
	) {
		ServerPlayer actor = requirePlayer(ctx.getSource());
		if (actor == null) {
			return 0;
		}
		Optional<IslandView> maybeIsland = resolveManageTargetIsland(actor, helper);
		if (maybeIsland.isEmpty()) {
			ctx.getSource().sendFailure(Component.literal("[Ogsky Warps] You do not have an island context to delete a warp from."));
			return 0;
		}
		IslandView island = maybeIsland.get();
		String targetName = rawName;
		if (targetName == null || targetName.isBlank()) {
			List<IslandWarp> existing = warpService.getIslandWarps(island.islandId());
			targetName = existing.size() == 1 ? existing.getFirst().slug() : WarpConfig.DEFAULT_WARP_NAME;
		}
		return reportResult(ctx.getSource(), warpService.deleteWarp(island.islandId(), actor, targetName, false));
	}

	private int executeShowMarker(
		CommandContext<CommandSourceStack> ctx,
		IslandCommandHelper helper,
		@Nullable String target
	) {
		ServerPlayer actor = requirePlayer(ctx.getSource());
		if (actor == null) {
			return 0;
		}
		Optional<IslandView> maybeIsland = helper.islandAt(actor).or(() -> helper.primaryIsland(actor));
		if (maybeIsland.isEmpty()) {
			ctx.getSource().sendFailure(Component.literal("[Ogsky Warps] You do not have an island to show warp markers for."));
			return 0;
		}
		return reportResult(
			ctx.getSource(),
			warpService.toggleMarker(ctx.getSource().getServer(), actor, maybeIsland.get().islandId(), target)
		);
	}

	private int executeEditCategory(CommandContext<CommandSourceStack> ctx, IslandCommandHelper helper, String warpName, String cat) {
		return withManageIsland(ctx, helper, island ->
			warpService.editCategory(island.islandId(), requirePlayerUnchecked(ctx), warpName, cat)
		);
	}

	private int executeEditDescription(CommandContext<CommandSourceStack> ctx, IslandCommandHelper helper, String warpName, @Nullable String desc) {
		return withManageIsland(ctx, helper, island ->
			warpService.editDescription(island.islandId(), requirePlayerUnchecked(ctx), warpName, desc)
		);
	}

	private int executeEditIconHand(CommandContext<CommandSourceStack> ctx, IslandCommandHelper helper, String warpName) {
		ServerPlayer actor = requirePlayer(ctx.getSource());
		if (actor == null) {
			return 0;
		}
		ItemStack hand = actor.getMainHandItem();
		if (hand.isEmpty()) {
			ctx.getSource().sendFailure(Component.literal("[Ogsky Warps] Hold an item in your main hand or specify an item ID / 'head'."));
			return 0;
		}
		String itemId = BuiltInRegistries.ITEM.getKey(hand.getItem()).toString();
		return withManageIsland(ctx, helper, island ->
			warpService.editIcon(island.islandId(), actor, warpName, itemId)
		);
	}

	private int executeEditIconLiteral(CommandContext<CommandSourceStack> ctx, IslandCommandHelper helper, String warpName, String rawIcon) {
		return withManageIsland(ctx, helper, island ->
			warpService.editIcon(island.islandId(), requirePlayerUnchecked(ctx), warpName, rawIcon)
		);
	}

	private int executeEditToggle(CommandContext<CommandSourceStack> ctx, IslandCommandHelper helper, String warpName, @Nullable Boolean state) {
		return withManageIsland(ctx, helper, island ->
			warpService.toggleEnabled(island.islandId(), requirePlayerUnchecked(ctx), warpName, state, false)
		);
	}

	private int executeEditMove(CommandContext<CommandSourceStack> ctx, IslandCommandHelper helper, String warpName) {
		return withManageIsland(ctx, helper, island ->
			warpService.moveWarpHere(island.islandId(), requirePlayerUnchecked(ctx), warpName)
		);
	}

	private int executeEditDefault(CommandContext<CommandSourceStack> ctx, IslandCommandHelper helper, String warpName) {
		return withManageIsland(ctx, helper, island ->
			warpService.setDefaultWarp(island.islandId(), requirePlayerUnchecked(ctx), warpName)
		);
	}

	private int withManageIsland(
		CommandContext<CommandSourceStack> ctx,
		IslandCommandHelper helper,
		java.util.function.Function<IslandView, IslandOperationResult<?>> action
	) {
		ServerPlayer actor = requirePlayer(ctx.getSource());
		if (actor == null) {
			return 0;
		}
		Optional<IslandView> island = resolveManageTargetIsland(actor, helper);
		if (island.isEmpty()) {
			ctx.getSource().sendFailure(Component.literal("[Ogsky Warps] You must be on or belong to an island you can manage."));
			return 0;
		}
		return reportResult(ctx.getSource(), action.apply(island.get()));
	}

	private Optional<IslandView> resolveManageTargetIsland(ServerPlayer actor, IslandCommandHelper helper) {
		Optional<IslandView> standing = helper.islandAt(actor);
		if (standing.isPresent() && (standing.get().roleOf(actor.getUUID()) > IslandRole.VISITOR
			|| warpService.canManageWarps(standing.get(), actor))) {
			return standing;
		}
		return helper.primaryIsland(actor).or(() -> standing);
	}

	private Optional<IslandView> resolvePlayerIsland(CommandSourceStack source, MinecraftServer server, String rawTarget) {
		UUID targetId = WarpHelper.resolveTargetUuid(server, rawTarget);
		if (targetId == null) {
			source.sendFailure(Component.literal("[Ogsky Warps] Player '" + rawTarget + "' was not found."));
			return Optional.empty();
		}
		Optional<IslandView> island = core.islands().findPrimaryIsland(targetId);
		if (island.isEmpty()) {
			source.sendFailure(Component.literal("[Ogsky Warps] " + rawTarget + " does not have a Skyblock island."));
			return Optional.empty();
		}
		return island;
	}

	private SuggestionProvider<CommandSourceStack> suggestCategories() {
		return (ctx, builder) -> {
			List<String> ids = new ArrayList<>();
			for (WarpConfig.CategorySpec spec : warpService.config().categories) {
				ids.add(spec.id);
			}
			return SharedSuggestionProvider.suggest(ids, builder);
		};
	}

	private SuggestionProvider<CommandSourceStack> suggestOnlinePlayers() {
		return (ctx, builder) -> SharedSuggestionProvider.suggest(
			ctx.getSource().getServer().getPlayerNames(),
			builder
		);
	}

	private SuggestionProvider<CommandSourceStack> suggestActorIslandWarps(IslandCommandHelper helper) {
		return (ctx, builder) -> {
			if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
				return builder.buildFuture();
			}
			Optional<IslandView> island = resolveManageTargetIsland(player, helper);
			if (island.isEmpty()) {
				return builder.buildFuture();
			}
			List<String> names = warpService.getIslandWarps(island.get().islandId()).stream()
				.map(IslandWarp::displayName)
				.toList();
			return SharedSuggestionProvider.suggest(names, builder);
		};
	}

	private SuggestionProvider<CommandSourceStack> suggestTargetPlayerWarps() {
		return (ctx, builder) -> {
			final String rawPlayer;
			try {
				rawPlayer = StringArgumentType.getString(ctx, "player");
			} catch (IllegalArgumentException ignored) {
				return builder.buildFuture();
			}
			UUID targetId = WarpHelper.resolveTargetUuid(ctx.getSource().getServer(), rawPlayer);
			if (targetId == null) {
				return builder.buildFuture();
			}
			Optional<IslandView> island = core.islands().findPrimaryIsland(targetId);
			if (island.isEmpty()) {
				return builder.buildFuture();
			}
			List<String> names = warpService.getIslandWarps(island.get().islandId()).stream()
				.filter(IslandWarp::enabled)
				.map(IslandWarp::displayName)
				.toList();
			return SharedSuggestionProvider.suggest(names, builder);
		};
	}

	private SuggestionProvider<CommandSourceStack> suggestShowTargets(IslandCommandHelper helper) {
		return (ctx, builder) -> {
			List<String> options = new ArrayList<>(List.of("off", "all"));
			if (ctx.getSource().getEntity() instanceof ServerPlayer player) {
				resolveManageTargetIsland(player, helper).ifPresent(island -> {
					for (IslandWarp warp : warpService.getIslandWarps(island.islandId())) {
						options.add(warp.displayName());
					}
				});
			}
			return SharedSuggestionProvider.suggest(options, builder);
		};
	}

	private static @Nullable ServerPlayer requirePlayer(CommandSourceStack source) {
		if (source.getEntity() instanceof ServerPlayer player) {
			return player;
		}
		source.sendFailure(Component.literal("[Ogsky Warps] Only players can execute this command."));
		return null;
	}

	private static ServerPlayer requirePlayerUnchecked(CommandContext<CommandSourceStack> ctx) {
		return (ServerPlayer) ctx.getSource().getEntity();
	}

	private static int reportResult(CommandSourceStack source, IslandOperationResult<?> result) {
		if (result.success()) {
			source.sendSuccess(
				() -> Component.literal("[Ogsky Warps] " + result.message()).withStyle(ChatFormatting.GREEN),
				false
			);
			return Command.SINGLE_SUCCESS;
		}
		source.sendFailure(Component.literal("[Ogsky Warps] " + result.message()));
		return 0;
	}

	private final class WarpSubcommand implements IslandSubcommand {
		@Override
		public String name() {
			return "warp";
		}

		@Override
		public String permissionNode() {
			return PERM_WARP;
		}

		@Override
		public void register(LiteralArgumentBuilder<CommandSourceStack> node, IslandCommandHelper helper) {
			node.then(Commands.argument("player", StringArgumentType.word())
				.suggests(suggestOnlinePlayers())
				.executes(ctx -> executeWarpTeleport(ctx, StringArgumentType.getString(ctx, "player"), null))
				.then(Commands.argument("warp", StringArgumentType.word())
					.suggests(suggestTargetPlayerWarps())
					.executes(ctx -> executeWarpTeleport(
						ctx,
						StringArgumentType.getString(ctx, "player"),
						StringArgumentType.getString(ctx, "warp")
					))));
		}
	}

	private final class WarpsSubcommand implements IslandSubcommand {
		@Override
		public String name() {
			return "warps";
		}

		@Override
		public String permissionNode() {
			return PERM_WARPS;
		}

		@Override
		public void register(LiteralArgumentBuilder<CommandSourceStack> node, IslandCommandHelper helper) {
			node.executes(ctx -> executeOpenShowcase(ctx, 1, "all", "recent", null, null, false))
				.then(Commands.literal("chat")
					.executes(ctx -> executeOpenShowcase(ctx, 1, "all", "recent", null, null, true))
					.then(Commands.argument("page", IntegerArgumentType.integer(1))
						.executes(ctx -> executeOpenShowcase(
							ctx,
							IntegerArgumentType.getInteger(ctx, "page"),
							"all",
							"recent",
							null,
							null,
							true
						))))
				.then(Commands.literal("category")
					.then(Commands.argument("category", StringArgumentType.word())
						.suggests(suggestCategories())
						.executes(ctx -> executeOpenShowcase(
							ctx,
							1,
							StringArgumentType.getString(ctx, "category"),
							"recent",
							null,
							null,
							false
						))
						.then(Commands.argument("page", IntegerArgumentType.integer(1))
							.executes(ctx -> executeOpenShowcase(
								ctx,
								IntegerArgumentType.getInteger(ctx, "page"),
								StringArgumentType.getString(ctx, "category"),
								"recent",
								null,
								null,
								true
							)))))
				.then(Commands.literal("search")
					.then(Commands.argument("query", StringArgumentType.greedyString())
						.executes(ctx -> executeOpenShowcase(
							ctx,
							1,
							"all",
							"recent",
							StringArgumentType.getString(ctx, "query"),
							null,
							false
						))))
				.then(Commands.literal("player")
					.then(Commands.argument("player", StringArgumentType.word())
						.suggests(suggestOnlinePlayers())
						.executes(ctx -> executePlayerWarpsList(ctx, StringArgumentType.getString(ctx, "player")))))
				.then(Commands.literal("mine")
					.executes(ctx -> executeOpenManage(ctx, helper)))
				.then(Commands.literal("manage")
					.executes(ctx -> executeOpenManage(ctx, helper)))
				.then(Commands.literal("show")
					.executes(ctx -> executeShowMarker(ctx, helper, null))
					.then(Commands.argument("target", StringArgumentType.word())
						.suggests(suggestShowTargets(helper))
						.executes(ctx -> executeShowMarker(ctx, helper, StringArgumentType.getString(ctx, "target")))))
				.then(Commands.literal("set")
					.then(Commands.argument("name", StringArgumentType.word())
						.executes(ctx -> executeSetWarp(ctx, helper, StringArgumentType.getString(ctx, "name"), null))
						.then(Commands.argument("category", StringArgumentType.word())
							.suggests(suggestCategories())
							.executes(ctx -> executeSetWarp(
								ctx,
								helper,
								StringArgumentType.getString(ctx, "name"),
								StringArgumentType.getString(ctx, "category")
							)))))
				.then(Commands.literal("remove")
					.then(Commands.argument("name", StringArgumentType.word())
						.suggests(suggestActorIslandWarps(helper))
						.executes(ctx -> executeDeleteWarp(ctx, helper, StringArgumentType.getString(ctx, "name")))))
				.then(Commands.literal("edit")
					.then(Commands.argument("warp", StringArgumentType.word())
						.suggests(suggestActorIslandWarps(helper))
						.then(Commands.literal("category")
							.then(Commands.argument("category", StringArgumentType.word())
								.suggests(suggestCategories())
								.executes(ctx -> executeEditCategory(
									ctx,
									helper,
									StringArgumentType.getString(ctx, "warp"),
									StringArgumentType.getString(ctx, "category")
								))))
						.then(Commands.literal("desc")
							.then(Commands.argument("description", StringArgumentType.greedyString())
								.executes(ctx -> executeEditDescription(
									ctx,
									helper,
									StringArgumentType.getString(ctx, "warp"),
									StringArgumentType.getString(ctx, "description")
								))))
						.then(Commands.literal("clear-desc")
							.executes(ctx -> executeEditDescription(
								ctx,
								helper,
								StringArgumentType.getString(ctx, "warp"),
								""
							)))
						.then(Commands.literal("icon")
							.then(Commands.literal("hand")
								.executes(ctx -> executeEditIconHand(
									ctx,
									helper,
									StringArgumentType.getString(ctx, "warp")
								)))
							.then(Commands.literal("head")
								.executes(ctx -> executeEditIconLiteral(
									ctx,
									helper,
									StringArgumentType.getString(ctx, "warp"),
									"head"
								)))
							.then(Commands.argument("item", StringArgumentType.word())
								.executes(ctx -> executeEditIconLiteral(
									ctx,
									helper,
									StringArgumentType.getString(ctx, "warp"),
									StringArgumentType.getString(ctx, "item")
								))))
						.then(Commands.literal("toggle")
							.executes(ctx -> executeEditToggle(ctx, helper, StringArgumentType.getString(ctx, "warp"), null))
							.then(Commands.argument("enabled", BoolArgumentType.bool())
								.executes(ctx -> executeEditToggle(
									ctx,
									helper,
									StringArgumentType.getString(ctx, "warp"),
									BoolArgumentType.getBool(ctx, "enabled")
								))))
						.then(Commands.literal("move")
							.executes(ctx -> executeEditMove(ctx, helper, StringArgumentType.getString(ctx, "warp"))))
						.then(Commands.literal("default")
							.executes(ctx -> executeEditDefault(ctx, helper, StringArgumentType.getString(ctx, "warp"))))));
		}
	}

	private final class SetWarpSubcommand implements IslandSubcommand {
		@Override
		public String name() {
			return "setwarp";
		}

		@Override
		public String permissionNode() {
			return PERM_SETWARP;
		}

		@Override
		public void register(LiteralArgumentBuilder<CommandSourceStack> node, IslandCommandHelper helper) {
			node.executes(ctx -> executeSetWarp(ctx, helper, null, null))
				.then(Commands.argument("name", StringArgumentType.word())
					.executes(ctx -> executeSetWarp(ctx, helper, StringArgumentType.getString(ctx, "name"), null))
					.then(Commands.argument("category", StringArgumentType.word())
						.suggests(suggestCategories())
						.executes(ctx -> executeSetWarp(
							ctx,
							helper,
							StringArgumentType.getString(ctx, "name"),
							StringArgumentType.getString(ctx, "category")
						))));
		}
	}

	private final class DelWarpSubcommand implements IslandSubcommand {
		@Override
		public String name() {
			return "delwarp";
		}

		@Override
		public String permissionNode() {
			return PERM_DELWARP;
		}

		@Override
		public void register(LiteralArgumentBuilder<CommandSourceStack> node, IslandCommandHelper helper) {
			node.executes(ctx -> executeDeleteWarp(ctx, helper, null))
				.then(Commands.argument("name", StringArgumentType.word())
					.suggests(suggestActorIslandWarps(helper))
					.executes(ctx -> executeDeleteWarp(ctx, helper, StringArgumentType.getString(ctx, "name"))));
		}
	}

	private final class AdminWarpsSubcommand implements IslandSubcommand {
		@Override
		public String name() {
			return "warps";
		}

		@Override
		public String permissionNode() {
			return PERM_ADMIN_WARPS;
		}

		@Override
		public void register(LiteralArgumentBuilder<CommandSourceStack> node, IslandCommandHelper helper) {
			node.requires(src -> helper.hasPermission(src, PERM_ADMIN_WARPS, 2))
				.then(Commands.literal("reload")
					.executes(ctx -> {
						warpService.reload(ctx.getSource().getServer());
						ctx.getSource().sendSuccess(
							() -> Component.literal("[Ogsky Warps] Reloaded config/ogsky/warps.json.").withStyle(ChatFormatting.GREEN),
							false
						);
						return Command.SINGLE_SUCCESS;
					}))
				.then(Commands.literal("inspect")
					.then(Commands.argument("player", StringArgumentType.word())
						.suggests(suggestOnlinePlayers())
						.executes(ctx -> {
							Optional<IslandView> island = resolvePlayerIsland(
								ctx.getSource(),
								ctx.getSource().getServer(),
								StringArgumentType.getString(ctx, "player")
							);
							if (island.isEmpty()) {
								return 0;
							}
							sendChatManageSummary(ctx.getSource(), island.get());
							return Command.SINGLE_SUCCESS;
						})))
				.then(Commands.literal("delete")
					.then(Commands.argument("player", StringArgumentType.word())
						.suggests(suggestOnlinePlayers())
						.then(Commands.argument("warp", StringArgumentType.word())
							.executes(ctx -> {
								Optional<IslandView> island = resolvePlayerIsland(
									ctx.getSource(),
									ctx.getSource().getServer(),
									StringArgumentType.getString(ctx, "player")
								);
								if (island.isEmpty()) {
									return 0;
								}
								return reportResult(
									ctx.getSource(),
									warpService.deleteWarp(
										island.get().islandId(),
										null,
										StringArgumentType.getString(ctx, "warp"),
										true
									)
								);
							}))))
				.then(Commands.literal("toggle")
					.then(Commands.argument("player", StringArgumentType.word())
						.suggests(suggestOnlinePlayers())
						.then(Commands.argument("warp", StringArgumentType.word())
							.then(Commands.argument("enabled", BoolArgumentType.bool())
								.executes(ctx -> {
									Optional<IslandView> island = resolvePlayerIsland(
										ctx.getSource(),
										ctx.getSource().getServer(),
										StringArgumentType.getString(ctx, "player")
									);
									if (island.isEmpty()) {
										return 0;
									}
									return reportResult(
										ctx.getSource(),
										warpService.toggleEnabled(
											island.get().islandId(),
											null,
											StringArgumentType.getString(ctx, "warp"),
											BoolArgumentType.getBool(ctx, "enabled"),
											true
										)
									);
								})))));
		}
	}
}
