package me.ogsammaenr.ogsky.core.api.flag;

/**
 * 3-tier flag classification modeled after BentoBox {@code Flag.Type}.
 */
public enum FlagType {
	/**
	 * Per-island action guard storing the minimum {@code IslandRole} byte ({@code 0..6}).
	 */
	PROTECTION,

	/**
	 * Per-island boolean toggle stored in the island's compact settings bitmask.
	 */
	SETTING,

	/**
	 * Global Skyblock world rule controlled by server administrators.
	 */
	WORLD_SETTING
}
