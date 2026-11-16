package me.ogsammaenr.ogsky.core.api.mutation;

import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Result returned by a {@link BlockPlacementGuard}.
 */
public final class BlockMutationDecision {
	public enum Outcome {
		ALLOW,
		DENY,
		REPLACE
	}

	private static final BlockMutationDecision ALLOW = new BlockMutationDecision(Outcome.ALLOW, null);
	private static final BlockMutationDecision DENY = new BlockMutationDecision(Outcome.DENY, null);

	private final Outcome outcome;
	private final @Nullable BlockState replacementState;

	private BlockMutationDecision(Outcome outcome, @Nullable BlockState replacementState) {
		this.outcome = outcome;
		this.replacementState = replacementState;
	}

	public static BlockMutationDecision allow() {
		return ALLOW;
	}

	public static BlockMutationDecision deny() {
		return DENY;
	}

	public static BlockMutationDecision replace(BlockState replacementState) {
		Objects.requireNonNull(replacementState, "replacementState");
		return new BlockMutationDecision(Outcome.REPLACE, replacementState);
	}

	public Outcome outcome() {
		return outcome;
	}

	public boolean isAllowed() {
		return outcome == Outcome.ALLOW;
	}

	public boolean isDenied() {
		return outcome == Outcome.DENY;
	}

	public boolean isReplaced() {
		return outcome == Outcome.REPLACE;
	}

	public @Nullable BlockState replacementState() {
		return replacementState;
	}
}
