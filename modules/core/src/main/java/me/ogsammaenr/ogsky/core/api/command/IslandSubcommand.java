package me.ogsammaenr.ogsky.core.api.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;

import java.util.List;

/**
 * Subcommand node contributed under {@code /island} ({@code /is}) or {@code /isadmin}.
 */
public interface IslandSubcommand {
	String name();

	default List<String> aliases() {
		return List.of();
	}

	/**
	 * Optional permission node (e.g. {@code ogsky.command.panel}). Empty means available to all players.
	 */
	default String permissionNode() {
		return "";
	}

	/**
	 * Builds the Brigadier arguments and execution callbacks onto {@code node}.
	 */
	void register(LiteralArgumentBuilder<CommandSourceStack> node, IslandCommandHelper helper);
}
