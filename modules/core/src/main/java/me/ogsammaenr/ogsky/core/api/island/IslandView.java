package me.ogsammaenr.ogsky.core.api.island;

import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.UUID;

/**
 * Read-only view of a Skyblock island shared across {@code ogsky:overworld}, {@code ogsky:nether}, and {@code ogsky:end}.
 */
public interface IslandView {
	UUID islandId();

	UUID ownerId();

	int gridX();

	int gridZ();

	int centerX();

	int centerZ();

	short halfSize();

	default int minX() {
		return centerX() - halfSize();
	}

	default int maxX() {
		return centerX() + halfSize();
	}

	default int minZ() {
		return centerZ() - halfSize();
	}

	default int maxZ() {
		return centerZ() + halfSize();
	}

	String bundleId();

	boolean isOverworldReady();

	boolean isNetherReady();

	boolean isEndReady();

	boolean isPasting();

	boolean isDeleting();

	RelativeSpawn overworldSpawn();

	RelativeSpawn netherSpawn();

	RelativeSpawn endSpawn();

	boolean contains(int x, int z);

	/**
	 * Returns the player's effective membership role ({@code 1..5}) or {@link IslandRole#VISITOR} if not a member.
	 */
	byte roleOf(UUID playerUuid);

	boolean isBanned(UUID playerUuid);

	List<IslandMemberEntry> members();

	List<UUID> bannedPlayers();

	byte protectionRank(Identifier flagId);

	boolean isSettingEnabled(Identifier flagId);
}
