package me.ogsammaenr.ogsky.core.api.island;

import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.function.Function;

/**
 * Public structured outcome returned by {@link IslandService} mutations.
 */
public record IslandOperationResult<T>(
	boolean success,
	String code,
	String message,
	@Nullable T value
) {
	public IslandOperationResult {
		Objects.requireNonNull(code, "code");
		Objects.requireNonNull(message, "message");
	}

	public static <T> IslandOperationResult<T> ok(String code, String message, @Nullable T value) {
		return new IslandOperationResult<>(true, code, message, value);
	}

	public static IslandOperationResult<Void> ok(String code, String message) {
		return new IslandOperationResult<>(true, code, message, null);
	}

	public static <T> IslandOperationResult<T> fail(String code, String message) {
		return new IslandOperationResult<>(false, code, message, null);
	}

	public <R> IslandOperationResult<R> map(Function<T, R> mapper) {
		Objects.requireNonNull(mapper, "mapper");
		return new IslandOperationResult<>(
			success,
			code,
			message,
			value == null ? null : mapper.apply(value)
		);
	}
}
