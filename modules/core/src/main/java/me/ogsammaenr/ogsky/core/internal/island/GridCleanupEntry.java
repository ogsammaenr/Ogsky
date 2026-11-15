package me.ogsammaenr.ogsky.core.internal.island;

import java.time.Instant;

/**
 * Persisted grid cell pending chunk cleanup across Skyblock dimensions before cell reuse.
 */
public record GridCleanupEntry(
	int gridX,
	int gridZ,
	int minX,
	int maxX,
	int minZ,
	int maxZ,
	int cursorChunkX,
	int cursorChunkZ,
	Instant createdAt
) {
	public long packedCell() {
		return PackedGridMap.pack(gridX, gridZ);
	}
}
