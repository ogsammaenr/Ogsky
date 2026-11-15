package me.ogsammaenr.ogsky.core.internal.service;

import me.ogsammaenr.ogsky.core.api.service.ServiceRegistry;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe {@link ServiceRegistry} mapping service contracts to singleton implementations.
 */
public final class SimpleServiceRegistry implements ServiceRegistry {
	private final ConcurrentHashMap<Class<?>, Object> services = new ConcurrentHashMap<>();

	@Override
	public <T> void register(Class<T> serviceType, T implementation) {
		Objects.requireNonNull(serviceType, "serviceType");
		Objects.requireNonNull(implementation, "implementation");
		if (!serviceType.isInstance(implementation)) {
			throw new IllegalArgumentException(
				implementation.getClass().getName() + " does not implement " + serviceType.getName()
			);
		}
		Object previous = services.putIfAbsent(serviceType, implementation);
		if (previous != null) {
			throw new IllegalStateException("Service already registered: " + serviceType.getName());
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> Optional<T> find(Class<T> serviceType) {
		Objects.requireNonNull(serviceType, "serviceType");
		return Optional.ofNullable((T) services.get(serviceType));
	}
}
