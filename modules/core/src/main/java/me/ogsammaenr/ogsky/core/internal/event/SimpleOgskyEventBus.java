package me.ogsammaenr.ogsky.core.internal.event;

import me.ogsammaenr.ogsky.core.api.event.OgskyEvent;
import me.ogsammaenr.ogsky.core.api.event.OgskyEventBus;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Thread-safe {@link OgskyEventBus} dispatching events synchronously in subscription order.
 */
public final class SimpleOgskyEventBus implements OgskyEventBus {
	private final ConcurrentHashMap<Class<?>, CopyOnWriteArrayList<Consumer<?>>> listeners = new ConcurrentHashMap<>();

	@Override
	public <T extends OgskyEvent> Subscription subscribe(Class<T> eventType, Consumer<T> listener) {
		Objects.requireNonNull(eventType, "eventType");
		Objects.requireNonNull(listener, "listener");
		CopyOnWriteArrayList<Consumer<?>> list = listeners.computeIfAbsent(
			eventType,
			ignored -> new CopyOnWriteArrayList<>()
		);
		list.add(listener);
		return () -> list.remove(listener);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T extends OgskyEvent> void publish(T event) {
		Objects.requireNonNull(event, "event");
		List<Consumer<?>> registered = listeners.get(event.getClass());
		if (registered == null || registered.isEmpty()) {
			return;
		}
		for (Consumer<?> raw : registered) {
			((Consumer<T>) raw).accept(event);
		}
	}
}
