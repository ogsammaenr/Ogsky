package me.ogsammaenr.ogsky.core.api.scan;

import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Shared tick-budgeted block and entity scanner across an island's active Skyblock dimensions.
 * Consumers (such as {@code ogsky-level} and {@code ogsky-limits}) receive callbacks on the server tick thread.
 */
public interface IslandScanService {
	/**
	 * Starts a chunk-budgeted scan of blocks across {@code islandId}'s initialized dimensions using {@code options}.
	 * Reads loaded chunk sections directly and optionally queues non-blocking disk chunk reads without generating void chunks.
	 */
	CompletableFuture<IslandScanResult> scanIsland(
		MinecraftServer server,
		UUID islandId,
		IslandScanOptions options,
		BlockStateCountConsumer consumer
	);

	/**
	 * Starts a block scan using {@link IslandScanOptions#defaults()}.
	 */
	default CompletableFuture<IslandScanResult> scanIsland(
		MinecraftServer server,
		UUID islandId,
		BlockStateCountConsumer consumer
	) {
		return scanIsland(server, islandId, IslandScanOptions.defaults(), consumer);
	}

	/**
	 * Starts a chunk-budgeted scan counting both non-air block states and (when non-null) entities across loaded and disk chunks.
	 */
	default CompletableFuture<IslandScanResult> scanIslandWithEntities(
		MinecraftServer server,
		UUID islandId,
		IslandScanOptions options,
		BlockStateCountConsumer blockConsumer,
		@Nullable EntityTypeCountConsumer entityConsumer
	) {
		return scanIsland(server, islandId, options, blockConsumer);
	}

	/**
	 * Cancels all in-flight scans targeting {@code islandId}; completed futures report {@code aborted=true}.
	 */
	void cancelScansForIsland(UUID islandId);
}
