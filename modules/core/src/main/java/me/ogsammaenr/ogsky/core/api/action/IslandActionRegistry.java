package me.ogsammaenr.ogsky.core.api.action;

import me.ogsammaenr.ogsky.core.api.island.IslandView;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

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
	 * Registers an action descriptor together with a dynamic viewer-aware presenter.
	 */
	default void register(IslandActionDescriptor descriptor, IslandActionPresenter presenter) {
		Objects.requireNonNull(descriptor, "descriptor");
		Objects.requireNonNull(presenter, "presenter");
		register(descriptor);
		registerPresenter(descriptor.id(), presenter);
	}

	/**
	 * Registers a dynamic viewer-aware presenter for an already-registered action ID.
	 */
	default void registerPresenter(Identifier actionId, IslandActionPresenter presenter) {
	}

	/**
	 * Resolves dynamic presentation metadata for {@code actionId} if a presenter is registered.
	 */
	default Optional<IslandActionPresentation> present(
		Identifier actionId,
		UUID viewerUuid,
		@Nullable IslandView island
	) {
		return Optional.empty();
	}

	/**
	 * Returns all registered actions ordered by {@code sortOrder} ascending, then {@code id}.
	 */
	List<IslandActionDescriptor> listOrdered();

	/**
	 * Finds a registered action by its namespaced {@link Identifier}.
	 */
	Optional<IslandActionDescriptor> find(Identifier id);
}
