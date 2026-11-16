package me.ogsammaenr.ogsky.core.api.action;

import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Optional;

/**
 * Registry of island actions contributed by {@code ogsky-core} and optional modules.
 */
public interface IslandActionRegistry {
	/**
	 * Registers an action descriptor surfaced in UI panels (such as {@code ogsky-panel}).
	 *
	 * @throws IllegalStateException if an action with the same {@code id} is already registered
	 */
	void register(IslandActionDescriptor descriptor);

	/**
	 * Returns all registered actions ordered by {@code sortOrder} ascending, then {@code id}.
	 */
	List<IslandActionDescriptor> listOrdered();

	/**
	 * Finds a registered action by its namespaced {@link Identifier}.
	 */
	Optional<IslandActionDescriptor> find(Identifier id);
}
