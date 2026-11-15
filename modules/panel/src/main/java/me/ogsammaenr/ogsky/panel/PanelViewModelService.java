package me.ogsammaenr.ogsky.panel;

import me.ogsammaenr.ogsky.core.api.OgskyCoreApi;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Builds {@link PanelSnapshot} instances dynamically from actions contributed by active Ogsky modules.
 */
public final class PanelViewModelService {
	private final OgskyCoreApi core;

	public PanelViewModelService(OgskyCoreApi core) {
		this.core = Objects.requireNonNull(core, "core");
	}

	public PanelSnapshot buildSnapshot(UUID islandId) {
		List<PanelSnapshot.PanelEntry> entries = core.actions().listOrdered().stream()
			.map(PanelSnapshot.PanelEntry::fromDescriptor)
			.toList();
		return new PanelSnapshot(islandId, entries);
	}
}
