package me.ogsammaenr.ogsky.core.api.island;

import net.minecraft.resources.Identifier;

import java.time.Instant;
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

	Instant createdAt();

	boolean isOverworldReady();

	boolean isNetherReady();

	boolean isEndReady();

	boolean isPasting();

	boolean isDeleting();

	/** Overworld home spawn offset relative to the island center. */
	RelativeSpawn overworldSpawn();

	/** Nether home spawn offset relative to the island center. */
	RelativeSpawn netherSpawn();

	/** End home spawn offset relative to the island center. */
	RelativeSpawn endSpawn();

	/** Returns {@code true} if horizontal world coordinates {@code (x, z)} fall within this island's active protection range. */
	boolean contains(int x, int z);

	/**
	 * Returns the player's effective membership role ({@code 1..5}) or {@link IslandRole#VISITOR} if not a member.
	 */
	byte roleOf(UUID playerUuid);

	/** Returns {@code true} if {@code playerUuid} is currently banned from this island. */
	boolean isBanned(UUID playerUuid);

	/** Returns an immutable snapshot of island members and their {@link IslandRole} ranks. */
	List<IslandMemberEntry> members();

	/** Returns an immutable list of banned player UUIDs for this island. */
	List<UUID> bannedPlayers();

	/** Returns the effective minimum {@link IslandRole} rank required for {@code PROTECTION} flag {@code flagId}. */
	byte protectionRank(Identifier flagId);

	/** Returns the effective boolean state of island {@code SETTING} flag {@code flagId}. */
	boolean isSettingEnabled(Identifier flagId);
}
