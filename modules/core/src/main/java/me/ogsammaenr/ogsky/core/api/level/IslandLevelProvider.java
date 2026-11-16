package me.ogsammaenr.ogsky.core.api.level;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Optional read-only capability registered in {@code core.services()} when {@code ogsky-level} is loaded.
 */
public interface IslandLevelProvider {
	/**
	 * Returns the latest cached level snapshot for {@code islandId}, or empty if not yet calculated.
	 */
	Optional<IslandLevelSnapshot> findSnapshot(UUID islandId);

	/**
	 * Returns the 1-based leaderboard rank for {@code islandId}, or empty if unranked.
	 */
	OptionalInt rankOf(UUID islandId);

	/**
	 * Returns up to {@code limit} top-ranked islands ordered by level descending then points descending.
	 */
	List<IslandLevelSnapshot> topIslands(int limit);

	/**
	 * Returns the total number of currently ranked islands held in memory.
	 */
	int rankedIslandCount();

	/**
	 * Returns {@code true} while an asynchronous level scan for {@code islandId} is actively running.
	 */
	boolean isScanInProgress(UUID islandId);
}
