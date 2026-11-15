package me.ogsammaenr.ogsky.core.api.service;

import java.util.Optional;

/**
 * Type-safe registry for optional capabilities exposed across Ogsky modules without compile coupling.
 */
public interface ServiceRegistry {
	<T> void register(Class<T> serviceType, T implementation);

	<T> Optional<T> find(Class<T> serviceType);

	default <T> T require(Class<T> serviceType) {
		return find(serviceType).orElseThrow(() ->
			new IllegalStateException("Required service not registered: " + serviceType.getName())
		);
	}
}
