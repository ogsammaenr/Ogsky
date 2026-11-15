package me.ogsammaenr.ogsky.core.api.command;

import java.util.List;

/**
 * Registry allowing {@code ogsky-core} and optional modules to register Brigadier subcommands under
 * {@code /island} ({@code /is}) and {@code /isadmin}.
 */
public interface IslandCommandRegistry {
	void registerSubcommand(IslandSubcommand subcommand);

	void registerAdminSubcommand(IslandSubcommand subcommand);

	List<IslandSubcommand> subcommands();

	List<IslandSubcommand> adminSubcommands();
}
