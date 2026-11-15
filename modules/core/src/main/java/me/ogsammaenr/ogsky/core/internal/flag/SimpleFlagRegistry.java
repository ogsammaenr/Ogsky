package me.ogsammaenr.ogsky.core.internal.flag;

import me.ogsammaenr.ogsky.core.api.flag.FlagRegistry;
import me.ogsammaenr.ogsky.core.api.flag.FlagType;
import me.ogsammaenr.ogsky.core.api.flag.IslandFlagDefinition;
import net.minecraft.resources.Identifier;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic {@link FlagRegistry} assigning compact per-{@link FlagType} slot indices on {@link #freeze()}.
 */
public final class SimpleFlagRegistry implements FlagRegistry {
	private static final Comparator<IslandFlagDefinition> ORDER = Comparator
		.comparingInt(IslandFlagDefinition::sortOrder)
		.thenComparing(def -> def.id().toString());

	private static final int MAX_SETTING_BITS = Long.SIZE;

	private final Map<Identifier, IslandFlagDefinition> definitions = new HashMap<>();
	private final Map<Identifier, Integer> slots = new HashMap<>();
	private final EnumMap<FlagType, List<IslandFlagDefinition>> orderedByType = new EnumMap<>(FlagType.class);
	private volatile List<IslandFlagDefinition> orderedAll = List.of();
	private volatile boolean frozen;

	public SimpleFlagRegistry() {
		for (FlagType type : FlagType.values()) {
			orderedByType.put(type, List.of());
		}
	}

	@Override
	public synchronized void register(IslandFlagDefinition definition) {
		Objects.requireNonNull(definition, "definition");
		if (frozen) {
			throw new IllegalStateException("Cannot register flag after FlagRegistry has frozen: " + definition.id());
		}
		IslandFlagDefinition previous = definitions.putIfAbsent(definition.id(), definition);
		if (previous != null) {
			throw new IllegalStateException("Duplicate flag id registered: " + definition.id());
		}
	}

	/**
	 * Locks flag registration and assigns contiguous slot indices {@code 0..N-1} per {@link FlagType}.
	 */
	public synchronized void freeze() {
		if (frozen) {
			return;
		}
		slots.clear();
		for (FlagType type : FlagType.values()) {
			List<IslandFlagDefinition> sorted = definitions.values().stream()
				.filter(def -> def.type() == type)
				.sorted(ORDER)
				.toList();
			if (type == FlagType.SETTING && sorted.size() > MAX_SETTING_BITS) {
				throw new IllegalStateException(
					"Too many SETTING flags (" + sorted.size() + "); max supported in 64-bit mask is " + MAX_SETTING_BITS
				);
			}
			for (int i = 0; i < sorted.size(); i++) {
				slots.put(sorted.get(i).id(), i);
			}
			orderedByType.put(type, sorted);
		}
		orderedAll = definitions.values().stream().sorted(ORDER).toList();
		frozen = true;
	}

	@Override
	public synchronized Optional<IslandFlagDefinition> find(Identifier id) {
		Objects.requireNonNull(id, "id");
		return Optional.ofNullable(definitions.get(id));
	}

	@Override
	public synchronized List<IslandFlagDefinition> listAll() {
		if (frozen) {
			return orderedAll;
		}
		return definitions.values().stream().sorted(ORDER).toList();
	}

	@Override
	public synchronized List<IslandFlagDefinition> listByType(FlagType type) {
		Objects.requireNonNull(type, "type");
		if (frozen) {
			return orderedByType.get(type);
		}
		return definitions.values().stream()
			.filter(def -> def.type() == type)
			.sorted(ORDER)
			.toList();
	}

	@Override
	public synchronized int slotOf(Identifier id) {
		Objects.requireNonNull(id, "id");
		if (!frozen) {
			throw new IllegalStateException("FlagRegistry must be frozen before querying slot indices.");
		}
		Integer slot = slots.get(id);
		if (slot == null) {
			throw new IllegalArgumentException("Unknown Ogsky flag id: " + id);
		}
		return slot;
	}

	@Override
	public synchronized int count(FlagType type) {
		Objects.requireNonNull(type, "type");
		if (frozen) {
			return orderedByType.get(type).size();
		}
		return (int) definitions.values().stream().filter(def -> def.type() == type).count();
	}

	@Override
	public boolean isFrozen() {
		return frozen;
	}
}
