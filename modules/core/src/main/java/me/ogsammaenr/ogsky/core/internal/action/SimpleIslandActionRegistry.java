package me.ogsammaenr.ogsky.core.internal.action;

import me.ogsammaenr.ogsky.core.api.action.IslandActionDescriptor;
import me.ogsammaenr.ogsky.core.api.action.IslandActionRegistry;
import net.minecraft.resources.Identifier;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe {@link IslandActionRegistry} returning actions sorted by {@code sortOrder} then {@code id}.
 */
public final class SimpleIslandActionRegistry implements IslandActionRegistry {
	private static final Comparator<IslandActionDescriptor> ORDER = Comparator
		.comparingInt(IslandActionDescriptor::sortOrder)
		.thenComparing(d -> d.id().toString());

	private final ConcurrentHashMap<Identifier, IslandActionDescriptor> actions = new ConcurrentHashMap<>();

	@Override
	public void register(IslandActionDescriptor descriptor) {
		Objects.requireNonNull(descriptor, "descriptor");
		IslandActionDescriptor previous = actions.putIfAbsent(descriptor.id(), descriptor);
		if (previous != null) {
			throw new IllegalStateException("Duplicate island action id: " + descriptor.id());
		}
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
