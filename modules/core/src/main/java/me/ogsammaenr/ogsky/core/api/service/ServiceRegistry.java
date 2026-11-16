package me.ogsammaenr.ogsky.core.api.service;

import java.util.Optional;

/**
 * Type-safe registry for optional capabilities exposed across Ogsky modules without compile coupling.
 */
public interface ServiceRegistry {
	/**
	 * Registers a singleton implementation for {@code serviceType}.
	 *
	 * @throws IllegalStateException if an implementation is already registered for {@code serviceType}
	 */
	<T> void register(Class<T> serviceType, T implementation);

	/**
	 * Looks up an optional capability implementation without requiring the providing module to be bundled.
	 */
	<T> Optional<T> find(Class<T> serviceType);

	/**
	 * Returns the registered implementation for {@code serviceType}, throwing if absent.
	 */
	default <T> T require(Class<T> serviceType) {
		return find(serviceType).orElseThrow(() ->
			new IllegalStateException("Required service not registered: " + serviceType.getName())
		);
	}
}
