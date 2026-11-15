package me.ogsammaenr.ogsky.core.api.scan;

import net.minecraft.server.MinecraftServer;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Shared tick-budgeted block scanner across an island's active Skyblock dimensions.
 * Consumers (such as {@code ogsky-level} and future {@code ogsky-limits}) receive callbacks on the server tick thread.
 */
public interface IslandScanService {
	CompletableFuture<IslandScanResult> scanIsland(
		MinecraftServer server,
		UUID islandId,
		IslandScanOptions options,
		BlockStateCountConsumer consumer
	);

	default CompletableFuture<IslandScanResult> scanIsland(
		MinecraftServer server,
		UUID islandId,
		BlockStateCountConsumer consumer
	) {
		return scanIsland(server, islandId, IslandScanOptions.defaults(), consumer);
	}

	void cancelScansForIsland(UUID islandId);
}
