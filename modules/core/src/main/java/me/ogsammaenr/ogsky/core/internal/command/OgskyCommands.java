package me.ogsammaenr.ogsky.core.internal.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.command.IslandCommandHelper;
import me.ogsammaenr.ogsky.core.api.command.IslandCommandRegistry;
import me.ogsammaenr.ogsky.core.api.command.IslandSubcommand;
import me.ogsammaenr.ogsky.core.api.event.IslandPreDeleteEvent;
import me.ogsammaenr.ogsky.core.api.flag.FlagRegistry;
import me.ogsammaenr.ogsky.core.api.flag.FlagType;
import me.ogsammaenr.ogsky.core.api.flag.IslandFlagDefinition;
import me.ogsammaenr.ogsky.core.api.gui.GuiOpenRequest;
import me.ogsammaenr.ogsky.core.api.gui.GuiService;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import me.ogsammaenr.ogsky.core.internal.blueprint.BlueprintBundle;
import me.ogsammaenr.ogsky.core.internal.blueprint.CoreBlueprintService;
import me.ogsammaenr.ogsky.core.internal.flag.BuiltinFlags;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandService;
import me.ogsammaenr.ogsky.core.internal.island.Island;
import me.ogsammaenr.ogsky.core.internal.island.IslandLifecycleService;
import me.ogsammaenr.ogsky.core.internal.island.OperationResult;
import me.ogsammaenr.ogsky.core.internal.island.TeamService;
import me.ogsammaenr.ogsky.core.internal.permission.LuckPermsPermissionBridge;
import me.ogsammaenr.ogsky.core.internal.protection.ProtectionResolver;
import me.ogsammaenr.ogsky.core.internal.world.OgskyDimensions;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Registers Brigadier {@code /island} ({@code /is}) and {@code /isadmin} ({@code /isa}) command trees,
 * plus any module-contributed {@link IslandSubcommand}s from {@link IslandCommandRegistry}.
 */
public final class OgskyCommands implements IslandCommandHelper {
	private static final long DELETE_CONFIRM_WINDOW_SECONDS = 30L;

	private final CoreIslandService islandService;
	private final IslandLifecycleService lifecycleService;
	private final TeamService teamService;
	private final CoreBlueprintService blueprintService;
	private final FlagRegistry flagRegistry;
	private final IslandCommandRegistry commandRegistry;
	private final LuckPermsPermissionBridge permissionBridge;
	private final ProtectionResolver protection;
	private final @Nullable GuiService guiService;
	private final Consumer<MinecraftServer> reloadAction;
	private final Map<UUID, Instant> pendingDeleteConfirmations = new ConcurrentHashMap<>();

	public OgskyCommands(
		CoreIslandService islandService,
		IslandLifecycleService lifecycleService,
		TeamService teamService,
		CoreBlueprintService blueprintService,
		FlagRegistry flagRegistry,
		IslandCommandRegistry commandRegistry,
		LuckPermsPermissionBridge permissionBridge,
		ProtectionResolver protection,
		Consumer<MinecraftServer> reloadAction
	) {
		this(
			islandService,
			lifecycleService,
			teamService,
			blueprintService,
			flagRegistry,
			commandRegistry,
			permissionBridge,
			protection,
			null,
			reloadAction
		);
	}

	public OgskyCommands(
		CoreIslandService islandService,
		IslandLifecycleService lifecycleService,
		TeamService teamService,
		CoreBlueprintService blueprintService,
		FlagRegistry flagRegistry,
		IslandCommandRegistry commandRegistry,
		LuckPermsPermissionBridge permissionBridge,
		ProtectionResolver protection,
		@Nullable GuiService guiService,
		Consumer<MinecraftServer> reloadAction
	) {
		this.islandService = Objects.requireNonNull(islandService, "islandService");
		this.lifecycleService = Objects.requireNonNull(lifecycleService, "lifecycleService");
		this.teamService = Objects.requireNonNull(teamService, "teamService");
		this.blueprintService = Objects.requireNonNull(blueprintService, "blueprintService");
		this.flagRegistry = Objects.requireNonNull(flagRegistry, "flagRegistry");
		this.commandRegistry = Objects.requireNonNull(commandRegistry, "commandRegistry");
		this.permissionBridge = Objects.requireNonNull(permissionBridge, "permissionBridge");
		this.protection = Objects.requireNonNull(protection, "protection");
		this.guiService = guiService;
		this.reloadAction = Objects.requireNonNull(reloadAction, "reloadAction");
	}

