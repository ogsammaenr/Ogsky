package me.ogsammaenr.ogsky.core.api.flag;

import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Optional;

/**
 * Registry of 3-tier island and world flags contributed by {@code ogsky-core} and optional modules.
 * Slot indices are assigned deterministically per {@link FlagType} once frozen after module initialization.
 */
public interface FlagRegistry {
	void register(IslandFlagDefinition definition);

	Optional<IslandFlagDefinition> find(Identifier id);

	default IslandFlagDefinition require(Identifier id) {
		return find(id).orElseThrow(() ->
			new IllegalArgumentException("Unknown Ogsky flag: " + id)
		);
	}

	List<IslandFlagDefinition> listAll();

	List<IslandFlagDefinition> listByType(FlagType type);

	/**
	 * Returns the frozen per-{@link FlagType} slot index ({@code 0..count(type)-1}) for {@code id}.
	 */
	int slotOf(Identifier id);

	int count(FlagType type);

	boolean isFrozen();
}
