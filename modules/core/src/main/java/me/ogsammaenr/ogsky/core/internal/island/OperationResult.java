package me.ogsammaenr.ogsky.core.internal.island;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Structured outcome returned by {@link IslandLifecycleService} and {@link TeamService} operations.
 */
public record OperationResult<T>(
	boolean success,
	String code,
	String message,
	@Nullable T value
) {
	public OperationResult {
		Objects.requireNonNull(code, "code");
		Objects.requireNonNull(message, "message");
	}

	public static <T> OperationResult<T> ok(String code, String message, @Nullable T value) {
		return new OperationResult<>(true, code, message, value);
	}

	public static OperationResult<Void> ok(String code, String message) {
		return new OperationResult<>(true, code, message, null);
	}

	public static <T> OperationResult<T> fail(String code, String message) {
		return new OperationResult<>(false, code, message, null);
	}
}