	public void registerCallbacks() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerTrees(dispatcher));
	}

	public void registerTrees(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(buildIslandRoot("island"));
		dispatcher.register(buildIslandRoot("is"));
		dispatcher.register(buildAdminRoot("isadmin"));
		dispatcher.register(buildAdminRoot("isa"));
	}

	public void clearPlayerState(UUID playerUuid) {
		pendingDeleteConfirmations.remove(playerUuid);
	}

	public void clearAllState() {
		pendingDeleteConfirmations.clear();
	}

	@Override
	public boolean hasPermission(CommandSourceStack source, String permissionNode, int fallbackOpLevel) {
		return permissionBridge.hasPermission(source, permissionNode, fallbackOpLevel);
	}

	@Override
	public Optional<IslandView> primaryIsland(ServerPlayer player) {
		return islandService.findPrimaryIsland(player.getUUID());
	}

	@Override
	public Optional<IslandView> islandAt(ServerPlayer player) {
		return islandService.findAt(player.level().dimension(), player.getBlockX(), player.getBlockZ());
	}

	private LiteralArgumentBuilder<CommandSourceStack> buildIslandRoot(String rootName) {
		Set<String> reserved = new HashSet<>();
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(rootName)
			.executes(this::executeIslandRoot);

		addSub(root, reserved, literalPerm("help", "ogsky.command.help", 0)
			.executes(ctx -> sendIslandHelp(ctx.getSource())));

		addSub(root, reserved, literalPerm("create", "ogsky.command.create", 0)
			.executes(ctx -> executeCreate(ctx, null))
			.then(Commands.argument("bundle", StringArgumentType.word())
				.suggests(suggestBundles())
				.executes(ctx -> executeCreate(ctx, StringArgumentType.getString(ctx, "bundle")))));

		addSub(root, reserved, literalPerm("reset", "ogsky.command.reset", 0)
			.executes(ctx -> executeReset(ctx, null))
			.then(Commands.argument("bundle", StringArgumentType.word())
				.suggests(suggestBundles())
				.executes(ctx -> executeReset(ctx, StringArgumentType.getString(ctx, "bundle")))));

		addSub(root, reserved, literalPerm("delete", "ogsky.command.delete", 0)
			.executes(ctx -> executeDelete(ctx, false))
			.then(Commands.literal("confirm")
				.executes(ctx -> executeDelete(ctx, true))));

		addSub(root, reserved, literalPerm("home", "ogsky.command.home", 0)
			.executes(ctx -> executeHome(ctx, null))
			.then(Commands.argument("dimension", StringArgumentType.word())
				.suggests(suggestDimensions())
				.executes(ctx -> executeHome(ctx, StringArgumentType.getString(ctx, "dimension")))));

		addSub(root, reserved, literalPerm("sethome", "ogsky.command.sethome", 0)
			.executes(this::executeSetHome));

		addSub(root, reserved, literalPerm("info", "ogsky.command.info", 0)
			.executes(ctx -> executeInfo(ctx, null))
			.then(Commands.argument("player", StringArgumentType.word())
				.suggests(suggestOnlinePlayers())
				.executes(ctx -> executeInfo(ctx, StringArgumentType.getString(ctx, "player")))));

		addSub(root, reserved, literalPerm("members", "ogsky.command.members", 0)
			.executes(this::executeMembers));

		addSub(root, reserved, literalPerm("invite", "ogsky.command.invite", 0)
			.then(Commands.argument("player", StringArgumentType.word())
				.suggests(suggestOnlinePlayers())
				.executes(ctx -> executeInvite(ctx, StringArgumentType.getString(ctx, "player")))));

		addSub(root, reserved, literalPerm("accept", "ogsky.command.accept", 0)
			.executes(this::executeAccept));

		addSub(root, reserved, literalPerm("deny", "ogsky.command.deny", 0)
			.executes(this::executeDeny));

		addSub(root, reserved, literalPerm("cancel", "ogsky.command.cancel", 0)
			.then(Commands.argument("player", StringArgumentType.word())
				.suggests(suggestOnlinePlayers())
				.executes(ctx -> executeCancelInvite(ctx, StringArgumentType.getString(ctx, "player")))));

		addSub(root, reserved, literalPerm("kick", "ogsky.command.kick", 0)
			.then(Commands.argument("player", StringArgumentType.word())
				.suggests(suggestOnlinePlayers())
				.executes(ctx -> executeKick(ctx, StringArgumentType.getString(ctx, "player")))));

		addSub(root, reserved, literalPerm("leave", "ogsky.command.leave", 0)
			.executes(this::executeLeave));

		addSub(root, reserved, literalPerm("coop", "ogsky.command.coop", 0)
			.then(Commands.argument("player", StringArgumentType.word())
				.suggests(suggestOnlinePlayers())
				.executes(ctx -> executeCoop(ctx, StringArgumentType.getString(ctx, "player")))));

		addSub(root, reserved, literalPerm("uncoop", "ogsky.command.uncoop", 0)
			.then(Commands.argument("player", StringArgumentType.word())
				.suggests(suggestOnlinePlayers())
				.executes(ctx -> executeUncoop(ctx, StringArgumentType.getString(ctx, "player")))));

		addSub(root, reserved, literalPerm("trust", "ogsky.command.trust", 0)
			.then(Commands.argument("player", StringArgumentType.word())
				.suggests(suggestOnlinePlayers())
				.executes(ctx -> executeTrust(ctx, StringArgumentType.getString(ctx, "player")))));

		addSub(root, reserved, literalPerm("untrust", "ogsky.command.untrust", 0)
			.then(Commands.argument("player", StringArgumentType.word())
				.suggests(suggestOnlinePlayers())
				.executes(ctx -> executeUntrust(ctx, StringArgumentType.getString(ctx, "player")))));

		addSub(root, reserved, literalPerm("promote", "ogsky.command.promote", 0)
			.then(Commands.argument("player", StringArgumentType.word())
				.suggests(suggestOnlinePlayers())
				.executes(ctx -> executePromote(ctx, StringArgumentType.getString(ctx, "player")))));

		addSub(root, reserved, literalPerm("demote", "ogsky.command.demote", 0)
			.then(Commands.argument("player", StringArgumentType.word())
				.suggests(suggestOnlinePlayers())
				.executes(ctx -> executeDemote(ctx, StringArgumentType.getString(ctx, "player")))));

		addSub(root, reserved, literalPerm("transfer", "ogsky.command.transfer", 0)
			.then(Commands.argument("player", StringArgumentType.word())
				.suggests(suggestOnlinePlayers())
				.executes(ctx -> executeTransfer(ctx, StringArgumentType.getString(ctx, "player")))));

		addSub(root, reserved, literalPerm("ban", "ogsky.command.ban", 0)
			.then(Commands.argument("player", StringArgumentType.word())
				.suggests(suggestOnlinePlayers())
				.executes(ctx -> executeBan(ctx, StringArgumentType.getString(ctx, "player")))));

		addSub(root, reserved, literalPerm("unban", "ogsky.command.unban", 0)
			.then(Commands.argument("player", StringArgumentType.word())
				.suggests(suggestOnlinePlayers())
				.executes(ctx -> executeUnban(ctx, StringArgumentType.getString(ctx, "player")))));

		addSub(root, reserved, literalPerm("lock", "ogsky.command.lock", 0)
			.executes(ctx -> executeLock(ctx, null))
			.then(Commands.argument("locked", BoolArgumentType.bool())
				.executes(ctx -> executeLock(ctx, BoolArgumentType.getBool(ctx, "locked")))));

		addSub(root, reserved, literalPerm("flags", "ogsky.command.flags", 0)
			.executes(this::executeListFlags));

		addSub(root, reserved, literalPerm("flag", "ogsky.command.flags", 0)
			.then(Commands.argument("flag", StringArgumentType.word())
				.suggests(suggestIslandFlags())
				.then(Commands.argument("value", StringArgumentType.word())
					.suggests(suggestFlagValues())
					.executes(ctx -> executeSetFlag(
						ctx,
						StringArgumentType.getString(ctx, "flag"),
						StringArgumentType.getString(ctx, "value")
					)))));

		mountCustomSubcommands(root, reserved, commandRegistry.subcommands(), 0);
		return root;
	}

	private LiteralArgumentBuilder<CommandSourceStack> buildAdminRoot(String rootName) {
		Set<String> reserved = new HashSet<>();
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(rootName)
			.requires(permissionBridge::hasAdmin)
			.executes(ctx -> sendAdminHelp(ctx.getSource()));

		addSub(root, reserved, Commands.literal("help")
			.executes(ctx -> sendAdminHelp(ctx.getSource())));

		addSub(root, reserved, Commands.literal("reload")
			.executes(this::executeAdminReload));

		addSub(root, reserved, Commands.literal("delete")
			.then(Commands.argument("owner", StringArgumentType.word())
				.suggests(suggestOnlinePlayers())
				.executes(ctx -> executeAdminDelete(ctx, StringArgumentType.getString(ctx, "owner")))));

		addSub(root, reserved, Commands.literal("setrange")
			.then(Commands.argument("owner", StringArgumentType.word())
				.suggests(suggestOnlinePlayers())
				.then(Commands.argument("halfSize", IntegerArgumentType.integer(1))
					.executes(ctx -> executeAdminSetRange(
						ctx,
						StringArgumentType.getString(ctx, "owner"),
						IntegerArgumentType.getInteger(ctx, "halfSize")
					)))));

		addSub(root, reserved, Commands.literal("tp")
			.then(Commands.argument("owner", StringArgumentType.word())
				.suggests(suggestOnlinePlayers())
				.executes(ctx -> executeAdminTp(ctx, StringArgumentType.getString(ctx, "owner"), null))
				.then(Commands.argument("dimension", StringArgumentType.word())
					.suggests(suggestDimensions())
					.executes(ctx -> executeAdminTp(
						ctx,
						StringArgumentType.getString(ctx, "owner"),
						StringArgumentType.getString(ctx, "dimension")
					)))));

		addSub(root, reserved, Commands.literal("info")
			.then(Commands.argument("owner", StringArgumentType.word())
				.suggests(suggestOnlinePlayers())
				.executes(ctx -> executeInfo(ctx, StringArgumentType.getString(ctx, "owner")))));

		addSub(root, reserved, Commands.literal("bypass")
			.executes(this::executeAdminBypass));

		addSub(root, reserved, Commands.literal("worldflags")
			.executes(this::executeAdminWorldFlags));

		addSub(root, reserved, Commands.literal("worldflag")
			.then(Commands.argument("flag", StringArgumentType.word())
				.suggests(suggestWorldFlags())
				.then(Commands.argument("enabled", BoolArgumentType.bool())
					.executes(ctx -> executeAdminWorldFlag(
						ctx,
						StringArgumentType.getString(ctx, "flag"),
						BoolArgumentType.getBool(ctx, "enabled")
					)))));

		mountCustomSubcommands(root, reserved, commandRegistry.adminSubcommands(), 2);
		return root;
	}

	private void addSub(
		LiteralArgumentBuilder<CommandSourceStack> root,
		Set<String> reserved,
		LiteralArgumentBuilder<CommandSourceStack> child
	) {
		reserved.add(child.getLiteral().toLowerCase(Locale.ROOT));
		root.then(child);
	}

	private void mountCustomSubcommands(
		LiteralArgumentBuilder<CommandSourceStack> root,
		Set<String> reserved,
		List<IslandSubcommand> customSubcommands,
		int defaultOpFallback
	) {
		for (IslandSubcommand sub : customSubcommands) {
			String primary = sub.name().trim().toLowerCase(Locale.ROOT);
			if (!reserved.add(primary)) {
				OgskyCore.LOGGER.warn("Skipping colliding custom subcommand literal '{}'.", primary);
				continue;
			}
			root.then(buildCustomLiteral(primary, sub, defaultOpFallback));
			for (String rawAlias : sub.aliases()) {
				String alias = rawAlias.trim().toLowerCase(Locale.ROOT);
				if (!reserved.add(alias)) {
					OgskyCore.LOGGER.warn("Skipping colliding custom subcommand alias '{}'.", alias);
					continue;
				}
				root.then(buildCustomLiteral(alias, sub, defaultOpFallback));
			}
		}
	}

	private LiteralArgumentBuilder<CommandSourceStack> buildCustomLiteral(
		String literal,
		IslandSubcommand sub,
		int defaultOpFallback
	) {
		LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(literal);
		String perm = sub.permissionNode();
		if (perm != null && !perm.isBlank()) {
			node.requires(source -> permissionBridge.hasPermission(source, perm, defaultOpFallback));
		}
		sub.register(node, this);
		return node;
	}

	private LiteralArgumentBuilder<CommandSourceStack> literalPerm(String name, String permissionNode, int fallbackOp) {
		return Commands.literal(name)
			.requires(source -> permissionBridge.hasPermission(source, permissionNode, fallbackOp));
	}

	private int executeIslandRoot(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null) {
			return 0;
		}
		Optional<Island> primary = islandService.grid().findPrimaryIsland(player.getUUID());
		if (primary.isEmpty()) {
			sendInfo(ctx.getSource(), "You don't have an island yet. Use /is create to start, or /is help.");
			return Command.SINGLE_SUCCESS;
		}
		return reportResult(
			ctx.getSource(),
			lifecycleService.teleportHome(ctx.getSource().getServer(), player, primary.get(), OgskyDimensions.OVERWORLD)
		);
	}

	private int executeCreate(CommandContext<CommandSourceStack> ctx, @Nullable String requestedBundle) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null) {
			return 0;
		}
		if (requestedBundle == null || requestedBundle.isBlank()) {
			if (islandService.grid().findPrimaryIsland(player.getUUID()).isPresent()) {
				sendFailure(ctx.getSource(), "You already belong to a primary Skyblock island.");
				return 0;
			}
			if (guiService != null && guiService.isAvailable()) {
				GuiOpenRequest request = GuiOpenRequest.of(GuiService.CREATE_GALLERY_SCREEN_ID, null)
					.withState("mode", "create");
				if (guiService.openScreen(player, request)) {
					return Command.SINGLE_SUCCESS;
				}
			}
		}
		String resolvedBundleId = (requestedBundle == null || requestedBundle.isBlank())
			? blueprintService.registry().defaultBundleId()
			: requestedBundle;
		if (!hasBundlePermission(ctx.getSource(), resolvedBundleId)) {
			sendFailure(ctx.getSource(), "You do not have permission to use blueprint '" + resolvedBundleId + "'.");
			return 0;
		}
		return reportResult(
			ctx.getSource(),
			lifecycleService.createIsland(ctx.getSource().getServer(), player, player.getUUID(), requestedBundle)
		);
	}

	private int executeReset(CommandContext<CommandSourceStack> ctx, @Nullable String requestedBundle) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null) {
			return 0;
		}
		Island island = requirePrimaryIsland(ctx.getSource(), player.getUUID());
		if (island == null) {
			return 0;
		}
		if (requestedBundle == null || requestedBundle.isBlank()) {
			if (!island.ownerId().equals(player.getUUID())) {
				sendFailure(ctx.getSource(), "Only the island owner can reset this island.");
				return 0;
			}
			if (island.isPasting() || island.isDeleting()) {
				sendFailure(ctx.getSource(), "Island is already processing a paste or cleanup operation.");
				return 0;
			}
			if (guiService != null && guiService.isAvailable()) {
				GuiOpenRequest request = GuiOpenRequest.of(GuiService.CREATE_GALLERY_SCREEN_ID, island.islandId())
					.withState("mode", "reset");
				if (guiService.openScreen(player, request)) {
					return Command.SINGLE_SUCCESS;
				}
			}
		}
		String resolvedBundleId = (requestedBundle == null || requestedBundle.isBlank())
			? blueprintService.registry().defaultBundleId()
			: requestedBundle;
		if (!hasBundlePermission(ctx.getSource(), resolvedBundleId)) {
			sendFailure(ctx.getSource(), "You do not have permission to use blueprint '" + resolvedBundleId + "'.");
			return 0;
		}
		return reportResult(
			ctx.getSource(),
			lifecycleService.resetIsland(ctx.getSource().getServer(), player.getUUID(), island.islandId(), requestedBundle, false)
		);
	}

	private boolean hasBundlePermission(CommandSourceStack source, String bundleId) {
		return blueprintService.registry().find(bundleId)
			.map(bundle -> permissionBridge.hasBlueprintPermission(source, bundle.toView()))
			.orElseGet(() -> permissionBridge.hasBlueprintPermission(source, bundleId));
	}

	private int executeDelete(CommandContext<CommandSourceStack> ctx, boolean confirmed) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null) {
			return 0;
		}
		Island island = requirePrimaryIsland(ctx.getSource(), player.getUUID());
		if (island == null) {
			return 0;
		}
		if (!island.ownerId().equals(player.getUUID())) {
			sendFailure(ctx.getSource(), "Only the island owner can delete the island.");
			return 0;
		}
		if (!confirmed) {
			pendingDeleteConfirmations.put(player.getUUID(), Instant.now().plusSeconds(DELETE_CONFIRM_WINDOW_SECONDS));
			sendWarn(ctx.getSource(), "This will permanently delete your island! Run /is delete confirm within 30s to proceed.");
			return Command.SINGLE_SUCCESS;
		}
		pendingDeleteConfirmations.remove(player.getUUID());
		return reportResult(
			ctx.getSource(),
			lifecycleService.deleteIsland(
				ctx.getSource().getServer(),
				island.islandId(),
				player.getUUID(),
				IslandPreDeleteEvent.DeleteReason.PLAYER_DELETE
			)
		);
	}

	private int executeHome(CommandContext<CommandSourceStack> ctx, @Nullable String dimName) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null) {
			return 0;
		}
		Island island = requirePrimaryIsland(ctx.getSource(), player.getUUID());
		if (island == null) {
			return 0;
		}
		ResourceKey<Level> targetDim = resolveDimensionArg(dimName);
		if (targetDim == null) {
			sendFailure(ctx.getSource(), "Unknown dimension '" + dimName + "'. Use overworld, nether, or end.");
			return 0;
		}
		return reportResult(
			ctx.getSource(),
			lifecycleService.teleportHome(ctx.getSource().getServer(), player, island, targetDim)
		);
	}

	private int executeSetHome(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null) {
			return 0;
		}
		Island island = islandService.grid().findAt(player.getBlockX(), player.getBlockZ());
		if (island == null || !island.contains(player.getBlockX(), player.getBlockZ())) {
			island = requirePrimaryIsland(ctx.getSource(), player.getUUID());
			if (island == null) {
				return 0;
			}
		}
		boolean bypass = permissionBridge.hasBypass(player, BuiltinFlags.BREAK_BLOCKS);
		return reportResult(ctx.getSource(), lifecycleService.setHome(player, island, bypass));
	}

	private int executeInfo(CommandContext<CommandSourceStack> ctx, @Nullable String targetName) {
		MinecraftServer server = ctx.getSource().getServer();
		Island island = null;
		if (targetName != null && !targetName.isBlank()) {
			UUID targetId = resolveTargetUuid(server, targetName);
			if (targetId == null) {
				sendFailure(ctx.getSource(), "Player '" + targetName + "' was not found.");
				return 0;
			}
			island = islandService.grid().findPrimaryIsland(targetId).orElse(null);
		} else if (ctx.getSource().getEntity() instanceof ServerPlayer player) {
			if (OgskyDimensions.isSkyblockDimension(player.level().dimension())) {
				island = islandService.grid().findAt(player.getBlockX(), player.getBlockZ());
			}
			if (island == null) {
				island = islandService.grid().findPrimaryIsland(player.getUUID()).orElse(null);
			}
		}
		if (island == null) {
			sendFailure(ctx.getSource(), "No Skyblock island found.");
			return 0;
		}
		String ownerName = resolveDisplayName(server, island.ownerId());
		boolean locked = island.isSettingEnabledBySlot(islandService.flagSlot(BuiltinFlags.ISLAND_LOCK));
		int side = island.halfSize() * 2 + 1;
		sendHeader(ctx.getSource(), "Island Info (" + ownerName + ")");
		sendLine(ctx.getSource(), "Grid Cell: (" + island.gridX() + ", " + island.gridZ() + ") | Center: (" + island.centerX() + ", " + island.centerZ() + ")");
		sendLine(ctx.getSource(), "Size: " + side + "x" + side + " (halfSize=" + island.halfSize() + ") | Locked: " + locked);
		sendLine(ctx.getSource(), "Blueprint: " + island.bundleId() + " | Members: " + island.members().size());
		return Command.SINGLE_SUCCESS;
	}

	private int executeMembers(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null) {
			return 0;
		}
		Island island = requireContextIsland(ctx.getSource(), player);
		if (island == null) {
			return 0;
		}
		MinecraftServer server = ctx.getSource().getServer();
		sendHeader(ctx.getSource(), "Island Members (" + resolveDisplayName(server, island.ownerId()) + ")");
		island.members().stream()
			.sorted((a, b) -> Byte.compare(b.role(), a.role()))
			.forEach(entry -> {
				String memberName = resolveDisplayName(server, entry.playerUuid());
				String roleName = islandService.roleDisplayName(entry.role());
				sendLine(ctx.getSource(), "- " + memberName + " [" + roleName + "]");
			});
		return Command.SINGLE_SUCCESS;
	}

	private int executeInvite(CommandContext<CommandSourceStack> ctx, String targetName) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null) {
			return 0;
		}
		Island island = requirePrimaryIsland(ctx.getSource(), player.getUUID());
		if (island == null) {
			return 0;
		}
		UUID targetId = resolveTargetUuid(ctx.getSource().getServer(), targetName);
		if (targetId == null) {
			sendFailure(ctx.getSource(), "Player '" + targetName + "' was not found.");
			return 0;
		}
		OperationResult<TeamService.PendingInvite> result = teamService.inviteMember(island, player.getUUID(), targetId);
		if (result.success()) {
			ServerPlayer targetPlayer = ctx.getSource().getServer().getPlayerList().getPlayer(targetId);
			if (targetPlayer != null) {
				targetPlayer.sendSystemMessage(
					Component.literal("[Ogsky] " + player.getScoreboardName() + " invited you to their island! Run /is accept or /is deny.")
						.withStyle(ChatFormatting.YELLOW)
				);
			}
		}
		return reportResult(ctx.getSource(), result);
	}

	private int executeAccept(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null) {
			return 0;
		}
		return reportResult(ctx.getSource(), teamService.acceptInvite(player.getUUID()));
	}

	private int executeDeny(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null) {
			return 0;
		}
		return reportResult(ctx.getSource(), teamService.denyInvite(player.getUUID()));
	}

	private int executeCancelInvite(CommandContext<CommandSourceStack> ctx, String targetName) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null) {
			return 0;
		}
		Island island = requirePrimaryIsland(ctx.getSource(), player.getUUID());
		if (island == null) {
			return 0;
		}
		UUID targetId = resolveTargetUuid(ctx.getSource().getServer(), targetName);
		if (targetId == null) {
			sendFailure(ctx.getSource(), "Player '" + targetName + "' was not found.");
			return 0;
		}
		return reportResult(ctx.getSource(), teamService.cancelInvite(island, player.getUUID(), targetId));
	}

	private int executeKick(CommandContext<CommandSourceStack> ctx, String targetName) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null) {
			return 0;
		}
		Island island = requirePrimaryIsland(ctx.getSource(), player.getUUID());
		if (island == null) {
			return 0;
		}
		UUID targetId = resolveTargetUuid(ctx.getSource().getServer(), targetName);
		if (targetId == null) {
			sendFailure(ctx.getSource(), "Player '" + targetName + "' was not found.");
			return 0;
		}
		return reportResult(
			ctx.getSource(),
			teamService.kickMember(ctx.getSource().getServer(), island, player.getUUID(), targetId, false)
		);
	}

	private int executeLeave(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null) {
			return 0;
		}
		Island island = islandService.grid().findPrimaryIsland(player.getUUID()).orElse(null);
		if (island == null) {
			List<Island> secondary = islandService.grid().findSecondaryIslands(player.getUUID());
			if (!secondary.isEmpty()) {
				island = secondary.getFirst();
			}
		}
		if (island == null) {
			sendFailure(ctx.getSource(), "You do not belong to any island.");
			return 0;
		}
		return reportResult(ctx.getSource(), teamService.leaveIsland(ctx.getSource().getServer(), player.getUUID(), island));
	}

	private int executeCoop(CommandContext<CommandSourceStack> ctx, String targetName) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null) {
			return 0;
		}
		Island island = requirePrimaryIsland(ctx.getSource(), player.getUUID());
		if (island == null) {
			return 0;
		}
		ServerPlayer onlineTarget = ctx.getSource().getServer().getPlayerList().getPlayerByName(targetName);
		if (onlineTarget == null) {
			sendFailure(ctx.getSource(), "Target player must be online to receive a session Coop role.");
			return 0;
		}
		return reportResult(ctx.getSource(), teamService.addCoop(island, player.getUUID(), onlineTarget.getUUID(), false));
	}

	private int executeUncoop(CommandContext<CommandSourceStack> ctx, String targetName) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null) {
			return 0;
		}
		Island island = requirePrimaryIsland(ctx.getSource(), player.getUUID());
		if (island == null) {
			return 0;
		}
		UUID targetId = resolveTargetUuid(ctx.getSource().getServer(), targetName);
		if (targetId == null) {
			sendFailure(ctx.getSource(), "Player '" + targetName + "' was not found.");
			return 0;
		}
		return reportResult(
			ctx.getSource(),
			teamService.removeCoop(ctx.getSource().getServer(), island, player.getUUID(), targetId, false)
		);
	}

	private int executeTrust(CommandContext<CommandSourceStack> ctx, String targetName) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null) {
			return 0;
		}
		Island island = requirePrimaryIsland(ctx.getSource(), player.getUUID());
		if (island == null) {
			return 0;
		}
		UUID targetId = resolveTargetUuid(ctx.getSource().getServer(), targetName);
		if (targetId == null) {
			sendFailure(ctx.getSource(), "Player '" + targetName + "' was not found.");
			return 0;
		}
		return reportResult(ctx.getSource(), teamService.addTrust(island, player.getUUID(), targetId, false));
	}

	private int executeUntrust(CommandContext<CommandSourceStack> ctx, String targetName) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null) {
			return 0;
		}
		Island island = requirePrimaryIsland(ctx.getSource(), player.getUUID());
		if (island == null) {
			return 0;
		}
		UUID targetId = resolveTargetUuid(ctx.getSource().getServer(), targetName);
		if (targetId == null) {
			sendFailure(ctx.getSource(), "Player '" + targetName + "' was not found.");
			return 0;
		}
		return reportResult(
			ctx.getSource(),
			teamService.removeTrust(ctx.getSource().getServer(), island, player.getUUID(), targetId, false)
		);
	}

	private int executePromote(CommandContext<CommandSourceStack> ctx, String targetName) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null) {
			return 0;
		}
		Island island = requirePrimaryIsland(ctx.getSource(), player.getUUID());
		if (island == null) {
			return 0;
		}
		UUID targetId = resolveTargetUuid(ctx.getSource().getServer(), targetName);
		if (targetId == null) {
			sendFailure(ctx.getSource(), "Player '" + targetName + "' was not found.");
			return 0;
		}
		return reportResult(ctx.getSource(), teamService.promoteMember(island, player.getUUID(), targetId, false));
	}

	private int executeDemote(CommandContext<CommandSourceStack> ctx, String targetName) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null) {
			return 0;
		}
		Island island = requirePrimaryIsland(ctx.getSource(), player.getUUID());
		if (island == null) {
			return 0;
		}
		UUID targetId = resolveTargetUuid(ctx.getSource().getServer(), targetName);
		if (targetId == null) {
			sendFailure(ctx.getSource(), "Player '" + targetName + "' was not found.");
			return 0;
		}
		return reportResult(ctx.getSource(), teamService.demoteMember(island, player.getUUID(), targetId, false));
	}

	private int executeTransfer(CommandContext<CommandSourceStack> ctx, String targetName) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null) {
			return 0;
		}
		Island island = requirePrimaryIsland(ctx.getSource(), player.getUUID());
		if (island == null) {
			return 0;
		}
		UUID targetId = resolveTargetUuid(ctx.getSource().getServer(), targetName);
		if (targetId == null) {
			sendFailure(ctx.getSource(), "Player '" + targetName + "' was not found.");
			return 0;
		}
		return reportResult(ctx.getSource(), teamService.transferOwnership(island, player.getUUID(), targetId, false));
	}

	private int executeBan(CommandContext<CommandSourceStack> ctx, String targetName) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null) {
			return 0;
		}
		Island island = requirePrimaryIsland(ctx.getSource(), player.getUUID());
		if (island == null) {
			return 0;
		}
		UUID targetId = resolveTargetUuid(ctx.getSource().getServer(), targetName);
		if (targetId == null) {
			sendFailure(ctx.getSource(), "Player '" + targetName + "' was not found.");
			return 0;
		}
		return reportResult(
			ctx.getSource(),
			teamService.banPlayer(ctx.getSource().getServer(), island, player.getUUID(), targetId, false)
		);
	}

	private int executeUnban(CommandContext<CommandSourceStack> ctx, String targetName) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null) {
			return 0;
		}
		Island island = requirePrimaryIsland(ctx.getSource(), player.getUUID());
		if (island == null) {
			return 0;
		}
		UUID targetId = resolveTargetUuid(ctx.getSource().getServer(), targetName);
		if (targetId == null) {
			sendFailure(ctx.getSource(), "Player '" + targetName + "' was not found.");
			return 0;
		}
		return reportResult(ctx.getSource(), teamService.unbanPlayer(island, player.getUUID(), targetId, false));
	}

	private int executeLock(CommandContext<CommandSourceStack> ctx, @Nullable Boolean explicit) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null) {
			return 0;
		}
		Island island = requirePrimaryIsland(ctx.getSource(), player.getUUID());
		if (island == null) {
			return 0;
		}
		boolean current = island.isSettingEnabledBySlot(islandService.flagSlot(BuiltinFlags.ISLAND_LOCK));
		boolean next = explicit != null ? explicit : !current;
		return reportResult(
			ctx.getSource(),
			lifecycleService.setLock(ctx.getSource().getServer(), player.getUUID(), island, next, false)
		);
	}

	private int executeListFlags(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null) {
			return 0;
		}
		Island island = requireContextIsland(ctx.getSource(), player);
		if (island == null) {
			return 0;
		}
		sendHeader(ctx.getSource(), "Island Flags");
		for (IslandFlagDefinition def : flagRegistry.listByType(FlagType.PROTECTION)) {
			byte rank = island.protectionRank(def.id());
			sendLine(ctx.getSource(), "[P] " + def.id().getPath() + " -> " + islandService.roleDisplayName(rank) + " (" + rank + ")");
		}
		for (IslandFlagDefinition def : flagRegistry.listByType(FlagType.SETTING)) {
			boolean val = island.isSettingEnabled(def.id());
			sendLine(ctx.getSource(), "[S] " + def.id().getPath() + " -> " + val);
		}
		return Command.SINGLE_SUCCESS;
	}

	private int executeSetFlag(CommandContext<CommandSourceStack> ctx, String rawFlag, String rawValue) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null) {
			return 0;
		}
		Island island = requirePrimaryIsland(ctx.getSource(), player.getUUID());
		if (island == null) {
			return 0;
		}
		Identifier flagId = resolveFlagId(rawFlag);
		if (flagId == null) {
			sendFailure(ctx.getSource(), "Invalid flag identifier: " + rawFlag);
			return 0;
		}
		Optional<IslandFlagDefinition> def = flagRegistry.find(flagId);
		if (def.isEmpty() || def.get().type() == FlagType.WORLD_SETTING) {
			sendFailure(ctx.getSource(), "Unknown island flag: " + rawFlag);
			return 0;
		}
		if (def.get().type() == FlagType.PROTECTION) {
			byte rank;
			try {
				rank = IslandRole.parseThreshold(rawValue);
			} catch (IllegalArgumentException e) {
				sendFailure(ctx.getSource(), "Invalid rank '" + rawValue + "'. Use visitor..owner or 0..5.");
				return 0;
			}
			return reportResult(
				ctx.getSource(),
				lifecycleService.setProtectionRank(player.getUUID(), island, flagId, rank, false)
			);
		}
		Boolean parsedBool = parseBooleanValue(rawValue);
		if (parsedBool == null) {
			sendFailure(ctx.getSource(), "Invalid boolean '" + rawValue + "'. Use true or false.");
			return 0;
		}
		return reportResult(
			ctx.getSource(),
			lifecycleService.setSettingFlag(ctx.getSource().getServer(), player.getUUID(), island, flagId, parsedBool, false)
		);
	}

	private int executeAdminReload(CommandContext<CommandSourceStack> ctx) {
		reloadAction.accept(ctx.getSource().getServer());
		sendSuccess(ctx.getSource(), "Reloaded core.json configuration and blueprint registry.");
		return Command.SINGLE_SUCCESS;
	}

	private int executeAdminDelete(CommandContext<CommandSourceStack> ctx, String ownerArg) {
		UUID ownerId = resolveTargetUuid(ctx.getSource().getServer(), ownerArg);
		if (ownerId == null) {
			sendFailure(ctx.getSource(), "Player '" + ownerArg + "' was not found.");
			return 0;
		}
		Optional<Island> island = islandService.grid().findPrimaryIsland(ownerId);
		if (island.isEmpty()) {
			sendFailure(ctx.getSource(), ownerArg + " does not have an island.");
			return 0;
		}
		UUID actorId = ctx.getSource().getEntity() != null ? ctx.getSource().getEntity().getUUID() : null;
		return reportResult(
			ctx.getSource(),
			lifecycleService.deleteIsland(ctx.getSource().getServer(), island.get().islandId(), actorId, IslandPreDeleteEvent.DeleteReason.ADMIN)
		);
	}

	private int executeAdminSetRange(CommandContext<CommandSourceStack> ctx, String ownerArg, int halfSize) {
		UUID ownerId = resolveTargetUuid(ctx.getSource().getServer(), ownerArg);
		if (ownerId == null) {
			sendFailure(ctx.getSource(), "Player '" + ownerArg + "' was not found.");
			return 0;
		}
		Optional<Island> island = islandService.grid().findPrimaryIsland(ownerId);
		if (island.isEmpty()) {
			sendFailure(ctx.getSource(), ownerArg + " does not have an island.");
			return 0;
		}
		return reportResult(ctx.getSource(), lifecycleService.resizeIsland(island.get().islandId(), halfSize));
	}

	private int executeAdminTp(CommandContext<CommandSourceStack> ctx, String ownerArg, @Nullable String dimName) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null) {
			return 0;
		}
		UUID ownerId = resolveTargetUuid(ctx.getSource().getServer(), ownerArg);
		if (ownerId == null) {
			sendFailure(ctx.getSource(), "Player '" + ownerArg + "' was not found.");
			return 0;
		}
		Optional<Island> island = islandService.grid().findPrimaryIsland(ownerId);
		if (island.isEmpty()) {
			sendFailure(ctx.getSource(), ownerArg + " does not have an island.");
			return 0;
		}
		ResourceKey<Level> targetDim = resolveDimensionArg(dimName);
		if (targetDim == null) {
			sendFailure(ctx.getSource(), "Unknown dimension '" + dimName + "'.");
			return 0;
		}
		return reportResult(ctx.getSource(), lifecycleService.teleportHome(ctx.getSource().getServer(), player, island.get(), targetDim));
	}

	private int executeAdminBypass(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = requirePlayer(ctx.getSource());
		if (player == null) {
			return 0;
		}
		boolean active = permissionBridge.toggleBypass(player);
		sendSuccess(ctx.getSource(), "Admin protection bypass is now " + (active ? "ENABLED" : "DISABLED") + ".");
		return Command.SINGLE_SUCCESS;
	}

	private int executeAdminWorldFlags(CommandContext<CommandSourceStack> ctx) {
		sendHeader(ctx.getSource(), "World Setting Flags");
		for (IslandFlagDefinition def : flagRegistry.listByType(FlagType.WORLD_SETTING)) {
			sendLine(ctx.getSource(), def.id().getPath() + " -> " + islandService.isWorldSettingEnabled(def.id()));
		}
		return Command.SINGLE_SUCCESS;
	}

	private int executeAdminWorldFlag(CommandContext<CommandSourceStack> ctx, String rawFlag, boolean enabled) {
		Identifier flagId = resolveFlagId(rawFlag);
		if (flagId == null) {
			sendFailure(ctx.getSource(), "Invalid flag identifier: " + rawFlag);
			return 0;
		}
		return reportResult(ctx.getSource(), lifecycleService.setWorldSetting(flagId, enabled));
	}

	private int sendIslandHelp(CommandSourceStack source) {
		sendHeader(source, "Ogsky Island Commands");
		sendLine(source, "/is create [bundle], reset [bundle], delete [confirm]");
		sendLine(source, "/is home [overworld|nether|end], sethome, info [player]");
		sendLine(source, "/is invite|accept|deny|cancel|kick|leave|coop|uncoop|trust|untrust");
		sendLine(source, "/is promote|demote|transfer|ban|unban|lock [true|false]|flags|flag <id> <val>");
		return Command.SINGLE_SUCCESS;
	}

	private int sendAdminHelp(CommandSourceStack source) {
		sendHeader(source, "Ogsky Admin Commands");
		sendLine(source, "/isadmin reload | bypass | info <owner>");
		sendLine(source, "/isadmin delete <owner> | setrange <owner> <halfSize> | tp <owner> [dim]");
		sendLine(source, "/isadmin worldflags | worldflag <flagId> <true|false>");
		return Command.SINGLE_SUCCESS;
	}

	private @Nullable ServerPlayer requirePlayer(CommandSourceStack source) {
		if (source.getEntity() instanceof ServerPlayer player) {
			return player;
		}
		sendFailure(source, "Only players can run this command.");
		return null;
	}

	private @Nullable Island requirePrimaryIsland(CommandSourceStack source, UUID playerId) {
		Optional<Island> island = islandService.grid().findPrimaryIsland(playerId);
		if (island.isEmpty()) {
			sendFailure(source, "You do not have a primary Skyblock island.");
			return null;
		}
		return island.get();
	}

	private @Nullable Island requireContextIsland(CommandSourceStack source, ServerPlayer player) {
		if (OgskyDimensions.isSkyblockDimension(player.level().dimension())) {
			Island at = islandService.grid().findAt(player.getBlockX(), player.getBlockZ());
			if (at != null && at.roleOf(player.getUUID()) > IslandRole.VISITOR) {
				return at;
			}
		}
		Island primary = islandService.grid().findPrimaryIsland(player.getUUID()).orElse(null);
		if (primary != null) {
			return primary;
		}
		if (OgskyDimensions.isSkyblockDimension(player.level().dimension())) {
			Island at = islandService.grid().findAt(player.getBlockX(), player.getBlockZ());
			if (at != null) {
				return at;
			}
		}
		sendFailure(source, "No Skyblock island found.");
		return null;
	}

	private static @Nullable UUID resolveTargetUuid(MinecraftServer server, String input) {
		ServerPlayer online = server.getPlayerList().getPlayerByName(input);
		if (online != null) {
			return online.getUUID();
		}
		Optional<NameAndId> cached = server.services().nameToIdCache().get(input);
		if (cached.isPresent()) {
			return cached.get().id();
		}
		try {
			return UUID.fromString(input);
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private static String resolveDisplayName(MinecraftServer server, UUID uuid) {
		ServerPlayer online = server.getPlayerList().getPlayer(uuid);
		if (online != null) {
			return online.getScoreboardName();
		}
		return server.services().nameToIdCache().get(uuid)
			.map(NameAndId::name)
			.orElse(uuid.toString());
	}

	private static @Nullable ResourceKey<Level> resolveDimensionArg(@Nullable String arg) {
		if (arg == null || arg.isBlank()) {
			return OgskyDimensions.OVERWORLD;
		}
		return switch (arg.trim().toLowerCase(Locale.ROOT)) {
			case "overworld", "ogsky:overworld" -> OgskyDimensions.OVERWORLD;
			case "nether", "ogsky:nether" -> OgskyDimensions.NETHER;
			case "end", "ogsky:end" -> OgskyDimensions.END;
			default -> null;
		};
	}

	private @Nullable Identifier resolveFlagId(String raw) {
		String trimmed = raw.trim().toLowerCase(Locale.ROOT);
		if (trimmed.isEmpty()) {
			return null;
		}
		if (trimmed.contains(":")) {
			return Identifier.tryParse(trimmed);
		}
		Identifier coreId = Identifier.tryBuild(OgskyCore.MOD_ID, trimmed);
		if (coreId != null && flagRegistry.find(coreId).isPresent()) {
			return coreId;
		}
		Identifier matched = null;
		for (IslandFlagDefinition def : flagRegistry.listAll()) {
			if (def.id().getPath().equals(trimmed)) {
				if (matched != null) {
					return null; // ambiguous path; caller must qualify with namespace:path
				}
				matched = def.id();
			}
		}
		return matched != null ? matched : coreId;
	}

	private static @Nullable Boolean parseBooleanValue(String raw) {
		return switch (raw.trim().toLowerCase(Locale.ROOT)) {
			case "true", "on", "yes", "enable", "enabled" -> Boolean.TRUE;
			case "false", "off", "no", "disable", "disabled" -> Boolean.FALSE;
			default -> null;
		};
	}

	private int reportResult(CommandSourceStack source, OperationResult<?> result) {
		if (result.success()) {
			sendSuccess(source, result.message());
			return Command.SINGLE_SUCCESS;
		}
		sendFailure(source, result.message());
		return 0;
	}

	private SuggestionProvider<CommandSourceStack> suggestOnlinePlayers() {
		return (ctx, builder) -> SharedSuggestionProvider.suggest(ctx.getSource().getServer().getPlayerNames(), builder);
	}

	private SuggestionProvider<CommandSourceStack> suggestBundles() {
		return (ctx, builder) -> SharedSuggestionProvider.suggest(
			blueprintService.registry().listAll().stream().map(BlueprintBundle::id),
			builder
		);
	}

	private static SuggestionProvider<CommandSourceStack> suggestDimensions() {
		return (ctx, builder) -> SharedSuggestionProvider.suggest(List.of("overworld", "nether", "end"), builder);
	}

	private SuggestionProvider<CommandSourceStack> suggestIslandFlags() {
		return (ctx, builder) -> {
			List<String> names = flagRegistry.listAll().stream()
				.filter(def -> def.type() != FlagType.WORLD_SETTING)
				.map(def -> def.id().getPath())
				.toList();
			return SharedSuggestionProvider.suggest(names, builder);
		};
	}

	private SuggestionProvider<CommandSourceStack> suggestWorldFlags() {
		return (ctx, builder) -> {
			List<String> names = flagRegistry.listByType(FlagType.WORLD_SETTING).stream()
				.map(def -> def.id().getPath())
				.toList();
			return SharedSuggestionProvider.suggest(names, builder);
		};
	}

	private SuggestionProvider<CommandSourceStack> suggestFlagValues() {
		return (ctx, builder) -> SharedSuggestionProvider.suggest(
			List.of("visitor", "coop", "trusted", "member", "admin", "owner", "true", "false"),
			builder
		);
	}

	private static void sendHeader(CommandSourceStack source, String text) {
		source.sendSuccess(() -> Component.literal("=== " + text + " ===").withStyle(ChatFormatting.GOLD), false);
	}

	private static void sendInfo(CommandSourceStack source, String text) {
		source.sendSuccess(() -> Component.literal("[Ogsky] " + text).withStyle(ChatFormatting.AQUA), false);
	}

	private static void sendSuccess(CommandSourceStack source, String text) {
		source.sendSuccess(() -> Component.literal("[Ogsky] " + text).withStyle(ChatFormatting.GREEN), false);
	}

	private static void sendWarn(CommandSourceStack source, String text) {
		source.sendSuccess(() -> Component.literal("[Ogsky] " + text).withStyle(ChatFormatting.YELLOW), false);
	}

	private static void sendLine(CommandSourceStack source, String text) {
		source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GRAY), false);
	}

	private static void sendFailure(CommandSourceStack source, String text) {
		source.sendFailure(Component.literal("[Ogsky] " + text));
	}
}
