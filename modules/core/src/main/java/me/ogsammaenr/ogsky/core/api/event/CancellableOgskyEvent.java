package me.ogsammaenr.ogsky.core.api.event;

/**
 * Contract for pre-action domain events dispatched synchronously on the main server tick thread
 * that listeners may cancel before mutation occurs.
 */
public interface CancellableOgskyEvent extends OgskyEvent {
	boolean isCancelled();

	void setCancelled(boolean cancelled);

	String cancelReason();

	void cancel(String reason);

	default void cancel() {
		setCancelled(true);
	}
}
