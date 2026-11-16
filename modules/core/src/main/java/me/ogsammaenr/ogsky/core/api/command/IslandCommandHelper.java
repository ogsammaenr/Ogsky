package me.ogsammaenr.ogsky.core.api.command;

import me.ogsammaenr.ogsky.core.api.island.IslandView;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Helper context passed to registered {@link IslandSubcommand}s during Brigadier tree construction and execution.
 */
public interface IslandCommandHelper {
	/**
	 * Evaluates {@code permissionNode} via LuckPerms / Fabric Permissions API, falling back to {@code fallbackOpLevel}.
	 */
	boolean hasPermission(CommandSourceStack source, String permissionNode, int fallbackOpLevel);

	/**
	 * Resolves {@code player}'s single primary island ({@code MEMBER..OWNER}) in O(1) RAM lookup.
	 */
	Optional<IslandView> primaryIsland(ServerPlayer player);

	/**
	 * Resolves the Skyblock island containing {@code player}'s current block position, if any.
	 */
	Optional<IslandView> islandAt(ServerPlayer player);
}
