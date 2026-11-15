package me.ogsammaenr.ogsky.core.api.action;

import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Optional;

/**
 * Registry of island actions contributed by {@code ogsky-core} and optional modules.
 */
public interface IslandActionRegistry {
	void register(IslandActionDescriptor descriptor);

	List<IslandActionDescriptor> listOrdered();

	Optional<IslandActionDescriptor> find(Identifier id);
}
