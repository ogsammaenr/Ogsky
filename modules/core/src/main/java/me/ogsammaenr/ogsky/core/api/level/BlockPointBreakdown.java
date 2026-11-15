package me.ogsammaenr.ogsky.core.api.level;

import net.minecraft.resources.Identifier;

import java.util.Objects;

/**
 * Per-block contribution summary recorded during an island level calculation.
 */
public record BlockPointBreakdown(
	Identifier blockId,
	int rawCount,
	int countedForPoints,
	long pointsEarned
) {
	public BlockPointBreakdown {
		Objects.requireNonNull(blockId, "blockId");
		if (rawCount < 0 || countedForPoints < 0 || pointsEarned < 0) {
			throw new IllegalArgumentException("Breakdown counts and points must be non-negative.");
		}
	}
}
