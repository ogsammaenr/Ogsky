package me.ogsammaenr.ogsky.core.api.scan;

/**
 * Tick-budget and disk-loading options controlling an island block scan.
 */
public record IslandScanOptions(
	int chunksPerTick,
	long maxNanosPerTick,
	boolean includeUnloadedChunks,
	int maxInFlightDiskReads
) {
	public IslandScanOptions {
		if (chunksPerTick < 1) {
			throw new IllegalArgumentException("chunksPerTick must be >= 1, got: " + chunksPerTick);
		}
		if (maxNanosPerTick < 100_000L) {
			throw new IllegalArgumentException("maxNanosPerTick must be >= 100000, got: " + maxNanosPerTick);
		}
		if (maxInFlightDiskReads < 1) {
			throw new IllegalArgumentException("maxInFlightDiskReads must be >= 1, got: " + maxInFlightDiskReads);
		}
	}

	public static IslandScanOptions defaults() {
		return new IslandScanOptions(16, 4_000_000L, true, 8);
	}
}
