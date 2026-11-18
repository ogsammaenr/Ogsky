package me.ogsammaenr.ogsky.border;

import me.ogsammaenr.ogsky.core.api.OgskyCoreApi;
import me.ogsammaenr.ogsky.core.api.action.IslandActionDescriptor;
import me.ogsammaenr.ogsky.core.api.action.IslandActionPresentation;
import me.ogsammaenr.ogsky.core.api.gui.GuiButton;
import me.ogsammaenr.ogsky.core.api.island.IslandOperationResult;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Registers {@code ogsky-border} panel action and summary line via UI-agnostic
 * {@code core.actions()} and {@code core.guis()}.
 */
final class BorderGuiRegistrar {
	static final Identifier SUMMARY_CONTRIBUTOR_ID = Identifier.fromNamespaceAndPath("ogsky_border", "summary");
	static final Identifier ACTION_TOGGLE_BORDER = Identifier.fromNamespaceAndPath("ogsky_border", "toggle_border");
	private static final String PREFIX = "[Ogsky Border] ";

	private final OgskyCoreApi core;
	private final IslandBorderService borderService;

	BorderGuiRegistrar(OgskyCoreApi core, IslandBorderService borderService) {
		this.core = core;
		this.borderService = borderService;
	}

	void registerAll() {
		// 1. Slot 8 Island Summary line on /is panel
		this.core.guis().registerSummaryContributor(SUMMARY_CONTRIBUTOR_ID, 50, ctx -> {
			if (!this.borderService.config().enabled) {
				return List.of();
			}
			PlayerBorderPreferences prefs = this.borderService.preferencesFor(ctx.viewerUuid());
			if (prefs.enabled()) {
				return List.of(
					GuiButton.nonItalic("Border: ON (" + prefs.color().displayName() + ")", ChatFormatting.AQUA)
				);
			}
			return List.of(
				GuiButton.nonItalic("Border: OFF (/is border)", ChatFormatting.GRAY)
			);
		});

		// 2. Single-click Border toggle button in /is panel Actions tab
		IslandActionDescriptor descriptor = new IslandActionDescriptor(
			ACTION_TOGGLE_BORDER,
			"Island Border",
			"Toggle a visible world border along your island boundaries.",
			"minecraft:light_blue_stained_glass",
			19,
			false,
			ctx -> executeToggleAction(ctx.actor())
		);
		this.core.actions().register(descriptor, (viewerUuid, island) -> {
			BorderConfig cfg = this.borderService.config();
			PlayerBorderPreferences prefs = this.borderService.preferencesFor(viewerUuid);
			if (!cfg.enabled) {
				return IslandActionPresentation.toggle(
					"Island Border: DISABLED",
					"Island border display is currently disabled by server config.",
					"minecraft:barrier",
					false,
					"Disabled by Server",
					"OFF"
				);
			}
			if (prefs.enabled()) {
				String desc = cfg.allowColorSelection
					? "Color: " + prefs.color().displayName() + " • Click to hide border (/is border color <blue|green|red>)."
					: "Color: " + prefs.color().displayName() + " • Click to hide border.";
				return IslandActionPresentation.toggle(
					"Island Border: ON",
					desc,
					prefs.color().iconItemId(),
					true,
					"Enabled (" + prefs.color().displayName() + ")",
					"ON"
				);
			}
			String offDesc = cfg.allowColorSelection
				? "Click to show a visible border along island bounds (/is border)."
				: "Click to show a visible border along island bounds.";
			return IslandActionPresentation.toggle(
				"Island Border: OFF",
				offDesc,
				"minecraft:gray_stained_glass",
				false,
				"Disabled",
				"OFF"
			);
		});
	}

	private void executeToggleAction(ServerPlayer actor) {
		IslandOperationResult<PlayerBorderPreferences> result = this.borderService.toggleBorder(actor, null, false);
		actor.sendSystemMessage(
			Component.literal(PREFIX + result.message())
				.withStyle(result.success() ? ChatFormatting.GREEN : ChatFormatting.RED)
		);
	}
}
