package me.ogsammaenr.ogsky.core.api.command;

import me.ogsammaenr.ogsky.core.api.island.IslandView;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Helper context passed to registered {@link IslandSubcommand}s during Brigadier tree construction and execution.
 */
public interface IslandCommandHelper {
	boolean hasPermission(CommandSourceStack source, String permissionNode, int fallbackOpLevel);

	Optional<IslandView> primaryIsland(ServerPlayer player);

	Optional<IslandView> islandAt(ServerPlayer player);
}
