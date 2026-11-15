package me.ogsammaenr.ogsky.core.api.event;

import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.UUID;

/**
 * Published after a per-island boolean {@code SETTING} flag toggles.
 */
public record IslandSettingFlagChangedEvent(
	UUID islandId,
	UUID actorId,
	Identifier flagId,
	boolean oldValue,
	boolean newValue
) implements OgskyEvent {
	public IslandSettingFlagChangedEvent {
		Objects.requireNonNull(islandId, "islandId");
		Objects.requireNonNull(flagId, "flagId");
	}
}
