package me.ogsammaenr.ogsky.core.api.flag;

import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import net.minecraft.resources.Identifier;

import java.util.Objects;

/**
 * Immutable metadata definition for a {@link FlagType} registered via {@link FlagRegistry}.
 */
public record IslandFlagDefinition(
	Identifier id,
	FlagType type,
	FlagMode mode,
	String iconItemId,
	int sortOrder,
	byte defaultMinRank,
	boolean defaultBoolValue,
	boolean wildernessAllowed
) {
	public IslandFlagDefinition {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(mode, "mode");
		if (iconItemId == null || iconItemId.isBlank()) {
			throw new IllegalArgumentException("Flag iconItemId must not be blank.");
		}
		if (type == FlagType.PROTECTION && !IslandRole.isValidThreshold(defaultMinRank)) {
			throw new IllegalArgumentException("Invalid protection defaultMinRank: " + defaultMinRank);
		}
	}

	public static IslandFlagDefinition protection(
		Identifier id,
		FlagMode mode,
		String iconItemId,
		int sortOrder,
		byte defaultMinRank,
		boolean wildernessAllowed
	) {
		return new IslandFlagDefinition(
			id,
			FlagType.PROTECTION,
			mode,
			iconItemId,
			sortOrder,
			defaultMinRank,
			false,
			wildernessAllowed
		);
	}

	public static IslandFlagDefinition setting(
		Identifier id,
		FlagMode mode,
		String iconItemId,
		int sortOrder,
		boolean defaultValue
	) {
		return new IslandFlagDefinition(
			id,
			FlagType.SETTING,
			mode,
			iconItemId,
			sortOrder,
			IslandRole.VISITOR,
			defaultValue,
			false
		);
	}

	public static IslandFlagDefinition worldSetting(
		Identifier id,
		FlagMode mode,
		String iconItemId,
		int sortOrder,
		boolean defaultValue
	) {
		return new IslandFlagDefinition(
			id,
			FlagType.WORLD_SETTING,
			mode,
			iconItemId,
			sortOrder,
			IslandRole.VISITOR,
			defaultValue,
			false
		);
	}
}
