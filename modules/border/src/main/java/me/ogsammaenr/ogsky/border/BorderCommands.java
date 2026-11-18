package me.ogsammaenr.ogsky.border;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import me.ogsammaenr.ogsky.core.api.OgskyCoreApi;
import me.ogsammaenr.ogsky.core.api.command.IslandCommandHelper;
import me.ogsammaenr.ogsky.core.api.command.IslandCommandRegistry;
import me.ogsammaenr.ogsky.core.api.command.IslandSubcommand;
import me.ogsammaenr.ogsky.core.api.island.IslandOperationResult;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Registers {@code /is border} and {@code /isadmin border} Brigadier subcommands.
 */
final class BorderCommands {
	private static final String PERM_BORDER = IslandBorderService.PERM_BORDER;
	private static final String PERM_ADMIN_BORDER = "ogsky.admin.border";
	private static final String PREFIX = "[Ogsky Border] ";

	private final OgskyCoreApi core;
	private final IslandBorderService borderService;

	BorderCommands(OgskyCoreApi core, IslandBorderService borderService) {
		this.core = core;
		this.borderService = borderService;
	}

	void registerAll(IslandCommandRegistry registry) {
		registry.registerSubcommand(new BorderSubcommand());
		registry.registerAdminSubcommand(new AdminBorderSubcommand());
	}

	private int executeToggle(CommandContext<CommandSourceStack> ctx, @Nullable Boolean explicitState) {
		ServerPlayer actor = requirePlayer(ctx.getSource());
		if (actor == null) {
			return 0;
		}
		return reportResult(ctx.getSource(), this.borderService.toggleBorder(actor, explicitState));
	}

	private int executeColor(CommandContext<CommandSourceStack> ctx, String rawColor) {
		ServerPlayer actor = requirePlayer(ctx.getSource());
		if (actor == null) {
			return 0;
		}
		Optional<BorderColor> parsed = BorderColor.parse(rawColor);
		if (parsed.isEmpty()) {
			ctx.getSource().sendFailure(Component.literal(PREFIX + "Unknown border color '" + rawColor + "'. Use: blue, green, or red."));
			return 0;
		}
		return reportResult(ctx.getSource(), this.borderService.setBorderColor(actor, parsed.get()));
	}

	private int executeStatus(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer actor = requirePlayer(ctx.getSource());
		if (actor == null) {
			return 0;
		}
		sendPlayerStatus(ctx.getSource(), actor);
		return Command.SINGLE_SUCCESS;
	}

	private void sendPlayerStatus(CommandSourceStack source, ServerPlayer target) {
		PlayerBorderPreferences prefs = this.borderService.preferencesFor(target.getUUID());
		Optional<IslandBorderService.RenderedBorderKey> active = this.borderService.activeRenderedFor(target.getUUID());
		String stateText = prefs.enabled() ? "ON" : "OFF";
		String renderingText = active
			.map(key -> "showing island bounds (" + (key.halfSize() * 2 + 1) + "x" + (key.halfSize() * 2 + 1) + ")")
			.orElse(prefs.enabled() ? "waiting for island entry" : "hidden");
		source.sendSuccess(
			() -> Component.literal(
					PREFIX + target.getScoreboardName() + ": " + stateText
						+ " | Color: " + prefs.color().displayName()
						+ " | Status: " + renderingText
				)
				.withStyle(prefs.enabled() ? ChatFormatting.GREEN : ChatFormatting.GRAY),
			false
		);
	}

	private static SuggestionProvider<CommandSourceStack> suggestColors() {
		return (ctx, builder) -> SharedSuggestionProvider.suggest(List.of("blue", "green", "red"), builder);
	}

	private static SuggestionProvider<CommandSourceStack> suggestOnlinePlayers() {
		return (ctx, builder) -> SharedSuggestionProvider.suggest(
			ctx.getSource().getServer().getPlayerNames(),
			builder
		);
	}

	private static @Nullable ServerPlayer requirePlayer(CommandSourceStack source) {
		if (source.getEntity() instanceof ServerPlayer player) {
			return player;
		}
		source.sendFailure(Component.literal(PREFIX + "Only players can execute this command."));
		return null;
	}

