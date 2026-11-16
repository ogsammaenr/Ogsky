package me.ogsammaenr.ogsky.core.api.command;

import java.util.List;

/**
 * Registry allowing {@code ogsky-core} and optional modules to register Brigadier subcommands under
 * {@code /island} ({@code /is}) and {@code /isadmin} ({@code /isa}).
 */
public interface IslandCommandRegistry {
	/**
	 * Registers a player subcommand (and its aliases) under {@code /island} and {@code /is}.
	 */
	void registerSubcommand(IslandSubcommand subcommand);

	/**
	 * Registers an admin subcommand (and its aliases) under {@code /isadmin} and {@code /isa}.
	 */
	void registerAdminSubcommand(IslandSubcommand subcommand);

	/**
	 * Returns an immutable snapshot of registered player subcommands in registration order.
	 */
	List<IslandSubcommand> subcommands();

	/**
	 * Returns an immutable snapshot of registered admin subcommands in registration order.
	 */
	List<IslandSubcommand> adminSubcommands();
}
