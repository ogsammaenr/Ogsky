package me.ogsammaenr.ogsky.core.api;

import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Global entry point for the Ogsky Core server API.
 */
public final class OgskyCore {
	public static final String MOD_ID = "ogsky";
	public static final String CORE_MOD_ID = "ogsky-core";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final AtomicReference<OgskyCoreApi> INSTANCE = new AtomicReference<>();

	private OgskyCore() {
	}

	/**
	 * Creates a namespaced {@link Identifier} under the {@code ogsky} namespace.
	 */
	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	/**
	 * Returns the active {@link OgskyCoreApi} bound during core mod initialization.
	 */
	public static OgskyCoreApi api() {
		OgskyCoreApi api = INSTANCE.get();
		if (api == null) {
			throw new IllegalStateException("OgskyCoreApi has not been initialized yet.");
		}
		return api;
	}

	/**
	 * Binds the active {@link OgskyCoreApi} instance. Intended for core bootstrap and tests.
	 */
	public static void bind(OgskyCoreApi api) {
		Objects.requireNonNull(api, "api");
		if (!INSTANCE.compareAndSet(null, api)) {
			throw new IllegalStateException("OgskyCoreApi is already bound.");
		}
	}

	/**
	 * Clears the bound {@link OgskyCoreApi} instance (intended for unit test teardown).
	 */
	public static void resetForTesting() {
		INSTANCE.set(null);
	}
}
