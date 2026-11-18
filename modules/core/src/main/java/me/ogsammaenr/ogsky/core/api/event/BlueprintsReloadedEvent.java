package me.ogsammaenr.ogsky.core.api.event;

/**
 * Published after {@code ogsky-core} loads or reloads the blueprint registry (startup or {@code /isadmin reload}).
 */
public record BlueprintsReloadedEvent(long revision, int bundleCount) implements OgskyEvent {
}
