package me.ogsammaenr.ogsky.panel;

import me.ogsammaenr.ogsky.core.api.action.IslandActionDescriptor;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * UI-agnostic snapshot of the island control panel for a given island.
 * Can be rendered as a server-side ChestMenu or sent to an optional client UI module.
 */
public record PanelSnapshot(UUID islandId, List<PanelEntry> entries) {
	public PanelSnapshot {
		Objects.requireNonNull(islandId, "islandId");
		entries = List.copyOf(entries);
	}

	public record PanelEntry(Identifier actionId, String title, String iconItemId) {
		public static PanelEntry fromDescriptor(IslandActionDescriptor descriptor) {
			return new PanelEntry(descriptor.id(), descriptor.title(), descriptor.iconItemId());
		}
	}
}
