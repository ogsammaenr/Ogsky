package me.ogsammaenr.ogsky.core.api.island;

import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read and hot-path query API for Skyblock islands exposed by {@code ogsky-core}.
 */
public interface IslandService {
	Optional<IslandView> findById(UUID islandId);

	Optional<IslandView> findPrimaryIsland(UUID playerUuid);

	List<IslandView> findSecondaryIslands(UUID playerUuid);

	Optional<IslandView> findAt(ResourceKey<Level> dimension, int x, int z);

	Optional<IslandView> findByGridCell(int gridX, int gridZ);

	int islandCount();

	int lockedCellSize();

	boolean isWorldSettingEnabled(Identifier flagId);

	boolean isProtectionAllowed(
		ResourceKey<Level> dimension,
		int x,
		int z,
		UUID playerUuid,
		Identifier flagId,
		boolean adminBypass
	);

	boolean isSettingEnabledAt(ResourceKey<Level> dimension, int x, int z, Identifier flagId);

	String roleDisplayName(byte role);
}
