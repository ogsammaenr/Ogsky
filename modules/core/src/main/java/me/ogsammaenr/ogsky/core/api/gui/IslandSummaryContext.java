package me.ogsammaenr.ogsky.core.api.gui;

import java.util.Objects;
import java.util.UUID;

/**
 * Context passed to {@link IslandSummaryContributor}s when rendering an island overview card.
 */
public record IslandSummaryContext(
	UUID islandId,
	UUID viewerUuid,
	byte viewerRole,
	boolean viewerAdminBypass
) {
	public IslandSummaryContext {
		Objects.requireNonNull(islandId, "islandId");
		Objects.requireNonNull(viewerUuid, "viewerUuid");
	}
}
