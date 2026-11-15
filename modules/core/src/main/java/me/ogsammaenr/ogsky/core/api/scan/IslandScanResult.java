package me.ogsammaenr.ogsky.core.api.scan;

import java.util.Objects;
import java.util.UUID;

/**
 * Summary telemetry emitted when a tick-budgeted island block scan completes or aborts.
 */
public record IslandScanResult(
	UUID islandId,
	int plannedChunks,
	int loadedChunksScanned,
	int diskChunksScanned,
	int skippedUnbornChunks,
	long nonAirBlocksCounted,
	boolean aborted,
	String abortReason
) {
	public IslandScanResult {
		Objects.requireNonNull(islandId, "islandId");
		abortReason = abortReason == null ? "" : abortReason;
	}

	public int totalChunksScanned() {
		return loadedChunksScanned + diskChunksScanned;
	}
}
