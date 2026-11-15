package me.ogsammaenr.ogsky.core.api.level;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable cached island level snapshot exposed across modules via {@link IslandLevelProvider}.
 */
public record IslandLevelSnapshot(
	UUID islandId,
	UUID ownerId,
	long levelValue,
	long blockPoints,
	long pointsPerLevel,
	long pointsToNextLevel,
	Instant updatedAt,
	List<BlockPointBreakdown> topBlocks
) {
	public IslandLevelSnapshot {
		Objects.requireNonNull(islandId, "islandId");
		Objects.requireNonNull(ownerId, "ownerId");
		if (pointsPerLevel < 1) {
			throw new IllegalArgumentException("pointsPerLevel must be >= 1, got: " + pointsPerLevel);
		}
		Objects.requireNonNull(updatedAt, "updatedAt");
		topBlocks = topBlocks == null ? List.of() : List.copyOf(topBlocks);
	}
}