	private static @Nullable TargetPlayer resolveTarget(MinecraftServer server, String input) {
		ServerPlayer online = server.getPlayerList().getPlayerByName(input);
		if (online != null) {
			return new TargetPlayer(online.getUUID(), online.getScoreboardName());
		}
		Optional<NameAndId> cached = server.services().nameToIdCache().get(input);
		if (cached.isPresent()) {
			return new TargetPlayer(cached.get().id(), cached.get().name());
		}
		try {
			UUID uuid = UUID.fromString(input);
			return new TargetPlayer(uuid, input);
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private static int reportResult(CommandSourceStack source, IslandOperationResult<?> result) {
		if (result.success()) {
			source.sendSuccess(
				() -> Component.literal(PREFIX + result.message()).withStyle(ChatFormatting.GREEN),
				false
			);
			return Command.SINGLE_SUCCESS;
		}
		source.sendFailure(Component.literal(PREFIX + result.message()));
		return 0;
	}

	private record TargetPlayer(UUID uuid, String displayName) {
	}

	private final class BorderSubcommand implements IslandSubcommand {
		@Override
		public String name() {
			return "border";
		}

		@Override
		public String permissionNode() {
			return PERM_BORDER;
		}

		@Override
		public void register(LiteralArgumentBuilder<CommandSourceStack> node, IslandCommandHelper helper) {
			node.executes(ctx -> executeToggle(ctx, null))
				.then(Commands.literal("toggle")
					.executes(ctx -> executeToggle(ctx, null)))
				.then(Commands.literal("on")
					.executes(ctx -> executeToggle(ctx, true)))
				.then(Commands.literal("off")
					.executes(ctx -> executeToggle(ctx, false)))
				.then(Commands.literal("status")
					.executes(BorderCommands.this::executeStatus))
				.then(Commands.literal("color")
					.then(Commands.argument("color", StringArgumentType.word())
						.suggests(suggestColors())
						.executes(ctx -> executeColor(ctx, StringArgumentType.getString(ctx, "color")))));
		}
	}

	private final class AdminBorderSubcommand implements IslandSubcommand {
		@Override
		public String name() {
			return "border";
		}

		@Override
		public String permissionNode() {
			return PERM_ADMIN_BORDER;
		}

		@Override
		public void register(LiteralArgumentBuilder<CommandSourceStack> node, IslandCommandHelper helper) {
			node.requires(src -> helper.hasPermission(src, PERM_ADMIN_BORDER, 2))
				.then(Commands.literal("reload")
					.executes(ctx -> {
						borderService.reload(ctx.getSource().getServer());
						ctx.getSource().sendSuccess(
							() -> Component.literal(PREFIX + "Reloaded config/ogsky/border.json.").withStyle(ChatFormatting.GREEN),
							false
						);
						return Command.SINGLE_SUCCESS;
					}))
				.then(Commands.literal("inspect")
					.then(Commands.argument("player", StringArgumentType.word())
						.suggests(suggestOnlinePlayers())
						.executes(ctx -> {
							MinecraftServer server = ctx.getSource().getServer();
							String raw = StringArgumentType.getString(ctx, "player");
							TargetPlayer target = resolveTarget(server, raw);
							if (target == null) {
								ctx.getSource().sendFailure(Component.literal(PREFIX + "Player '" + raw + "' was not found."));
								return 0;
							}
							ServerPlayer online = server.getPlayerList().getPlayer(target.uuid());
							if (online != null) {
								sendPlayerStatus(ctx.getSource(), online);
							} else {
								PlayerBorderPreferences prefs = borderService.preferencesFor(target.uuid());
								ctx.getSource().sendSuccess(
									() -> Component.literal(
											PREFIX + target.displayName() + ": " + (prefs.enabled() ? "ON" : "OFF")
												+ " | Color: " + prefs.color().displayName() + " | Offline"
										)
										.withStyle(ChatFormatting.AQUA),
									false
								);
							}
							return Command.SINGLE_SUCCESS;
						})))
				.then(Commands.literal("toggle")
					.then(Commands.argument("player", StringArgumentType.word())
						.suggests(suggestOnlinePlayers())
						.executes(ctx -> executeAdminToggle(ctx, StringArgumentType.getString(ctx, "player"), null))
						.then(Commands.literal("on")
							.executes(ctx -> executeAdminToggle(ctx, StringArgumentType.getString(ctx, "player"), true)))
						.then(Commands.literal("off")
							.executes(ctx -> executeAdminToggle(ctx, StringArgumentType.getString(ctx, "player"), false)))));
		}

		private int executeAdminToggle(
			CommandContext<CommandSourceStack> ctx,
			String rawPlayer,
			@Nullable Boolean explicitState
		) {
			MinecraftServer server = ctx.getSource().getServer();
			TargetPlayer target = resolveTarget(server, rawPlayer);
			if (target == null) {
				ctx.getSource().sendFailure(Component.literal(PREFIX + "Player '" + rawPlayer + "' was not found."));
				return 0;
			}
			return reportResult(
				ctx.getSource(),
				borderService.adminSetEnabled(server, target.uuid(), target.displayName(), explicitState)
			);
		}
	}
}
