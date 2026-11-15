package me.ogsammaenr.ogsky.core.api.event;

/**
 * Shared cancellation state helper for cancellable {@link OgskyEvent} implementations.
 */
public abstract class AbstractCancellableOgskyEvent implements CancellableOgskyEvent {
	private boolean cancelled;
	private String cancelReason;

	@Override
	public boolean isCancelled() {
		return cancelled;
	}

	@Override
	public void setCancelled(boolean cancelled) {
		this.cancelled = cancelled;
		if (!cancelled) {
			this.cancelReason = null;
		}
	}

	@Override
	public String cancelReason() {
		return cancelReason;
	}

	@Override
	public void cancel(String reason) {
		setCancelled(true);
		if (isCancelled()) {
			this.cancelReason = reason;
		}
	}
}
