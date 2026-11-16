package me.ogsammaenr.ogsky.core.api.event;

import java.util.function.Consumer;

/**
 * Typed event bus enabling loose coupling between {@code ogsky-core} and optional modules.
 */
public interface OgskyEventBus {
	/**
	 * Subscribes a listener for exact {@code eventType} dispatches and returns a handle to unsubscribe later.
	 */
	<T extends OgskyEvent> Subscription subscribe(Class<T> eventType, Consumer<T> listener);

	/**
	 * Synchronously dispatches {@code event} to all subscribers in registration order.
	 * Callers MUST invoke this on the main Minecraft server tick thread.
	 */
	<T extends OgskyEvent> void publish(T event);

	/**
	 * Publishes a cancellable event and returns {@code true} if no listener cancelled it.
	 */
	default <T extends CancellableOgskyEvent> boolean publishCancellable(T event) {
		publish(event);
		return !event.isCancelled();
	}

	@FunctionalInterface
	interface Subscription extends AutoCloseable {
		void unsubscribe();

		@Override
		default void close() {
			unsubscribe();
		}
	}
}
