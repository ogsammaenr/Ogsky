package me.ogsammaenr.ogsky.core.api.flag;

import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Optional;

/**
 * Registry of 3-tier island and world flags contributed by {@code ogsky-core} and optional modules.
 * Slot indices are assigned deterministically per {@link FlagType} once frozen after module initialization.
 */
public interface FlagRegistry {
	/**
	 * Registers a flag definition before the registry is frozen at the end of module initialization.
	 *
	 * @throws IllegalStateException if the registry is already frozen or a duplicate {@code id} is registered
	 */
	void register(IslandFlagDefinition definition);

	/**
	 * Looks up a registered flag definition by its namespaced {@link Identifier}.
	 */
	Optional<IslandFlagDefinition> find(Identifier id);

	/**
	 * Returns the flag definition for {@code id}, throwing {@link IllegalArgumentException} if unknown.
	 */
	default IslandFlagDefinition require(Identifier id) {
		return find(id).orElseThrow(() ->
			new IllegalArgumentException("Unknown Ogsky flag: " + id)
		);
	}

	/**
	 * Returns all registered flags ordered by {@code sortOrder} then {@code id}.
	 */
	List<IslandFlagDefinition> listAll();

	/**
	 * Returns all registered flags of the specified {@link FlagType}, ordered deterministically.
	 */
	List<IslandFlagDefinition> listByType(FlagType type);

	/**
	 * Returns the frozen per-{@link FlagType} slot index ({@code 0..count(type)-1}) for {@code id}.
	 */
	int slotOf(Identifier id);

	/**
	 * Returns the number of registered flags belonging to {@code type}.
	 */
	int count(FlagType type);

	/**
	 * Returns {@code true} once module loading finished and slot assignments were locked.
	 */
	boolean isFrozen();
}
