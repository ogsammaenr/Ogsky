package me.ogsammaenr.ogsky.core.api;

/**
 * Entrypoint contract for optional Ogsky modules registered under the {@code "ogsky-module"}
 * Fabric entrypoint key. Invoked deterministically in alphabetical order of {@link #moduleId()}
 * by {@code ogsky-core} after core bootstrap and before registries freeze.
 */
public interface OgskyModule {
	/**
	 * Returns the unique lowercase identifier for this module (e.g. {@code "panel"}, {@code "level"}, {@code "limits"}).
	 * Also determines deterministic load ordering across optional modules.
	 */
	String moduleId();

	/**
	 * Registers module databases, flags, commands, actions, GUIs, services, mutation hooks, and event listeners.
	 * Called once during mod initialization prior to registry freeze.
	 */
	void onLoad(OgskyCoreApi core);
}
