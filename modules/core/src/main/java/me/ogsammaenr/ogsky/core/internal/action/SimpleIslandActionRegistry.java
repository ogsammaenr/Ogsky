package me.ogsammaenr.ogsky.core.internal.action;

import me.ogsammaenr.ogsky.core.api.action.IslandActionDescriptor;
import me.ogsammaenr.ogsky.core.api.action.IslandActionPresentation;
import me.ogsammaenr.ogsky.core.api.action.IslandActionPresenter;
import me.ogsammaenr.ogsky.core.api.action.IslandActionRegistry;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe {@link IslandActionRegistry} returning actions sorted by {@code sortOrder} then {@code id}.
 */
public final class SimpleIslandActionRegistry implements IslandActionRegistry {
	private static final Comparator<IslandActionDescriptor> ORDER = Comparator
		.comparingInt(IslandActionDescriptor::sortOrder)
		.thenComparing(d -> d.id().toString());

	private final ConcurrentHashMap<Identifier, IslandActionDescriptor> actions = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Identifier, IslandActionPresenter> presenters = new ConcurrentHashMap<>();

	@Override
	public void register(IslandActionDescriptor descriptor) {
		Objects.requireNonNull(descriptor, "descriptor");
		IslandActionDescriptor previous = actions.putIfAbsent(descriptor.id(), descriptor);
		if (previous != null) {
			throw new IllegalStateException("Duplicate island action id: " + descriptor.id());
		}
	}

	@Override
	public void registerPresenter(Identifier actionId, IslandActionPresenter presenter) {
		Objects.requireNonNull(actionId, "actionId");
		Objects.requireNonNull(presenter, "presenter");
		presenters.put(actionId, presenter);
	}

	@Override
	public Optional<IslandActionPresentation> present(
		Identifier actionId,
		UUID viewerUuid,
		@Nullable IslandView island
	) {
		Objects.requireNonNull(actionId, "actionId");
		Objects.requireNonNull(viewerUuid, "viewerUuid");
		IslandActionPresenter presenter = presenters.get(actionId);
		if (presenter == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(presenter.present(viewerUuid, island));
	}

	@Override
	public List<IslandActionDescriptor> listOrdered() {
		return actions.values().stream().sorted(ORDER).toList();
	}

	@Override
	public Optional<IslandActionDescriptor> find(Identifier id) {
		Objects.requireNonNull(id, "id");
		return Optional.ofNullable(actions.get(id));
	}
}
