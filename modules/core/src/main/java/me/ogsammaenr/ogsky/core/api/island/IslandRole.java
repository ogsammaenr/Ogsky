package me.ogsammaenr.ogsky.core.api.island;

import java.util.Locale;
import java.util.Objects;

/**
 * Primitive {@code byte} role constants ({@code 0..5}) and helpers for Skyblock island memberships.
 */
public final class IslandRole {
	public static final byte VISITOR = 0;
	public static final byte COOP = 1;
	public static final byte TRUSTED = 2;
	public static final byte MEMBER = 3;
	public static final byte ADMIN = 4;
	public static final byte OWNER = 5;

	/**
	 * Optional sentinel rank for {@code PROTECTION} flags indicating an action is disabled even for the owner.
	 */
	public static final byte DISABLED = 6;

	private IslandRole() {
	}

	/**
	 * Returns {@code true} if {@code role} is a persisted island membership role ({@code COOP..OWNER}).
	 */
	public static boolean isValidMemberRole(byte role) {
		return role >= COOP && role <= OWNER;
	}

	/**
	 * Returns {@code true} if {@code rank} is a valid {@code PROTECTION} flag threshold ({@code VISITOR..DISABLED}).
	 */
	public static boolean isValidThreshold(byte rank) {
		return rank >= VISITOR && rank <= DISABLED;
	}

	/**
	 * Returns {@code true} if {@code role} counts as the player's single primary island membership ({@code MEMBER..OWNER}).
	 */
	public static boolean isPrimary(byte role) {
		return role >= MEMBER && role <= OWNER;
	}

	/**
	 * Returns {@code true} if {@code role} is a capped secondary helper role ({@code COOP} or {@code TRUSTED}).
	 */
	public static boolean isSecondary(byte role) {
		return role == COOP || role == TRUSTED;
	}

	/**
	 * Canonical lowercase identifier key for a role or threshold byte.
	 */
	public static String defaultKey(byte role) {
		return switch (role) {
			case VISITOR -> "visitor";
			case COOP -> "coop";
			case TRUSTED -> "trusted";
			case MEMBER -> "member";
			case ADMIN -> "admin";
			case OWNER -> "owner";
			case DISABLED -> "disabled";
			default -> throw new IllegalArgumentException("Unknown IslandRole byte: " + role);
		};
	}

	/**
	 * Parses a role key (e.g. {@code "member"}) or numeric string ({@code "0".."6"}) into a protection threshold byte.
	 */
	public static byte parseThreshold(String raw) {
		Objects.requireNonNull(raw, "raw");
		String normalized = raw.trim().toLowerCase(Locale.ROOT);
		return switch (normalized) {
			case "visitor", "0" -> VISITOR;
			case "coop", "1" -> COOP;
			case "trusted", "2" -> TRUSTED;
			case "member", "3" -> MEMBER;
			case "admin", "4" -> ADMIN;
			case "owner", "5" -> OWNER;
			case "disabled", "6" -> DISABLED;
			default -> throw new IllegalArgumentException("Invalid IslandRole threshold: " + raw);
		};
	}
}
