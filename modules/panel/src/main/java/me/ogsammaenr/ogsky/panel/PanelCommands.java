package me.ogsammaenr.ogsky.panel;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.OgskyCoreApi;
import me.ogsammaenr.ogsky.core.api.command.IslandCommandHelper;
import me.ogsammaenr.ogsky.core.api.command.IslandCommandRegistry;
import me.ogsammaenr.ogsky.core.api.command.IslandSubcommand;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Registers {@code /is panel} ({@code gui}, {@code menu}) and {@code /is settings} ({@code setting})
 * through {@link IslandCommandRegistry}.
 */
public final class PanelCommands {
	private static final Identifier ISLAND_LOCK_FLAG = OgskyCore.id("island_lock");
	private static final SuggestionProvider<CommandSourceStack> SUGGEST_TABS = (ctx, builder) ->
		SharedSuggestionProvider.suggest(List.of("actions", "protections", "settings", "members", "blueprints"), builder);
	private static final SuggestionProvider<CommandSourceStack> SUGGEST_SETTINGS = (ctx, builder) ->
		SharedSuggestionProvider.suggest(List.of("protections", "settings"), builder);

	private final OgskyCoreApi core;
	private final PanelPreferenceRepository preferences;
	private final PanelController controller;

	public PanelCommands(
		OgskyCoreApi core,
		PanelPreferenceRepository preferences,
		PanelController controller
	) {
		this.core = Objects.requireNonNull(core, "core");
		this.preferences = Objects.requireNonNull(preferences, "preferences");
		this.controller = Objects.requireNonNull(controller, "controller");
	}

	public void registerAll(IslandCommandRegistry registry) {
		registry.registerSubcommand(new PanelSubcommand());
		registry.registerSubcommand(new SettingsSubcommand());
	}

	private int executeOpen(
		CommandSourceStack source,
		IslandCommandHelper helper,
		@Nullable PanelTab explicitTab,
		boolean persistExplicitTab
	) {
		if (!(source.getEntity() instanceof ServerPlayer player)) {
			source.sendFailure(Component.literal("[Ogsky] This command can only be used by an online player."));
			return 0;
		}
		Optional<IslandView> targetIsland = resolveTargetIsland(player, helper);
		if (targetIsland.isPresent() && targetIsland.get().isBanned(player.getUUID())) {
			source.sendFailure(Component.literal("[Ogsky] You are banned from this island."));
			return 0;
		}
		UUID islandId = targetIsland.map(IslandView::islandId).orElse(null);
		if (explicitTab != null && !core.permissions().hasPermission(player, explicitTab.requiredPermission(), 0)) {
			source.sendFailure(Component.literal("[Ogsky] You do not have permission to open " + explicitTab.displayName() + "."));
			return 0;
		}

		Optional<PanelPreferences> cached = preferences.cached(player.getUUID());
		if (cached.isPresent()) {
			openResolved(player, islandId, cached.get(), explicitTab, persistExplicitTab);
			return Command.SINGLE_SUCCESS;
		}

		preferences.loadOrCreateAsync(player.getUUID()).whenComplete((loaded, error) -> {
			MinecraftServer server = player.level().getServer();
			server.execute(() -> {
				if (player.isRemoved() || !player.isAlive()) {
					return;
				}
				PanelPreferences resolved = (error != null || loaded == null)
					? PanelPreferences.defaults(player.getUUID())
					: loaded;
				openResolved(player, islandId, resolved, explicitTab, persistExplicitTab);
			});
		});
		return Command.SINGLE_SUCCESS;
	}

