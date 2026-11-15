package me.ogsammaenr.ogsky.core.api.level;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Optional read-only capability registered in {@code core.services()} when {@code ogsky-level} is loaded.
 */
public interface IslandLevelProvider {
	Optional<IslandLevelSnapshot> findSnapshot(UUID islandId);

	OptionalInt rankOf(UUID islandId);

	List<IslandLevelSnapshot> topIslands(int limit);

	int rankedIslandCount();

	boolean isScanInProgress(UUID islandId);
}
