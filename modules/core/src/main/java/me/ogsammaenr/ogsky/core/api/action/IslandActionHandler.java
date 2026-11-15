package me.ogsammaenr.ogsky.core.api.action;

/**
 * Callback executed server-side when an island action is triggered.
 */
@FunctionalInterface
public interface IslandActionHandler {
	void execute(IslandActionContext context);
}