	private void openResolved(
		ServerPlayer player,
		@Nullable UUID islandId,
		PanelPreferences prefs,
		@Nullable PanelTab explicitTab,
		boolean persistExplicitTab
	) {
		PanelTab resolvedTab;
		if (islandId == null) {
			resolvedTab = PanelTab.BLUEPRINTS_INFO;
		} else {
			resolvedTab = explicitTab != null ? explicitTab : prefs.preferredTab();
			if (!core.permissions().hasPermission(player, resolvedTab.requiredPermission(), 0)) {
				if (core.permissions().hasPermission(player, PanelTab.ACTIONS.requiredPermission(), 0)) {
					resolvedTab = PanelTab.ACTIONS;
				} else {
					player.sendSystemMessage(
						Component.literal("[Ogsky] You do not have permission to open this panel tab.")
							.withStyle(ChatFormatting.RED)
					);
					return;
				}
			}
			if (persistExplicitTab && explicitTab != null && explicitTab != prefs.preferredTab()) {
				preferences.saveTabAsync(player.getUUID(), explicitTab);
			}
		}
		controller.openPanel(player, islandId, resolvedTab, prefs.flagMode(), 0);
	}

	private Optional<IslandView> resolveTargetIsland(ServerPlayer player, IslandCommandHelper helper) {
		Optional<IslandView> at = helper.islandAt(player);
		if (at.isPresent() && at.get().roleOf(player.getUUID()) > IslandRole.VISITOR) {
			return at;
		}
		Optional<IslandView> primary = helper.primaryIsland(player);
		if (primary.isPresent()) {
			return primary;
		}
		if (at.isPresent()) {
			IslandView stood = at.get();
			boolean locked = core.flags().find(ISLAND_LOCK_FLAG)
				.map(def -> stood.isSettingEnabled(def.id()))
				.orElse(false);
			if (!locked || core.permissions().isBypassActive(player)) {
				return at;
			}
		}
		return Optional.empty();
	}

	private final class PanelSubcommand implements IslandSubcommand {
		@Override
		public String name() {
			return "panel";
		}

		@Override
		public List<String> aliases() {
			return List.of("gui", "menu");
		}

		@Override
		public String permissionNode() {
			return "ogsky.command.panel";
		}

		@Override
		public void register(LiteralArgumentBuilder<CommandSourceStack> node, IslandCommandHelper helper) {
			node.executes(ctx -> executeOpen(ctx.getSource(), helper, null, false))
				.then(Commands.argument("tab", StringArgumentType.word())
					.suggests(SUGGEST_TABS)
					.executes(ctx -> {
						String raw = StringArgumentType.getString(ctx, "tab");
						Optional<PanelTab> parsed = PanelTab.fromCommandArg(raw);
						if (parsed.isEmpty()) {
							ctx.getSource().sendFailure(Component.literal(
								"[Ogsky] Unknown panel tab '" + raw + "'. Use: actions, protections, settings, members, blueprints."
							));
							return 0;
						}
						return executeOpen(ctx.getSource(), helper, parsed.get(), true);
					}));
		}
	}

	private final class SettingsSubcommand implements IslandSubcommand {
		@Override
		public String name() {
			return "settings";
		}

		@Override
		public List<String> aliases() {
			return List.of("setting");
		}

		@Override
		public String permissionNode() {
			return "ogsky.command.settings";
		}

		@Override
		public void register(LiteralArgumentBuilder<CommandSourceStack> node, IslandCommandHelper helper) {
			node.executes(ctx -> executeOpen(ctx.getSource(), helper, PanelTab.PROTECTION_FLAGS, false))
				.then(Commands.argument("category", StringArgumentType.word())
					.suggests(SUGGEST_SETTINGS)
					.executes(ctx -> {
						String raw = StringArgumentType.getString(ctx, "category");
						Optional<PanelTab> parsed = PanelTab.fromCommandArg(raw);
						if (parsed.isEmpty() || (parsed.get() != PanelTab.PROTECTION_FLAGS && parsed.get() != PanelTab.SETTING_FLAGS)) {
							ctx.getSource().sendFailure(Component.literal(
								"[Ogsky] Unknown settings category '" + raw + "'. Use: protections or settings."
							));
							return 0;
						}
						return executeOpen(ctx.getSource(), helper, parsed.get(), false);
					}));
		}
	}
}
