package me.ogsammaenr.ogsky.core.api;

/**
 * Entrypoint contract for optional Ogsky modules registered under the {@code "ogsky-module"}
 * Fabric entrypoint key. Invoked deterministically by {@code ogsky-core} after core bootstrap.
 */
public interface OgskyModule {
	String moduleId();

	void onLoad(OgskyCoreApi core);
}
